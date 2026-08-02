"use strict";

/* Home picks — the point of this store is that the SAME period keeps the SAME
 * pick across restarts, and only re-picks when the remembered thing is gone. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const makeHomePicks = require("./homepicks");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "hp-test-"));
const FILE = path.join(dir, "home-picks.json");
const mk = () => makeHomePicks({ dataDir: dir });
const reset = () => { try { fs.unlinkSync(FILE); } catch (e) {} };
const always = () => true;

// ---- the pick is stable for its period ----
{
  reset();
  // A "library" whose contents shift, exactly like index.records across a
  // restart. If the pick were positional it would follow the shuffle.
  let libraryA = ["a1", "a2", "a3", "a4", "a5"];
  let calls = 0;
  const choose = (lib) => () => { calls++; return lib[2]; };   // whatever the rule picks

  const first = mk().stable("aotd", "2026-8-2", (v) => libraryA.includes(v), choose(libraryA));
  ok("first call picks and stores", first === "a3", first);
  ok("choose() ran exactly once", calls === 1, calls);

  // Same period, brand-new store instance (i.e. a server restart) AND a library
  // whose ordering/length changed — the pick must not move.
  const libraryB = ["zz", "a5", "a3", "a1", "a2", "a4", "new"];
  const again = mk().stable("aotd", "2026-8-2", (v) => libraryB.includes(v), choose(libraryB));
  ok("same period after a restart returns the SAME pick", again === "a3", again);
  ok("choose() was not called again", calls === 1, calls);
}

// ---- a new period re-picks ----
{
  const lib = ["a1", "a2", "a3"];
  const s = mk();
  const d1 = s.stable("aotd", "2026-8-2", (v) => lib.includes(v), () => "a3");
  const d2 = s.stable("aotd", "2026-8-3", (v) => lib.includes(v), () => "a1");
  ok("a new day picks afresh", d1 === "a3" && d2 === "a1", { d1, d2 });
  ok("the new pick is what's stored now", s.peek("aotd").value === "a1", s.peek("aotd"));
  ok("the stored key is the new period", s.peek("aotd").key === "2026-8-3", s.peek("aotd"));
}

// ---- a pick that no longer exists is replaced ----
{
  reset();
  const s = mk();
  s.stable("aotd", "2026-8-2", always, () => "gone-album");
  // Same day, but that album has been removed from the library by a rescan.
  const replaced = s.stable("aotd", "2026-8-2", (v) => v === "still-here", () => "still-here");
  ok("a vanished pick is re-picked rather than returned", replaced === "still-here", replaced);
  ok("the replacement is persisted", s.peek("aotd").value === "still-here", s.peek("aotd"));
}

// ---- kinds are independent ----
{
  reset();
  const s = mk();
  s.stable("aotd", "2026-8-2", always, () => "album-1");
  s.stable("lotw", "2026-W31", always, () => "label-1");
  ok("two kinds coexist", s.peek("aotd").value === "album-1" && s.peek("lotw").value === "label-1");
  // A new week must not disturb the day's pick.
  s.stable("lotw", "2026-W32", always, () => "label-2");
  ok("a new week leaves the day's pick alone", s.peek("aotd").value === "album-1", s.peek("aotd"));
  ok("the week's pick did move", s.peek("lotw").value === "label-2", s.peek("lotw"));
}

// ---- robustness ----
{
  reset();
  const s = mk();
  ok("choose returning null yields null", s.stable("aotd", "d", always, () => null) === null);
  ok("nothing is stored for a null pick", s.peek("aotd") === null, s.peek("aotd"));

  // A throwing chooser must not take the request down.
  ok("a throwing chooser yields null", s.stable("aotd", "d", always, () => { throw new Error("boom"); }) === null);

  // A throwing validator falls through to a fresh pick rather than propagating.
  s.stable("aotd", "d2", always, () => "x");
  const afterBadValidator = s.stable("aotd", "d2", () => { throw new Error("bad"); }, () => "y");
  ok("a throwing validator re-picks instead of throwing", afterBadValidator === "y", afterBadValidator);

  // A corrupt file must not throw.
  fs.writeFileSync(FILE, "{{{ not json");
  ok("a corrupt file is treated as empty", mk().peek("aotd") === null);
  const afterCorrupt = mk().stable("aotd", "d3", always, () => "fresh");
  ok("and a pick can still be made and stored", afterCorrupt === "fresh" && mk().peek("aotd").value === "fresh");
}

// ---- clear ----
{
  reset();
  const s = mk();
  s.stable("aotd", "d", always, () => "a");
  s.stable("lotw", "w", always, () => "l");
  s.clear("aotd");
  ok("clear(kind) removes only that kind", s.peek("aotd") === null && s.peek("lotw").value === "l");
  s.clear();
  ok("clear() removes everything", s.peek("lotw") === null);
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log("\n" + pass + "/" + (pass + fail) + " home-pick tests passed.");
process.exit(fail ? 1 : 0);
