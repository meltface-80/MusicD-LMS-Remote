#!/usr/bin/env node
// @ts-check
"use strict";
/*
 * A test build's version (bug list item 141): what dev's builds call themselves, so Settings and the app say which
 * test build is installed, and the release after it is offered over it by the updaters (1.1.4-t7 < 1.1.4). The next
 * release's number (package.json's, its last number one up) with -tN, N the commits on this branch since that release
 * was tagged, or, while that next release is on dev and not yet tagged on main, the same count up to it (versionFor;
 * first parent only: one each merged pull request, squashed or merged; a merge of main back into dev counts for
 * nothing, so the first pull request after a release is -t1, items 161 and 171). Nothing is committed: the build stamps it as it builds
 * (.github/workflows/os-image.yml, android.yml). package.json's "nextVersion", when later than the release, names the
 * next release instead (the owner's word: dev's builds after v1.1.5 are 1.1.7-tN).
 *   node tools/test-version.js      the version for this checkout, e.g. 1.1.4-t7
 */
const { execFileSync } = require("child_process");
const path = require("path");

/**
 * [released]'s test build number [count]: "1.1.3", 7 → "1.1.4-t7". A version already before its release ("1.2.0-rc1")
 * keeps its numbers ("1.2.0-t7"); none counted yet, the version as it is. [next], a release later than [released], is
 * the one numbered ("1.1.5", 3, "1.1.7" → "1.1.7-t3"); one that isn't later is passed over.
 * @param {string} released @param {number} count @param {string} [next] @returns {string}
 */
function testVersion(released, count, next) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(-[^+]+)?/.exec(String(released).trim());
  if (!m) throw new Error("not a version: " + released);
  if (!(count > 0)) return m[0].replace(/^v/, "");
  const n = next ? /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(next).trim()) : null;
  if (next && !n) throw new Error("not a release: " + next);
  if (n && later(n.slice(1, 4).map(Number), m.slice(1, 4).map(Number))) return `${n[1]}.${n[2]}.${n[3]}-t${count}`;
  const patch = m[4] ? Number(m[3]) : Number(m[3]) + 1;
  return `${m[1]}.${m[2]}.${patch}-t${count}`;
}

/** [a] after [b], each [major, minor, patch]. @param {number[]} a @param {number[]} b */
function later(a, b) {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i];
  return false;
}

/**
 * The changes on HEAD since [tag] (all of HEAD's when the tag isn't here): each commit on the first-parent line, a
 * pull request's merge commit included, but not a merge of main back into dev, which brings nothing new (what it
 * merges is on main already). All merges were left out (item 161), and pull requests merged with a merge commit
 * then counted for nothing: dev's builds after v1.1.8 said 1.1.8, 1.1.8, 1.1.9-t1, 1.1.9-t1 (item 171).
 * @param {string} tag @param {string} cwd
 */
function commitsSince(tag, cwd) {
  const git = (/** @type {string[]} */ ...a) => execFileSync("git", a, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  const ok = (/** @type {string[]} */ ...a) => { try { git(...a); return true; } catch { return false; } };
  const tagged = ok("rev-parse", "--verify", "--quiet", tag + "^{commit}");
  const main = ["refs/remotes/origin/main", "refs/heads/main"].find(r => ok("rev-parse", "--verify", "--quiet", r));
  // From main already: on main (a merge back), or, with no main here, released with the tag.
  const fromMain = (/** @type {string} */ c) => main ? ok("merge-base", "--is-ancestor", c, main) : tagged && ok("merge-base", "--is-ancestor", c, tag);
  const lines = git("rev-list", "--first-parent", "--parents", tagged ? tag + "..HEAD" : "HEAD").split("\n").filter(Boolean);
  return lines.filter(l => { const p = l.split(" "); return p.length < 3 || !fromMain(p[2]); }).length;
}

/** The newest release tagged on HEAD's history (v1.1.7), or null. @param {string} cwd @returns {string|null} */
function lastRelease(cwd) {
  try {
    return execFileSync("git", ["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*.[0-9]*.[0-9]*"],
      { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim() || null;
  } catch { return null; }
}

/**
 * This checkout's test build version, from package.json ([pkg]). Its version tagged: the next release's test build,
 * counted from that tag. Not tagged yet (the release made on dev, waiting for main to tag it): still a test build
 * of that release, counted from the release before it, as every build since that one was (the owner's 1.1.8-tN up to
 * v1.1.8 itself; it was 1.1.9-t224, counted from the first commit, item 165).
 * @param {{ version: string, nextVersion?: string }} pkg @param {string} cwd @returns {string}
 */
function versionFor(pkg, cwd) {
  const tag = "v" + pkg.version;
  const prev = lastRelease(cwd);
  if (prev && prev !== tag && later(parse(pkg.version), parse(prev))) return testVersion(prev, commitsSince(prev, cwd), pkg.version);
  return testVersion(pkg.version, commitsSince(tag, cwd), pkg.nextVersion);
}

/** [major, minor, patch] of "v1.1.8" or "1.1.8". @param {string} v */
function parse(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)/.exec(v);
  return m ? m.slice(1, 4).map(Number) : [0, 0, 0];
}

if (require.main === module) {
  const root = path.join(__dirname, "..");
  process.stdout.write(versionFor(require(path.join(root, "package.json")), root) + "\n");
}

module.exports = { testVersion, commitsSince, versionFor };
