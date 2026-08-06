/*
 * The Smart Picks choosing logic. All pure, so this runs without a server —
 * the same reason lib/radio.js's radioDecision() is pure.
 *
 * The properties worth pinning are the ones that decide whether the feature is
 * any good: seeds come from the library's OBSCURE end, ranking is by distance
 * rather than similarity, and the day's set doesn't collapse into one seed's
 * neighbourhood.
 */
"use strict";
const A = require("./smartpickalgo");
const { artistKey, splitArtistNames } = require("./search");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}
const eq = (label, got, want) => ok(label, JSON.stringify(got) === JSON.stringify(want), { got, want });

const prof = (rows) => new Map(rows.map(r => [r.canon, r]));

// ---- seeds come from the library's obscure, well-played end ----
{
  const p = prof([
    { canon: "radiohead",    name: "Radiohead",       albums: 9, plays: 90 },
    { canon: "barkpsychosis", name: "Bark Psychosis", albums: 1, plays: 12 },
    { canon: "slint",        name: "Slint",           albums: 2, plays: 10 },
    { canon: "neverplayed",  name: "Never Played",    albums: 4, plays: 0 },
  ]);
  const seeds = A.smartPickSeeds(p, new Set(["radiohead"]), 3).map(s => s.name);
  // Bark Psychosis: 12 plays over 1 album = 12. Slint: 10 over 2 = 5.
  eq("seeds are per-album plays, obscure end first, hubs dropped",
     seeds, ["Bark Psychosis", "Slint", "Never Played"]);
  ok("a hub is never seeded from, however much it is played",
     !A.smartPickSeeds(p, new Set(["radiohead"]), 4).some(s => s.canon === "radiohead"));

  // A library with no play history at all still has to produce seeds.
  const cold = prof([
    { canon: "a", name: "A", albums: 5, plays: 0 },
    { canon: "b", name: "B", albums: 9, plays: 0 },
  ]);
  eq("with no plays yet it falls back to most-owned",
     A.smartPickSeeds(cold, new Set(), 2).map(s => s.name), ["B", "A"]);

  // Determinism: the same profile must give the same seeds, or the day's picks
  // change on every rebuild for no reason.
  const tie = prof([
    { canon: "x", name: "X", albums: 1, plays: 5 },
    { canon: "y", name: "Y", albums: 1, plays: 5 },
  ]);
  eq("a tie breaks on canon, so the choice is deterministic",
     A.smartPickSeeds(tie, new Set(), 2).map(s => s.canon), ["x", "y"]);
}

// ---- stretch genres: what the library barely touches ----
{
  const weights = new Map([["Rock", 500], ["Jazz", 400], ["Flamenco", 8], ["Gamelan", 2]]);
  eq("genres at or under 2% of the library, least-owned first",
     A.smartStretchGenres(weights, 1000).map(g => g.genre), ["Gamelan", "Flamenco"]);
  eq("an empty library yields no stretch genre", A.smartStretchGenres(weights, 0), []);
  // Exactly on the threshold counts as outside — 2% of 1000 is 20.
  eq("the share test is inclusive",
     A.smartStretchGenres(new Map([["Edge", 20], ["Bulk", 980]]), 1000).map(g => g.genre), ["Edge"]);

  // The defect: a FLAT library has nothing under the share line, and returning
  // [] there meant the sixth pick was never even attempted. The rarest genres
  // stand in — all but the single most-owned one, which IS the library.
  const flat = new Map([["Rock", 90], ["Jazz", 80], ["Soul", 70], ["Folk", 60]]);
  eq("a flat library still yields stretch genres, rarest first",
     A.smartStretchGenres(flat, 300).map(g => g.genre), ["Folk", "Soul", "Jazz"]);
  eq("the most-owned genre is never a stretch genre",
     A.smartStretchGenres(flat, 300).some(g => g.genre === "Rock"), false);
  // One genre is the whole library; there is no outside to reach for.
  eq("a single-genre library yields nothing",
     A.smartStretchGenres(new Map([["Rock", 300]]), 300), []);
  // The fallback must not fire when the share line already selected something.
  eq("the share line wins when it selects anything",
     A.smartStretchGenres(weights, 1000).length, 2);
}

