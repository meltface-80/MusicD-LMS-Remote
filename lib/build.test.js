// @ts-check
"use strict";
const { buildInfo, updateStatus } = require("./build");
let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

ok("a release build reports package.json's version", buildInfo({}, { version: "1.0.86" }).version === "1.0.86" && buildInfo({}, { version: "1.0.86" }).channel === "release");
const dev = buildInfo({ APP_CHANNEL: "dev", APP_VERSION: "1.0.87-t3" }, { version: "1.0.86" });
ok("a test build reports its stamped version on the dev channel", dev.version === "1.0.87-t3" && dev.channel === "dev");
ok("a malformed stamp falls back to package.json's", buildInfo({ APP_VERSION: "rm -rf" }, { version: "1.0.86" }).version === "1.0.86");
const s = updateStatus(dev, { available: true, latest: "1.0.86", apply: { phase: "idle" } });
ok("a test build never offers the in-app update", s.available === false && s.current === "1.0.87-t3" && s.channel === "dev" && /:dev/.test(s.notes));
const r = updateStatus(buildInfo({}, { version: "1.0.86" }), { available: true, latest: "1.0.87" });
ok("a release build offers what the updater found", r.available === true && r.current === "1.0.86" && r.channel === "release");
console.log(`\n${pass}/${pass + fail} build tests passed.`);
if (fail) process.exit(1);
