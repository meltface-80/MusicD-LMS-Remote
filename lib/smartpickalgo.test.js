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

// ---- the stretch pick: two hops out in the taste graph ----
// It used to be chosen by GENRE, and on a library whose files carry no genre
// tag at all that could never work. These pin the replacement.
{
  // near = the seeds, everything one hop from a seed, and everything owned.
  const near = new Set(["barkpsychosis", "slint", "labradford", "mogwai", "ownedband"]);
  const viaNames = new Map([["lab", "Labradford"], ["mog", "Mogwai"]]);
  const rows = [
    // Two different near-neighbours point at Fennesz — a real cluster.
    { mbid: "fen", name: "Fennesz",   score: 0.60, seed: "lab" },
    { mbid: "fen", name: "Fennesz",   score: 0.80, seed: "mog" },
    // One referrer only.
    { mbid: "tim", name: "Tim Hecker", score: 0.90, seed: "lab" },
    // Already one hop from the library — NOT two steps out.
    { mbid: "mg",  name: "Mogwai",    score: 0.99, seed: "lab" },
    // Owned outright.
    { mbid: "ob",  name: "Owned Band", score: 0.95, seed: "mog" },
    { mbid: "",    name: "No Id",     score: 1.00, seed: "lab" },
  ];
  const c = A.collectStretchCandidates(rows, viaNames, near, artistKey);
  eq("anything the library already reaches is not two steps out",
     c.map(x => x.name).sort(), ["Fennesz", "Tim Hecker"]);
  ok("a row with no mbid is dropped", !c.some(x => x.name === "No Id"));
  const fen = c.find(x => x.mbid === "fen");
  eq("every near-neighbour that pointed at it is remembered", fen.vias, ["lab", "mog"]);
  eq("...by name too, for the reason line", fen.viaNames, ["Labradford", "Mogwai"]);
  ok("the strongest score wins", fen.score === 0.8, fen.score);

  // The ranking is the OPPOSITE of the adjacent picks': everything here is
  // already two hops out, so the question is "is this real", not "how far".
  const ranked = A.rankStretchCandidates(c);
  eq("most referrers first — a cluster beats a tail entry",
     ranked.map(x => x.name), ["Fennesz", "Tim Hecker"]);
  ok("the input is not mutated", c[0].name === "Fennesz" || c[0].name === "Tim Hecker");
  // No minimum referrer count: every hard filter on this path has turned into
  // a new way for the stretch to vanish.
  const lone = A.rankStretchCandidates(
    A.collectStretchCandidates([{ mbid: "x", name: "Lone", score: 0.1, seed: "lab" }],
                               viaNames, near, artistKey));
  eq("a single referrer is still offered rather than dropped", lone.map(x => x.name), ["Lone"]);
  eq("nothing to step beyond yields nothing",
     A.collectStretchCandidates([], viaNames, near, artistKey), []);
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
  eq("a stretch pick names the neighbour it was reached through",
     A.smartPickReason({ kind: "stretch", genre: "", viaNames: ["Labradford"] }),
     "Two steps from your library, by way of Labradford");
  eq("with no via it still says something true",
     A.smartPickReason({ kind: "stretch", genre: "", viaNames: [] }),
     "Two steps from your library — nothing you own is close to this");
  // A reason is written once, at build time. A card stored under the old genre
  // rule must keep describing itself by that rule, not the new one.
  eq("a pick stored under the old genre rule keeps its wording",
     A.smartPickReason({ kind: "stretch", genre: "flamenco" }),
     "Nothing like your library — a cornerstone of flamenco");
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