// ---- library genre → MusicBrainz tag spellings ----
// The other half of the missing stretch pick: a file's GENRE tag is routinely
// a compound and MusicBrainz tags are atomic, so the literal string alone
// matched nothing and every roster came back empty.
{
  const c = (g) => A.genreTagCandidates(g);
  eq("a plain genre is asked for as itself", c("Jazz"), ["jazz"]);
  eq("a compound is tried whole, then in parts",
     c("Alternative & Punk"), ["alternative & punk", "alternative and punk", "alternative", "punk"]);
  eq("a slash compound splits too",
     c("Hip-Hop/Rap").slice(0, 4), ["hip-hop/rap", "hip-hop", "hip hop", "rap"]);
  // "drum and bass" is a real tag and "drum"/"bass" are not, so the whole-string
  // `and` form must be offered BEFORE the split parts or the roster is junk.
  eq("the & → and form comes before the split",
     c("Drum & Bass"), ["drum & bass", "drum and bass", "drum", "bass"]);
  eq("a hyphen is also tried as a space", c("Trip-Hop"), ["trip-hop", "trip hop"]);
  eq("nothing is asked for an empty genre", c("   "), []);
  // A one-character fragment is noise, not a tag.
  eq("single characters are dropped", c("R&B").indexOf("r"), -1);
  const many = c("Folk, World, & Country / Blues; Gospel");
  eq("the candidate list is budgeted", many.length <= A.MAX_TAG_CANDIDATES, true);
  eq("...and leads with the literal string", many[0], "folk, world, & country / blues; gospel");
}

// ---- candidates fold by mbid and REMEMBER every seed ----
{
  const seedNames = new Map([["s1", "Bark Psychosis"], ["s2", "Slint"]]);
  const rows = [
    { mbid: "c1", name: "Labradford", score: 0.4, seed: "s1" },
    { mbid: "c1", name: "Labradford", score: 0.9, seed: "s2" },
    { mbid: "c2", name: "Mogwai",     score: 0.8, seed: "s1" },
    { mbid: "",   name: "No Id",      score: 1.0, seed: "s1" },
  ];
  const c = A.collectSmartCandidates(rows, seedNames, artistKey);
  eq("one entry per candidate", c.map(x => x.name).sort(), ["Labradford", "Mogwai"]);
  const lab = c.find(x => x.mbid === "c1");
  eq("both seeds are remembered, not overwritten", lab.seeds, ["s1", "s2"]);
  ok("the strongest score wins", lab.score === 0.9, lab.score);
  eq("and the seed NAMES come along for the reason line", lab.seedNames, ["Bark Psychosis", "Slint"]);
  ok("a row with no mbid is dropped", !c.some(x => x.name === "No Id"));
}

// ---- ranking is by distance, not similarity ----
{
  const cands = [
    { canon: "popular", seeds: ["a", "b", "c"], score: 0.99 },
    { canon: "distant", seeds: ["a"],           score: 0.30 },
    { canon: "alsoone", seeds: ["b"],           score: 0.60 },
  ];
  eq("fewest connections back first, then score",
     A.rankSmartCandidates(cands).map(c => c.canon), ["alsoone", "distant", "popular"]);
  ok("the input is not mutated", cands[0].canon === "popular");
}

