"use strict";
// LMS gone and back (lib/lmswatch.js): the outage logged with what was in flight and the commands before it; a hang-up
// answered at once is not an outage; a slow answer is said.
const { makeLmsWatch } = require("./lmswatch");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

let t = 1000;
const warned = [];
const w = makeLmsWatch({ log: { warn: (m) => warned.push(m) }, now: () => t });
const a = w.begin("titles 0 500 tags:eDorTItg"); t += 200; w.end(a);
const b = w.begin("status - 1"); const c = w.begin("albums 500 500");
t += 50; w.end(b, "socket hang up");
ok("gone: noted, not logged yet", w.down && w.down.error === "socket hang up" && warned.length === 0);
ok("…with what was in flight", w.down.inFlight.includes("status - 1") && w.down.inFlight.includes("albums 500 500"));
t += 20000;
w.end(c, "connect ECONNREFUSED 10.0.0.2:9000");
const d = w.begin("serverstatus 0 999"); t += 30; w.end(d);
ok("back after 20 s: one outage, logged as a restart", w.outages().length === 1 && w.outages()[0].seconds === 20 && /restarted/.test(warned[0]));
ok("…naming the commands before it", /titles 0 500/.test(warned[0]) && w.outages()[0].before.some((x) => /titles 0 500/.test(x)));
const e = w.begin("can qobuz items ?"); t += 10; w.end(e, "socket hang up");
const f = w.begin("status - 1"); t += 10; w.end(f);
ok("a hang-up answered at once (a verb LMS can't dispatch): not an outage", w.outages().length === 1 && w.down === null);
const g = w.begin("titles 0 500"); t += 7000; w.end(g);
ok("a slow answer is said", warned.some((m) => /took 7.0 s/.test(m)));
console.log(`\n${pass}/${pass + fail} LMS watch tests passed.`);
process.exit(fail ? 1 : 0);
