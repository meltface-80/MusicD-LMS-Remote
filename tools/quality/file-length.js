#!/usr/bin/env node
// @ts-check
"use strict";
/*
 * The file-length ratchet (docs/quality-plan.md): a file under the watch line
 * is free; over it, it needs an allowance entry (tools/quality/allowance.json)
 * recording its length, and that entry only ever comes down. A file that grows
 * past its entry fails until it is split; one that shrinks must have its entry
 * lowered. A hard cap blocks any file, watched or not, from growing without
 * bound; a file named under "exempt" in the policy is spared the cap alone.
 *
 *   node tools/quality/file-length.js [check] [PATH...]   findings, exit 1 if any
 *   node tools/quality/file-length.js accept [PATH...] [--reason TEXT]
 *   node tools/quality/file-length.js hook                 Claude Code PreToolUse, JSON on stdin
 *
 * Modelled on filepawl's length gate (github.com/ohshitgorillas/filepawl); the
 * semantics are its, in Node so the repository needs nothing it doesn't have.
 */
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const ROOT = process.env.CLAUDE_PROJECT_DIR || path.resolve(__dirname, "..", "..");
const POLICY = path.join(ROOT, "tools", "quality", "policy.json");
const STATE = path.join(ROOT, "tools", "quality", "allowance.json");

/** @typedef {{ include: string[], exclude: string[], tests: string[], length: { cap: number, capTests: number, watch: number }, exempt: Record<string, string> }} Policy */
/** @typedef {{ lines: number, reason?: string }} Entry */
/** @typedef {{ version: number, allowance: Record<string, Entry> }} State */
/** @typedef {{ path: string, message: string, accept?: boolean }} Finding */

/** A glob (** , *, ?) as a regular expression over a slash-separated path. @param {string} glob */
function globToRegExp(glob) {
  let re = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        const slash = glob[i + 2] === "/";
        re += slash ? "(?:.*/)?" : ".*";
        i += slash ? 2 : 1;
      } else re += "[^/]*";
    } else if (c === "?") re += "[^/]";
    else re += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
  }
  return new RegExp("^" + re + "$");
}

/** @param {string} p @param {string[]} globs */
const matches = (p, globs) => globs.some(g => globToRegExp(g).test(p));

/** @returns {Policy} */
function readPolicy() {
  const p = JSON.parse(fs.readFileSync(POLICY, "utf8"));
  for (const k of ["include", "exclude", "tests"]) if (!Array.isArray(p[k])) throw new Error(`policy.json: "${k}" must be a list`);
  const l = p.length || {};
  for (const k of ["cap", "capTests", "watch"]) if (!Number.isInteger(l[k]) || l[k] < 1) throw new Error(`policy.json: length.${k} must be a whole number`);
  if (l.watch > l.cap) throw new Error("policy.json: the watch line is above the cap");
  return { include: p.include, exclude: p.exclude, tests: p.tests, length: l, exempt: p.exempt || {} };
}

/** @returns {State} */
function readState() {
  if (!fs.existsSync(STATE)) return { version: 1, allowance: {} };
  const s = JSON.parse(fs.readFileSync(STATE, "utf8"));
  if (s.version !== 1 || typeof s.allowance !== "object") throw new Error("allowance.json: not a version 1 allowance table");
  return s;
}

/** @param {State} s */
function writeState(s) {
  const sorted = Object.fromEntries(Object.keys(s.allowance).sort().map(k => [k, s.allowance[k]]));
  fs.writeFileSync(STATE, JSON.stringify({ version: 1, allowance: sorted }, null, 2) + "\n");
}

