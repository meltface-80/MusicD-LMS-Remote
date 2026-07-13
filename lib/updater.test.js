// Tests for the self-updater. Run: node lib/updater.test.js
// No network: exercises the pure semver compare, the getStatus() shape (which
// the routes/frontend depend on), and the applyStaged() overlay/exclude logic
// against scratch dirs. The GitHub check + tarball download paths are covered
// out-of-band by the offline harness (local http tarball) described in the task.
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const U = require("./updater");

let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

// --- _cmp semver-ish compare ----------------------------------------------
assert.strictEqual(U._cmp("1.0.1", "1.0.0"), 1);
assert.strictEqual(U._cmp("1.0.0", "1.0.1"), -1);
assert.strictEqual(U._cmp("1.2.0", "1.2.0"), 0);
assert.strictEqual(U._cmp("1.10.0", "1.9.0"), 1);   // numeric, not lexical
assert.strictEqual(U._cmp("2.0", "2.0.0"), 0);      // missing parts count as 0
assert.strictEqual(U._cmp("1.2.0-beta", "1.2.0"), 0); // suffix ignored (coarse)
ok("_cmp orders versions numerically");

// --- getStatus() shape (routes + frontend poll depend on these fields) ----
const up = U.makeUpdater({ owner: "o", repo: "r", currentVersion: "1.0.0", viaLauncher: true });
const st = up.getStatus();
for (const k of ["available", "latest", "current", "notes", "url", "isDowngrade",
                 "checkedAt", "error", "apply", "viaLauncher"]) {
  assert.ok(Object.prototype.hasOwnProperty.call(st, k), "getStatus missing " + k);
}
assert.strictEqual(st.current, "1.0.0");
assert.strictEqual(st.available, false);
assert.strictEqual(st.viaLauncher, true);
assert.deepStrictEqual(st.apply, { phase: "idle", error: null, version: null });
ok("getStatus() carries notifier fields + apply/viaLauncher");

// strips a leading v on the current version
assert.strictEqual(U.makeUpdater({ currentVersion: "v2.3.4" }).getStatus().current, "2.3.4");
ok("current version strips a leading v");

// --- _forceState seeds an available update for the apply() harness --------
{
  const u3 = U.makeUpdater({ owner: "o", repo: "r", currentVersion: "1.0.0" });
  const s3 = u3._forceState({ available: true, latest: "1.1.0", downloadUrl: "http://127.0.0.1:1/x.tgz" });
  assert.strictEqual(s3.available, true);
  assert.strictEqual(s3.latest, "1.1.0");
  ok("_forceState arms available+latest for offline apply()");
}

(async () => {
  // --- applyStaged() overlay: deps UNCHANGED -> no npm install ------------
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "updtest-"));
  const staged = path.join(root, "staged");
  const target = path.join(root, "target");
  fs.mkdirSync(staged, { recursive: true });
  fs.mkdirSync(path.join(target, "data"), { recursive: true });
  fs.mkdirSync(path.join(target, "node_modules", "x"), { recursive: true });

  const deps = { dependencies: { express: "^4.19.2" } };
  fs.writeFileSync(path.join(staged, "index.js"), "// NEW\n");
  fs.writeFileSync(path.join(staged, "package.json"), JSON.stringify(deps));
  fs.writeFileSync(path.join(staged, "brand-new.js"), "// added\n");
  fs.writeFileSync(path.join(target, "index.js"), "// OLD\n");
  fs.writeFileSync(path.join(target, "package.json"), JSON.stringify(deps));
  fs.writeFileSync(path.join(target, "data", "keep.txt"), "precious");
  fs.writeFileSync(path.join(target, "node_modules", "x", "m.js"), "dep");

  let npmRan = false;
  const origPath = process.env.PATH;
  U.applyStaged(staged, target, { log: (m) => { if (String(m).includes("npm install")) npmRan = true; } });

  assert.strictEqual(fs.readFileSync(path.join(target, "index.js"), "utf8"), "// NEW\n");
  assert.ok(fs.existsSync(path.join(target, "brand-new.js")), "new file copied");
  assert.strictEqual(fs.readFileSync(path.join(target, "data", "keep.txt"), "utf8"), "precious");
  assert.ok(fs.existsSync(path.join(target, "node_modules", "x", "m.js")), "node_modules untouched");
  assert.strictEqual(npmRan, false, "npm install must NOT run when deps unchanged");
  ok("applyStaged overlays code, preserves data/ + node_modules, skips npm when deps equal");

  // --- applyStaged() overlay: deps CHANGED -> attempts npm install -------
  // Point PATH at a stub `npm` so the install attempt is observable + fast and
  // can't reach the network. applyStaged tolerates any exit status.
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin, { recursive: true });
  const marker = path.join(root, "npm-ran");
  fs.writeFileSync(path.join(bin, "npm"), "#!/bin/sh\ntouch '" + marker + "'\nexit 0\n");
  fs.chmodSync(path.join(bin, "npm"), 0o755);
  process.env.PATH = bin + path.delimiter + origPath;

  fs.writeFileSync(path.join(staged, "package.json"),
    JSON.stringify({ dependencies: { express: "^4.19.2", compression: "^1.7.4" } }));
  let npmRan2 = false;
  U.applyStaged(staged, target, { log: (m) => { if (String(m).includes("npm install")) npmRan2 = true; } });
  process.env.PATH = origPath;

  assert.strictEqual(npmRan2, true, "npm install attempted when deps differ");
  assert.ok(fs.existsSync(marker), "stub npm was actually invoked");
  ok("applyStaged runs npm install when dependencies change");

  fs.rmSync(root, { recursive: true, force: true });
  console.log("\nAll " + n + " updater test groups passed.");
})().catch((e) => { console.error(e); process.exit(1); });
