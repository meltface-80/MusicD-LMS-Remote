/*
 * The shared MusicBrainz gate. The thing worth testing is the property the
 * per-module copies did NOT have: N callers awaiting at the same instant are
 * serialised, rather than all reading the same "last" timestamp and going
 * together.
 */
"use strict";
const { mbWait, mbQuote, MB_GAP_MS, MB_UA } = require("./musicbrainz");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}

(async () => {
  // Lucene escaping — a name with a hyphen or a colon must not change the query
  // it lands in.
  ok("escapes Lucene operators", mbQuote("AC/DC") === "AC\\/DC", mbQuote("AC/DC"));
  ok("escapes a hyphen", mbQuote("Jay-Z") === "Jay\\-Z", mbQuote("Jay-Z"));
  ok("leaves a plain name alone", mbQuote("Portishead") === "Portishead");
  ok("a non-string is coerced, not thrown at", mbQuote(null) === "null");
  ok("the User-Agent identifies the app", /MusicD-LMS-Remote/.test(MB_UA), MB_UA);

  // The first call goes immediately — a cold gate must not cost a second.
  const t0 = Date.now();
  await mbWait();
  ok("the first caller doesn't wait", Date.now() - t0 < MB_GAP_MS / 2, Date.now() - t0);

  // Four callers firing at once must leave in four separate slots. The old
  // per-module gate compared timestamps, so simultaneous callers all saw the
  // same `last` and all went at once — which is the violation this exists to
  // stop. Timing, not call count, is the only way to observe it.
  const start = Date.now();
  const stamps = [];
  await Promise.all([0, 1, 2, 3].map(() => mbWait().then(() => stamps.push(Date.now() - start))));
  stamps.sort((a, b) => a - b);
  const gaps = stamps.slice(1).map((s, i) => s - stamps[i]);
  ok("four concurrent callers are serialised (" + stamps.join("ms, ") + "ms)",
     gaps.every(g => g >= MB_GAP_MS - 60), gaps);

  // A rejected turn must not wedge the queue for everyone behind it.
  const boom = mbWait().then(() => { throw new Error("caller blew up"); });
  await boom.catch(() => {});
  const after = Date.now();
  await mbWait();
  ok("a caller that throws doesn't wedge the queue", Date.now() - after < MB_GAP_MS * 2,
     Date.now() - after);

  console.log(fail ? `\n${fail} FAILED, ${pass} passed.` : `\n${pass}/${pass} MusicBrainz gate tests passed.`);
  process.exit(fail ? 1 : 0);
})();