/** The files the gate measures: what git tracks, through the include and exclude globs, present on disk. @param {Policy} policy */
function tree(policy) {
  const out = execFileSync("git", ["ls-files", "-z"], { cwd: ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  return out.split("\0").filter(p => p && matches(p, policy.include) && !matches(p, policy.exclude) && fs.existsSync(path.join(ROOT, p)));
}

/** Lines as an editor counts them: a trailing newline ends the last line, it doesn't start another. @param {string} text */
function countLines(text) {
  if (text.length === 0) return 0;
  let n = 0;
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) n++;
  return text.endsWith("\n") ? n : n + 1;
}
/** @param {string} p */
const linesOf = (p) => countLines(fs.readFileSync(path.join(ROOT, p), "utf8"));

/** Why an allowance entry can't stand, or null. @param {string} p @param {string[]} files @param {Policy} policy */
function staleReason(p, files, policy) {
  if (!files.includes(p)) return "allowance names a path outside the tree; drop it";
  if (matches(p, policy.tests)) return "allowance names a test path; drop it";
  if (linesOf(p) <= policy.length.watch) return `back under the watch line ${policy.length.watch}; drop it`;
  return null;
}

/** @param {string} p @param {number} lines @param {Policy} policy @param {State} state @returns {Finding[]} */
function fileFindings(p, lines, policy, state) {
  const { cap, capTests, watch } = policy.length;
  const exempt = Object.prototype.hasOwnProperty.call(policy.exempt, p);
  /** @type {Finding[]} */
  const out = [];
  if (matches(p, policy.tests)) {
    if (!exempt && lines > capTests) out.push({ path: p, message: `test over cap ${capTests} (${lines} lines)` });
    return out;
  }
  if (!exempt && lines > cap) out.push({ path: p, message: `over cap ${cap} (${lines} lines); split it` });
  if (lines <= watch) return out;
  const entry = state.allowance[p];
  if (!entry) out.push({ path: p, message: `over watch line ${watch} (${lines} lines); run \`node tools/quality/file-length.js accept ${p}\``, accept: true });
  else if (lines > entry.lines) out.push({ path: p, message: `grew past allowance (${lines} > ${entry.lines}); split it` });
  else if (lines < entry.lines) out.push({ path: p, message: `shrank (${lines} < ${entry.lines}); run \`node tools/quality/file-length.js accept ${p}\``, accept: true });
  return out;
}

/**
 * Every finding over the tree ([only]: the length gate narrowed to these paths; the stale audit is whole).
 * @param {Policy} policy @param {State} state @param {string[]} only
 */
function check(policy, state, only) {
  const files = tree(policy);
  /** @type {Finding[]} */
  let out = [];
  for (const p of files) if (!only.length || only.includes(p)) out = out.concat(fileFindings(p, linesOf(p), policy, state));
  for (const p of Object.keys(state.allowance).sort()) {
    const why = staleReason(p, files, policy);
    if (why) out.push({ path: p, message: why, accept: true });
  }
  for (const p of Object.keys(policy.exempt).sort()) if (!files.includes(p)) out.push({ path: p, message: "exempt names a path outside the tree" });
  return out.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
}

/**
 * The allowance brought to the tree: entries added for newly watched files, lowered for shrunk ones, stale ones dropped; never raised.
 * @param {Policy} policy @param {State} state @param {string[]} only @param {string} reason
 */
function accept(policy, state, only, reason) {
  const files = tree(policy);
  /** @type {Record<string, Entry>} */
  const next = {};
  for (const [p, e] of Object.entries(state.allowance)) if (!staleReason(p, files, policy)) next[p] = e;
  for (const p of files) {
    if (matches(p, policy.tests) || (only.length && !only.includes(p))) continue;
    const lines = linesOf(p);
    if (lines <= policy.length.watch) continue;
    const e = next[p];
    if (!e) next[p] = { lines };
    else if (lines < e.lines) next[p] = e.reason ? { lines, reason: e.reason } : { lines };
    if (reason && only.length === 1 && next[p]) next[p].reason = reason;
  }
  writeState({ version: 1, allowance: next });
}

/**
 * The length a file would have after a Claude Code edit (Write, Edit, MultiEdit), or null when it isn't one.
 * @param {{ content?: unknown, edits?: unknown, old_string?: unknown, new_string?: unknown, replace_all?: unknown }} input @param {string} current
 */
function lengthAfter(input, current) {
  if (typeof input.content === "string") return countLines(input.content);
  /** @type {Array<{ old_string?: unknown, new_string?: unknown, replace_all?: unknown }> | null} */
  const edits = Array.isArray(input.edits) ? input.edits : typeof input.new_string === "string" ? [input] : null;
  if (!edits) return null;
  let text = current;
  for (const e of edits) {
    if (typeof e.old_string !== "string" || typeof e.new_string !== "string") return null;
    const from = e.old_string, to = e.new_string;
    if (from === "") { text = to; continue; }
    text = e.replace_all ? text.split(from).join(to) : text.replace(from, () => to);
  }
  return countLines(text);
}

/*
 * The edit-time notice: Claude Code's PreToolUse hook hands the tool's input on
 * stdin. Under the watch line nothing is said; over it, where the file stands;
 * an edit that grows a file over its cap is turned down (the file is split
 * first), unless the file is exempt or the edit leaves it no longer than it was.
 */
function hook() {
  const raw = fs.readFileSync(0, "utf8");
  let call;
  try { call = JSON.parse(raw); } catch (e) { return; }
  const input = call.tool_input || {};
  if (typeof input.file_path !== "string") return;
  const policy = readPolicy(), state = readState();
  const rel = path.relative(ROOT, path.resolve(ROOT, input.file_path)).split(path.sep).join("/");
  if (rel.startsWith("..") || !matches(rel, policy.include) || matches(rel, policy.exclude)) return;
  const full = path.join(ROOT, rel);
  const current = fs.existsSync(full) ? fs.readFileSync(full, "utf8") : "";
  const before = countLines(current), after = lengthAfter(input, current);
  if (after == null) return;
  const test = matches(rel, policy.tests), exempt = Object.prototype.hasOwnProperty.call(policy.exempt, rel);
  const cap = test ? policy.length.capTests : policy.length.cap, watch = policy.length.watch;
  if (after <= watch && !test) return;
  if (test && after <= cap) return;
  const entry = state.allowance[rel];
  const over = after > cap && !exempt;
  const stands = test ? `a test file; its cap is ${cap}` : entry ? `in the length ratchet at ${entry.lines} lines` : exempt ? "exempt from the cap, in the ratchet once accepted" : `over the watch line ${watch}`;
  const takes = `this edit takes it ${before} → ${after}`;
  const grows = after > before;
  let message;
  if (over && grows) message = `${rel}: over the cap ${cap} (${takes}). Split it before adding to it: plan the split now; it is mechanical and can be delegated.`;
  else if (entry && after > entry.lines) message = `${rel}: ${stands}; ${takes}. ${after - entry.lines} lines over the allowance; split them out before committing (node tools/quality/file-length.js check).`;
  else message = `${rel}: ${stands}; ${takes}${!entry && !test ? "; it needs an allowance entry (node tools/quality/file-length.js accept) or a split" : ""}.`;
  const out = over && grows
    ? { hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: "file-length: " + message } }
    : { hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "allow", additionalContext: "file-length: " + message }, systemMessage: "file-length: " + message };
  process.stdout.write(JSON.stringify(out) + "\n");
}

/** @param {string[]} argv */
function main(argv) {
  const cmd = argv[0] === "accept" || argv[0] === "hook" || argv[0] === "check" ? argv.shift() : "check";
  if (cmd === "hook") return hook(), 0;
  const policy = readPolicy(), state = readState();
  /** @type {string[]} */
  const paths = [], rest = argv.slice();
  let reason = "";
  while (rest.length) {
    const a = String(rest.shift());
    if (a === "--reason") reason = rest.shift() || "";
    else paths.push(path.relative(ROOT, path.resolve(ROOT, a)).split(path.sep).join("/"));
  }
  if (cmd === "accept") {
    if (reason && paths.length !== 1) throw new Error("--reason takes exactly one path");
    accept(policy, state, paths, reason);
    return 0;
  }
  const findings = check(policy, state, paths);
  for (const f of findings) console.log(`${f.path}: ${f.message}`);
  if (findings.some(f => f.accept)) console.log("node tools/quality/file-length.js accept");
  return findings.length ? 1 : 0;
}

if (require.main === module) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (e) { console.error("file-length: " + (e instanceof Error ? e.message : String(e))); process.exitCode = 2; }
}

module.exports = { globToRegExp, countLines, lengthAfter, check, accept, readPolicy, readState };
