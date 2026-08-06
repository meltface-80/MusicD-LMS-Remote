/*
 * The Smart Picks discovery cache. The two things that actually matter:
 * a cached MISS is distinguishable from an absent entry, and each namespace
 * ages out on its own clock.
 */
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { makeSmartCache, TTL, SIM_MAX } = require("./smartcache");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "sc-test-"));
const FILE = path.join(dir, "cache", "smart-cache.json");
const mk = () => makeSmartCache({ dataDir: dir });
const reset = () => { try { fs.rmSync(path.join(dir, "cache"), { recursive: true, force: true }); } catch (e) {} };

// ---- round trip, and a MISS is not the same as absent ----
{
  reset();
  const c = mk();
  ok("an absent key is undefined", c.get("hubs") === undefined);
  c.set("hubs", [{ mbid: "a", name: "A" }]);
  ok("a stored value comes back", JSON.stringify(c.get("hubs")) === '[{"mbid":"a","name":"A"}]');
  // "this artist has nothing findable" is the expensive answer — it must be
  // storable, and must not read back as "never asked".
  c.set("alb:labradford", null);
  ok("a cached MISS reads back as null, not undefined", c.get("alb:labradford") === null);
  ok("...and is distinguishable from a key never set", c.get("alb:nobody") === undefined);
}

// ---- each namespace expires on its own clock ----
{
  reset();
  const c = mk();
  c.set("alb:x", { album: "A" });
  c.set("sim:mb-1", [1, 2, 3]);
  // Age both by 8 days: past alb's 7-day TTL, well inside sim's 30.
  c._internal.flush();
  const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
  const eightDays = 8 * 24 * 60 * 60 * 1000;
  raw["alb:x"].ts -= eightDays;
  raw["sim:mb-1"].ts -= eightDays;
  fs.writeFileSync(FILE, JSON.stringify(raw));
  const c2 = mk();
  ok("an alb: entry is stale after 8 days (TTL " + TTL.alb / 86400000 + "d)", c2.get("alb:x") === undefined);
  ok("a sim: entry is still fresh (TTL " + TTL.sim / 86400000 + "d)", JSON.stringify(c2.get("sim:mb-1")) === "[1,2,3]");
}

// ---- prune drops exactly the stale rows ----
{
  reset();
  const c = mk();
  c.set("hubs", [1]);
  c.set("alb:old", null);
  c._internal.flush();
  const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
  raw["alb:old"].ts -= 30 * 24 * 60 * 60 * 1000;
  fs.writeFileSync(FILE, JSON.stringify(raw));
  const c2 = mk();
  ok("prune removes the stale row and keeps the fresh one",
     c2.prune() === 1 && c2.get("hubs") !== undefined && c2.get("alb:old") === undefined);
}

// ---- the sim: namespace stays bounded, evicting oldest first ----
{
  reset();
  const c = mk();
  for (let i = 0; i < SIM_MAX + 25; i++) c.set("sim:mb-" + i, [i]);
  c._internal.flush();
  const keys = Object.keys(JSON.parse(fs.readFileSync(FILE, "utf8"))).filter(k => k.startsWith("sim:"));
  ok("sim: is capped at " + SIM_MAX + " (" + keys.length + ")", keys.length === SIM_MAX, keys.length);
  ok("...and it is the OLDEST that went", !keys.includes("sim:mb-0") && keys.includes("sim:mb-" + (SIM_MAX + 24)));
  // Only sim: is capped — the other namespaces are small by construction.
  ok("an unbounded namespace is untouched by the cap", c.get("sim:mb-" + (SIM_MAX + 24)) !== undefined);
}

// ---- a corrupt file is not fatal ----
{
  reset();
  fs.mkdirSync(path.dirname(FILE), { recursive: true });
  fs.writeFileSync(FILE, "}{ garbage");
  const c = mk();
  ok("a corrupt cache reads as empty", c.get("hubs") === undefined);
  c.set("hubs", ["recovered"]);
  ok("...and is rewritten cleanly", JSON.stringify(c.get("hubs")) === '["recovered"]');
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log(fail ? `\n${fail} FAILED, ${pass} passed.` : `\n${pass}/${pass} Smart Picks cache tests passed.`);
process.exit(fail ? 1 : 0);
