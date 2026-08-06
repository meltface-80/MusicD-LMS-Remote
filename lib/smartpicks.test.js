/*
 * The Smart Picks store. Everything here is about what survives a write and a
 * reload — the build algorithm is tested separately, against pure functions.
 */
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { makeSmartPicks, SEEN_TTL_MS, KEEP_DAYS } = require("./smartpicks");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}
const eq = (label, got, want) => ok(label, JSON.stringify(got) === JSON.stringify(want), { got, want });

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "sp-test-"));
const FILE = path.join(dir, "smart-picks.json");
const mk = () => makeSmartPicks({ dataDir: dir });
const reset = () => { try { fs.unlinkSync(FILE); } catch (e) {} };

const pick = (over) => Object.assign({
  kind: "adjacent", artist: "Labradford", canon: "labradford",
  album: "Mi Media Naranja", image: "https://x/1.jpg",
  reason: "Because you play Stars of the Lid", genre: "", mbid: "mb-1",
}, over || {});

// ---- an absent / corrupt file reads as empty, never throws ----
{
  reset();
  const s = mk();
  eq("a missing file reads as no picks", s.readDay("2026-08-05"), []);
  ok("...and no blocks", s.blockedSet().size === 0);
  fs.writeFileSync(FILE, "{ not json");
  const s2 = mk();
  eq("a corrupt file reads as no picks", s2.readDay("2026-08-05"), []);
  ok("...and writing over it recovers", s2.writeDay("2026-08-05", [pick()]).length === 1);
}

// ---- rank IS display order, and the stretch pick stays last ----
{
  reset();
  const s = mk();
  s.writeDay("2026-08-05", [
    pick({ artist: "A", canon: "a" }),
    pick({ artist: "B", canon: "b" }),
    pick({ kind: "stretch", artist: "Z", canon: "z", genre: "flamenco" }),
  ]);
  const rows = mk().readDay("2026-08-05");
  eq("ranks are assigned in the order written", rows.map(r => r.rank), [0, 1, 2]);
  eq("and read back in that order, stretch last", rows.map(r => r.artist), ["A", "B", "Z"]);
  // The Roon build hit a bug where ordering on kind as well floated the stretch
  // pick to the top. Ordering must be by rank ALONE.
  const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
  raw.picks["2026-08-05"].reverse();
  fs.writeFileSync(FILE, JSON.stringify(raw));
  eq("a shuffled file still reads back in rank order",
     mk().readDay("2026-08-05").map(r => r.artist), ["A", "B", "Z"]);
}

// ---- a malformed pick is dropped, not served ----
{
  reset();
  const s = mk();
  const kept = s.writeDay("2026-08-05", [
    pick(),
    pick({ kind: "wishful", artist: "Nope", canon: "nope" }),   // unknown kind
    pick({ artist: "", canon: "blank" }),                        // no artist
    pick({ artist: "No Canon", canon: "" }),                     // no canon
    null,
  ]);
  eq("only the well-formed pick survives a write", kept.map(p => p.artist), ["Labradford"]);
  ok("an unknown kind is refused rather than reaching the client",
     !mk().readDay("2026-08-05").some(p => p.kind === "wishful"));
}

// ---- writing a day marks everything in it as seen ----
{
  reset();
  const s = mk();
  s.writeDay("2026-08-05", [pick(), pick({ artist: "Bark Psychosis", canon: "barkpsychosis" })]);
  const seen = mk().seenSet();
  ok("every pick offered is remembered as seen",
     seen.has("labradford") && seen.has("barkpsychosis"), [...seen]);
  // Expiry: an old entry drops out of the set so the pool refills eventually.
  const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
  raw.seen["labradford"] = Date.now() - SEEN_TTL_MS - 1000;
  fs.writeFileSync(FILE, JSON.stringify(raw));
  const after = mk().seenSet();
  ok("a seen entry past its TTL is no longer excluded",
     !after.has("labradford") && after.has("barkpsychosis"), [...after]);
}

// ---- "Not for me" ----
{
  reset();
  const s = mk();
  s.writeDay("2026-08-04", [pick()]);
  s.writeDay("2026-08-05", [pick(), pick({ artist: "Keep Me", canon: "keepme" })]);
  ok("blocking an artist reports success", s.block("labradford", "Labradford"));
  ok("...and it is in the blocked set", mk().blockedSet().has("labradford"));
  const removed = s.deleteByCanon("labradford");
  ok("blocking removes that artist's picks from EVERY day (" + removed + " removed)", removed === 2);
  eq("today keeps the others", mk().readDay("2026-08-05").map(p => p.artist), ["Keep Me"]);
  eq("and so does yesterday", mk().readDay("2026-08-04"), []);
  eq("the block list names the artist", mk().blocks().map(b => b.name), ["Labradford"]);
  ok("a blank canon is refused", s.block("", "nothing") === false);
  ok("unblocking reports whether it was there", s.unblock("labradford") === true &&
     mk().unblock("labradford") === false);
}

// ---- attempt markers ----
{
  reset();
  const s = mk();
  ok("no marker before a build is tried", s.attempted("2026-08-05") === null);
  s.markAttempt("2026-08-05", { picks: 0, reason: "no service" });
  const a = mk().attempted("2026-08-05");
  ok("a marker records why nothing was produced", a && a.reason === "no service" && a.at > 0, a);
}

// ---- pruning keeps the file bounded ----
{
  reset();
  const s = mk();
  for (let i = 1; i <= KEEP_DAYS + 4; i++) {
    s.writeDay("2026-08-" + String(i).padStart(2, "0"), [pick({ canon: "c" + i, artist: "A" + i })]);
  }
  const days = Object.keys(JSON.parse(fs.readFileSync(FILE, "utf8")).picks);
  ok("only the most recent " + KEEP_DAYS + " days are kept (" + days.length + ")",
     days.length === KEEP_DAYS, days);
  ok("and the newest day is one of them", days.includes("2026-08-" + (KEEP_DAYS + 4)), days);
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log(fail ? `\n${fail} FAILED, ${pass} passed.` : `\n${pass}/${pass} Smart Picks store tests passed.`);
process.exit(fail ? 1 : 0);
