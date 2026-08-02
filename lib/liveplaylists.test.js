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
{
  const s = mk();
  eq("defaults fill an empty view",
    s.sanitizeView({}),
    { sort: "album", dir: "asc", seed: 1, decade: [], source: [], genre: [], played: "any" });

  eq("a valid view round-trips",
    s.sanitizeView({ sort: "genre", dir: "desc", seed: 42, decade: ["1990"], source: ["qobuz"], genre: ["Jazz"], played: "6" }),
    { sort: "genre", dir: "desc", seed: 42, decade: ["1990"], source: ["qobuz"], genre: ["Jazz"], played: "6" });

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

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log("\n" + pass + "/" + (pass + fail) + " live-playlist tests passed.");
process.exit(fail ? 1 : 0);
