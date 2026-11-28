#!/usr/bin/env node
// @ts-check
"use strict";
/*
 * The @ts-check ratchet (docs/quality-plan.md, "Types"): every Node module the
 * repository runs outside the tests (tools/**; the Node server's index.js,
 * launcher.js, reset-password.js and lib/** until v0.9.0) opens with
 * `// @ts-check`, which has TypeScript's checker read it as the JavaScript it
 * is: no rewrite, no build step, and the renamed-variable class of bug fails
 * before the code runs. The modules that didn't pass when the gate came are
 * listed in tools/quality/ts-baseline.json, and that list only ever shrinks:
 * a listed module that gains the pragma must leave it.
 *
 * This checks the pragmas; `npm run typecheck` then runs tsc.
 */
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..", "..");
const BASELINE = path.join(__dirname, "ts-baseline.json");
const REQUIRED = /^(tools\/.+\.js|lib\/(?!.*\.test\.js$).+\.js|index\.js|launcher\.js)$/;
const IGNORED = /(^|\/)node_modules\//;
const PRAGMA = /^(#![^\n]*\n)?\/\/ @ts-check\n/;

/**
 * The decision, free of I/O: a required module without the pragma fails unless baselined; a baselined one
 * that carries it, or is gone, fails too, so the list only shrinks.
 * @param {Record<string, boolean>} files path → whether it carries the pragma @param {string[]} exempt
 */
function judge(files, exempt) {
  const failures = [];
  const ex = new Set(exempt);
  for (const f of Object.keys(files).sort()) {
    if (files[f] && ex.has(f)) failures.push(`${f}: now carries // @ts-check; take it out of tools/quality/ts-baseline.json`);
    if (!files[f] && !ex.has(f)) failures.push(`${f}: must open with // @ts-check (line 1, or line 2 after a #! line)`);
  }
  for (const f of [...ex].sort()) if (!(f in files)) failures.push(`${f}: gone, but still in tools/quality/ts-baseline.json`);
  return failures;
}

function main() {
  const out = execFileSync("git", ["ls-files", "-z"], { cwd: ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  /** @type {Record<string, boolean>} */
  const files = {};
  for (const f of out.split("\0")) {
    if (!f || !REQUIRED.test(f) || IGNORED.test(f) || !fs.existsSync(path.join(ROOT, f))) continue;
    files[f] = PRAGMA.test(fs.readFileSync(path.join(ROOT, f), "utf8"));
  }
  const failures = judge(files, JSON.parse(fs.readFileSync(BASELINE, "utf8")));
  for (const f of failures) console.error(`${f}`);
  if (!failures.length) {
    const checked = Object.values(files).filter(Boolean).length;
    console.log(`@ts-check: ${checked} of ${Object.keys(files).length} server modules checked; the rest are listed and only shrink`);
  }
  return failures.length ? 1 : 0;
}

if (require.main === module) process.exitCode = main();

module.exports = { judge, PRAGMA };