// ---- diversification stops one seed owning the whole day ----
{
  // Five candidates, four of them from the same seed — the exact monoculture
  // the round-robin exists to break up.
  const ranked = [
    { canon: "sotl1", seeds: ["sotl"] },
    { canon: "sotl2", seeds: ["sotl"] },
    { canon: "sotl3", seeds: ["sotl"] },
    { canon: "slint1", seeds: ["slint"] },
    { canon: "bark1",  seeds: ["bark"] },
  ];
  const out = A.diversifySmartCandidates(ranked).map(c => c.canon);
  eq("the ranked list is dealt round-robin by first seed",
     out, ["sotl1", "slint1", "bark1", "sotl2", "sotl3"]);
  ok("nothing is lost or duplicated", out.length === ranked.length && new Set(out).size === out.length);
  ok("the strongest candidate overall still leads", out[0] === "sotl1");
  // The failure it prevents, stated as the assertion: the first three picks
  // must not all come from one corner of the library.
  ok("the first three picks come from three different seeds",
     new Set(out.slice(0, 3).map(c => ranked.find(r => r.canon === c).seeds[0])).size === 3);
}

// ---- exclusions ----
{
  const sets = { library: new Set(["owned"]), hubs: new Set(["famous"]),
                 blocked: new Set(["nope"]), seen: new Set(["yesterday"]) };
  ok("an album you already own is excluded", A.smartPickExcluded("owned", sets));
  ok("a world-famous artist is not a discovery", A.smartPickExcluded("famous", sets));
  ok("a blocked artist stays blocked", A.smartPickExcluded("nope", sets));
  ok("one shown recently is held back", A.smartPickExcluded("yesterday", sets));
  ok("an empty canon is never offered", A.smartPickExcluded("", sets));
  ok("anything else is allowed", A.smartPickExcluded("labradford", sets) === false);
}

// ---- reasons are true by construction ----
{
  eq("one seed names it",
     A.smartPickReason({ kind: "adjacent", seedNames: ["Stars of the Lid"] }),
     "Because you play Stars of the Lid");
  eq("two seeds name both",
     A.smartPickReason({ kind: "adjacent", seedNames: ["Slint", "Tortoise", "Mogwai"] }),
     "Because you play Slint and Tortoise");
  eq("no seed name still says something honest",
     A.smartPickReason({ kind: "adjacent", seedNames: [] }),
     "Close to what you already listen to");
  eq("a stretch pick names its genre",
     A.smartPickReason({ kind: "stretch", genre: "flamenco" }),
     "Nothing like your library — a cornerstone of flamenco");
  eq("a stretch pick with no genre doesn't invent one",
     A.smartPickReason({ kind: "stretch", genre: "" }), "Nothing like your library");
}

// ---- the library profile comes from album CREDITS ----
{
  const records = [
    { title: "Spirit of Eden", subtitle: "Talk Talk" },
    { title: "Laughing Stock", subtitle: "Talk Talk" },
    { title: "Hex",            subtitle: "Bark Psychosis" },
    { title: "Split",          subtitle: "Slint & Tortoise" },
  ];
  const plays = new Map([["spirit of eden", 20], ["hex", 3]]);
  const p = A.libraryArtistProfile(records, splitArtistNames,
    (t) => plays.get(String(t).toLowerCase().trim()) || 0);
  ok("every credited artist gets an entry", p.has(artistKey("Talk Talk")) &&
     p.has(artistKey("Slint")) && p.has(artistKey("Tortoise")), [...p.keys()]);
  const tt = p.get(artistKey("Talk Talk"));
  ok("albums are counted per artist", tt.albums === 2, tt);
  ok("plays are summed from the albums they are credited on", tt.plays === 20, tt);
  // A collaboration credits BOTH artists with the album — that is the point of
  // splitting the credit rather than keying on the raw string.
  ok("a collaboration credits each artist once",
     p.get(artistKey("Slint")).albums === 1 && p.get(artistKey("Tortoise")).albums === 1);

  // An artist credited twice on one album (main + featured) must not be
  // counted twice for it.
  const dup = A.libraryArtistProfile(
    [{ title: "X", subtitle: "Portishead feat. Portishead" }], splitArtistNames, () => 0);
  ok("an artist credited twice on one album counts once",
     dup.get(artistKey("Portishead")).albums === 1, [...dup.values()]);
}

console.log(fail ? `\n${fail} FAILED, ${pass} passed.` : `\n${pass}/${pass} Smart Picks algorithm tests passed.`);
process.exit(fail ? 1 : 0);
