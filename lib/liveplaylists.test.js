"use strict";

/* Live Playlists store — the sanitiser is the load-bearing part: a saved rule
 * is re-validated on EVERY read, so a hand-edited file, or a view written by
 * an older build, degrades to something sane instead of producing a query the
 * Library can't answer. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const makeLivePlaylists = require("./liveplaylists");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}
const eq = (label, got, want) => ok(label, JSON.stringify(got) === JSON.stringify(want), { got, want });

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lp-test-"));
const SORTS = ["album", "artist", "year", "genre", "plays", "lastplayed", "random"];
const PLAYEDS = ["any", "never", "6", "12"];
const mk = () => makeLivePlaylists({ dataDir: dir, sorts: SORTS, playeds: PLAYEDS });
const FILE = path.join(dir, "live-playlists.json");
const reset = () => { try { fs.unlinkSync(FILE); } catch (e) {} };

// ---- sanitizeView ----
// The facet list is read back off sanitizeView itself rather than restated
// here: a hardcoded copy meant that adding a facet to the engine failed three
// assertions that had nothing to say about the new facet.
const FACETS = Object.keys(makeLivePlaylists({ dataDir: dir, sorts: SORTS, playeds: PLAYEDS })
  .sanitizeView({})).filter(k => !["sort", "dir", "seed", "played"].includes(k));
const view = (head, facets) => {
  const o = { ...head };
  for (const id of FACETS) o[id] = (facets && facets[id]) || [];
  return o;
};
{
  const s = mk();
  // Every facet the Library engine offers is present, empty, by default.
  ok("every Library facet is present and empty by default",
    FACETS.length >= 6 && FACETS.every(id => Array.isArray(s.sanitizeView({})[id]) && !s.sanitizeView({})[id].length),
    FACETS);
  eq("defaults fill an empty view",
    s.sanitizeView({}),
    view({ sort: "album", dir: "asc", seed: 1, played: "any" }));

  eq("a valid view round-trips",
    s.sanitizeView({ sort: "genre", dir: "desc", seed: 42, decade: ["1990"], source: ["qobuz"], genre: ["Jazz"], played: "6" }),
    view({ sort: "genre", dir: "desc", seed: 42, played: "6" },
         { genre: ["Jazz"], source: ["qobuz"], decade: ["1990"] }));

  // EXCLUSION round-trips. The decade validator used to parseInt the whole
  // string, which turned "!1990" into NaN and silently dropped the rule.
  eq("an excluded decade survives sanitising",
    s.sanitizeView({ decade: ["!1990", "2000"] }).decade, ["!1990", "2000"]);
  eq("an excluded genre survives sanitising",
    s.sanitizeView({ genre: ["!Pop"] }).genre, ["!Pop"]);
  eq("a bad decade is still dropped, prefix or not",
    s.sanitizeView({ decade: ["!1995", "banana", "!1980"] }).decade, ["!1980"]);
  eq("the new facets are carried",
    s.sanitizeView({ label: ["!Blue Note"], letter: ["B"], added: ["30"] }),
    view({ sort: "album", dir: "asc", seed: 1, played: "any" },
         { label: ["!Blue Note"], letter: ["B"], added: ["30"] }));
  // Format / Sample rate / Bit depth ride in on the added-time sweep, so a
  // saved playlist must carry them like any other facet.
  eq("the format facets are carried",
    s.sanitizeView({ format: ["flac", "!mp3"], rate: ["96000"], bits: ["24"] }),
    view({ sort: "album", dir: "asc", seed: 1, played: "any" },
         { format: ["flac", "!mp3"], rate: ["96000"], bits: ["24"] }));

  ok("an unknown sort falls back to album", s.sanitizeView({ sort: "by_vibes" }).sort === "album");
  ok("an unknown played falls back to any", s.sanitizeView({ played: "17" }).played === "any");
  ok("dir accepts only desc", s.sanitizeView({ dir: "DESC" }).dir === "asc");
  ok("a zero/negative seed becomes 1", s.sanitizeView({ seed: 0 }).seed === 1 && s.sanitizeView({ seed: -3 }).seed === 1);

  // Decades are decade-START years. A non-multiple of ten would match nothing
  // at query time, so it is dropped rather than silently returning empty.
  eq("non-decade years are dropped", s.sanitizeView({ decade: ["1995", "1990", "abc", "12"] }).decade, ["1990"]);
  eq("duplicate facet values collapse", s.sanitizeView({ genre: ["Jazz", "Jazz", "Rock"] }).genre, ["Jazz", "Rock"]);
  eq("a non-array facet becomes empty", s.sanitizeView({ source: "qobuz" }).source, []);
}

// ---- put / list / get / remove ----
{
  reset();
  const s = mk();
  eq("a fresh store is empty", s.list(), []);

  const a = s.put({ name: "  80s Jazz  ", view: { sort: "year", dir: "desc", genre: ["Jazz"] } });
  ok("put returns a record with an id", !!a && /^lp_/.test(a.id), a);
  ok("the name is trimmed", a.name === "80s Jazz", a.name);
  ok("the view is sanitised on write", a.view.sort === "year" && a.view.played === "any", a.view);

  const b = s.put({ name: "Never played" });
  ok("a second playlist coexists", s.list().length === 2);
  ok("ids are distinct", a.id !== b.id);

  // Update in place by id — this is what lets a rename avoid forking a copy.
  const renamed = s.put({ id: a.id, name: "Eighties Jazz", view: a.view });
  ok("updating by id renames rather than duplicating", s.list().length === 2 && renamed.id === a.id);
  ok("the rename is what is stored", s.get(a.id).name === "Eighties Jazz", s.get(a.id));

  ok("get returns null for an unknown id", s.get("lp_nope") === null);
  ok("remove reports success", s.remove(b.id) === true);
  ok("remove reports failure for an unknown id", s.remove("lp_nope") === false);
  ok("the removed playlist is gone", s.list().length === 1 && !s.get(b.id));

  ok("an unnamed playlist is rejected", s.put({ name: "   " }) === null);
}

// ---- save-over by NAME (no id) must not fork a twin ----
{
  reset();
  const s = mk();
  const first = s.put({ name: "Sunday Jazz", view: { sort: "album" } });
  // Saving again under the same name, with NO id, is a save-over: the picker
  // is a flat list and two identical rows would be indistinguishable.
  const again = s.put({ name: "Sunday Jazz", view: { sort: "year", dir: "desc" } });
  ok("re-saving a name does not create a duplicate", s.list().length === 1, s.list().map(r => r.name));
  ok("the save-over reuses the original id", again.id === first.id, { was: first.id, now: again.id });
  ok("the new rules replaced the old ones", s.get(first.id).view.sort === "year", s.get(first.id).view);

  // Case shouldn't matter — "sunday jazz" is the same row to a reader.
  s.put({ name: "SUNDAY JAZZ", view: { sort: "artist" } });
  ok("the name match is case-insensitive", s.list().length === 1, s.list().map(r => r.name));

  // A different name is still a genuinely new playlist.
  s.put({ name: "Monday Jazz" });
  ok("a different name still creates a new playlist", s.list().length === 2);
}

// ---- persistence + sanitise-on-READ ----
{
  reset();
  const s = mk();
  s.put({ name: "Keep", view: { sort: "artist" } });
  ok("a new store instance sees the saved playlist", mk().list().length === 1);

  // Hand-edit the file the way a person (or an older build) might.
  const rows = JSON.parse(fs.readFileSync(FILE, "utf8"));
  rows.push({ id: "lp_bad", name: "Corrupt", view: { sort: "removed_sort", played: "999", decade: ["1995"] } });
  rows.push({ id: "lp_noname", name: "", view: {} });
  rows.push("not an object");
  fs.writeFileSync(FILE, JSON.stringify(rows));

  const after = mk().list();
  ok("junk rows are dropped on load", after.length === 2, after.map(r => r.name));
  const bad = after.find(r => r.id === "lp_bad");
  ok("a stale sort is repaired on READ, not just on write", bad && bad.view.sort === "album", bad && bad.view);
  ok("a stale played value is repaired on read", bad && bad.view.played === "any", bad && bad.view);
  eq("an invalid decade is dropped on read", bad ? bad.view.decade : null, []);

  // A file that isn't JSON at all must not throw.
  fs.writeFileSync(FILE, "{{{ not json");
  eq("an unreadable file yields an empty list", mk().list(), []);
}

// ---- cap ----
{
  reset();
  const s = mk();
  for (let i = 0; i < s.MAX; i++) s.put({ name: "PL " + i });
  ok("the store fills to MAX", s.list().length === s.MAX);
  ok("put past MAX is refused rather than evicting", s.put({ name: "one too many" }) === null);
  ok("still exactly MAX after a refused put", s.list().length === s.MAX);
  // Updating an existing one must still work at the cap.
  const first = s.list()[0];
  ok("an update still works at the cap", !!s.put({ id: first.id, name: "renamed at cap" }));
}

// ---- size cap and order (the playlist's own properties, not its rules) ----
{
  reset();
  const s = mk();

  // Absent on everything saved before these shipped — the safe direction is the
  // DEFAULT, never "unlimited".
  const legacy = s.put({ name: "no limit field" });
  eq("a playlist saved without a limit takes the default", legacy.limit, 100);
  eq("...and album order", legacy.order, "album");

  eq("a chosen limit is kept", s.put({ name: "small", limit: 25 }).limit, 25);
  eq("a limit above the ceiling is clamped", s.put({ name: "huge", limit: 9999 }).limit, 400);
  // Zero must not read as "no limit" — that is the whole point of the field.
  eq("zero falls back to the default, not unlimited", s.put({ name: "zero", limit: 0 }).limit, 100);
  eq("garbage falls back to the default", s.put({ name: "junk", limit: "lots" }).limit, 100);

  eq("random order is kept", s.put({ name: "shuffled", order: "random" }).order, "random");
  // The endpoints branch on this string; an unrecognised value passed through
  // would take whichever branch its comparison happened to miss.
  eq("an unknown order falls back to album", s.put({ name: "weird", order: "sideways" }).order, "album");

  // Normalised by the same function on the way back IN, not just on the way out.
  fs.writeFileSync(FILE, JSON.stringify([
    { id: "lp_x", name: "hand edited", limit: -5, order: "nonsense", view: {} }
  ]));
  const back = mk().list()[0];
  eq("a hand-edited limit is repaired on READ", back.limit, 100);
  eq("a hand-edited order is repaired on READ", back.order, "album");

  // They live BESIDE the view, never inside it: two playlists can share a rule
  // set and differ here, which is why the server slices rather than folding
  // them into the query.
  ok("limit and order stay out of the saved view",
     back.view.limit === undefined && back.view.order === undefined, back.view);
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log("\n" + pass + "/" + (pass + fail) + " live-playlist tests passed.");
process.exit(fail ? 1 : 0);
