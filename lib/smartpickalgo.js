"use strict";

/* Smart Picks — the choosing, as pure functions over plain data.
 *
 * Six albums a day by artists NOT in the library: five "adjacent" picks drawn
 * from the neighbourhood the library already lives in, and one "stretch" pick
 * from a genre it barely touches.
 *
 * Everything here is deliberately free of network, disk and LMS, in the style
 * of lib/radio.js's radioDecision(): the interesting decisions are the ranking
 * and the exclusions, and those are worth testing without standing a server up.
 * The I/O lives in index.js.
 *
 * THE ONE IDEA THAT MAKES THIS WORK, from the Roon build and preserved
 * verbatim: similarity quality INVERTS with seed popularity. Seed from a
 * library's biggest names and you get the airport bookshop of music (Radiohead
 * → Nirvana, RHCP, Coldplay). Seed from its obscure end and you get real finds
 * (Bark Psychosis → Mogwai, Talk Talk, Tortoise, Slint, Labradford). So the
 * seeds are the library's least famous well-played artists, never its biggest,
 * and "famous" is decided by the world listen chart rather than by us.
 */

// How many library artists to ask the similarity graph about.
const SEED_COUNT = 24;
// How many ranked candidates to carry into the resolve step.
const POOL_COUNT = 150;
// A genre counts as "outside the library" at or below this share of it.
const STRETCH_SHARE = 0.02;
const MAX_STRETCH_GENRES = 3;
const MAX_STRETCH_ROSTER = 15;
// How many albums to try to resolve before giving up on the day. Each resolve
// on LMS is a multi-round-trip MENU WALK against the owner's own server, not a
// single API call as it is in the Roon build — so this is deliberately much
// lower than the Roon build's 40.
const MAX_RESOLVES = 18;
const PICK_COUNT = 5;   // adjacent picks; the stretch pick makes six

const byCanon = (a, b) => (a.canon < b.canon ? -1 : a.canon > b.canon ? 1 : 0);

/* Which library artists to seed from.
 *
 * Hubs are dropped outright. What remains is sorted by plays PER ALBUM OWNED —
 * an artist you own one record by and play constantly is a stronger signal of
 * taste than one you own twelve of and play rarely — with ties broken towards
 * the smaller collection, then by canon so the choice is deterministic.
 *
 * A library with no play history yet still has to produce something, so the
 * list is topped up by most-owned. That is a weaker signal, not a wrong one.
 */
function smartPickSeeds(profile, hubCanons, limit) {
  const cap = limit || SEED_COUNT;
  const eligible = [];
  for (const rec of profile.values()) {
    if (hubCanons.has(rec.canon)) continue;
    eligible.push(rec);
  }
  const played = eligible.filter(r => r.plays > 0).sort((a, b) =>
    (b.plays / b.albums) - (a.plays / a.albums) || a.albums - b.albums || byCanon(a, b));
  const out = played.slice(0, cap);
  if (out.length < cap) {
    const have = new Set(out.map(r => r.canon));
    const rest = eligible.filter(r => !have.has(r.canon))
      .sort((a, b) => b.albums - a.albums || byCanon(a, b));
    for (const r of rest) {
      if (out.length >= cap) break;
      out.push(r);
    }
  }
  return out;
}

// Genres the library barely touches, least-owned first.
function smartStretchGenres(weights, totalAlbums, share) {
  const total = totalAlbums || 0;
  if (!total) return [];
  const limit = share == null ? STRETCH_SHARE : share;
  const out = [];
  for (const [genre, albums] of weights) {
    if (!genre) continue;
    if ((albums / total) <= limit) out.push({ genre, albums });
  }
  out.sort((a, b) => a.albums - b.albums ||
    (a.genre < b.genre ? -1 : a.genre > b.genre ? 1 : 0));
  return out;
}

/* Fold similar-artist rows into one entry per candidate, remembering EVERY seed
 * each was reached from — that count is the distance signal, so a candidate
 * arriving twice must not overwrite itself.
 *
 * `canon` is injected (search.artistKey) rather than imported, so identity
 * folding is the same function the rest of the app compares artists with.
 */
function collectSmartCandidates(rows, seedNameByMbid, canon) {
  const byMbid = new Map();
  for (const r of rows || []) {
    if (!r || !r.mbid || !r.name) continue;
    const k = canon(r.name);
    if (!k) continue;
    let rec = byMbid.get(r.mbid);
    if (!rec) {
      rec = { mbid: r.mbid, name: r.name, canon: k, comment: r.comment || "",
              score: 0, seeds: [], seedNames: [] };
      byMbid.set(r.mbid, rec);
    }
    if (r.score > rec.score) rec.score = r.score;
    if (r.seed && rec.seeds.indexOf(r.seed) === -1) {
      rec.seeds.push(r.seed);
      const sn = seedNameByMbid && seedNameByMbid.get(r.seed);
      if (sn) rec.seedNames.push(sn);
    }
  }
  return Array.from(byMbid.values());
}

/* Rank by DISTANCE from the library rather than by similarity to it.
 *
 * Fewest connections back first. With a couple of dozen seeds most candidates
 * sit in the one-seed bucket, so score decides within it — which is why hub
 * candidates have to be filtered out before this runs, or the strongest score
 * in that bucket is simply the most famous name in it.
 */
function rankSmartCandidates(cands) {
  return (cands || []).slice().sort((a, b) =>
    a.seeds.length - b.seeds.length ||
    b.score - a.score ||
    byCanon(a, b));
}

/* Spread the day's picks across DIFFERENT corners of the library.
 *
 * Ranking alone produces a monoculture, and measurably so: on a test library
 * seeded from Bark Psychosis, Slint, Stars of the Lid, Labradford and Tortoise,
 * the top five candidates were all neighbours of Stars of the Lid — five
 * ambient records that between them said one thing. The distance sort cannot
 * prevent that, because once most candidates sit in the one-seed bucket it
 * decides on score alone and the loudest seed owns every slot.
 *
 * So the ranked list is dealt round-robin: the best candidate from each seed,
 * then each seed's second, and so on. Rank order is preserved WITHIN a seed,
 * and the seed queues are already in rank order, so the strongest candidate
 * overall still comes first — it just no longer brings four relatives with it.
 */
function diversifySmartCandidates(ranked) {
  const bySeed = new Map();
  for (const c of ranked || []) {
    // A candidate's first seed is its strongest connection: `seeds` is filled
    // in arrival order from a list the endpoint returns strongest-first.
    const key = (c.seeds && c.seeds[0]) || "";
    if (!bySeed.has(key)) bySeed.set(key, []);
    bySeed.get(key).push(c);
  }
  const queues = Array.from(bySeed.values());
  const out = [];
  for (let round = 0; ; round++) {
    let moved = false;
    for (const q of queues) {
      if (round < q.length) { out.push(q[round]); moved = true; }
    }
    if (!moved) break;   // every queue exhausted
  }
  return out;
}

// Everything a candidate must not be. One function, so the adjacent and
// stretch paths cannot drift apart on what counts as "already known".
function smartPickExcluded(canon, sets) {
  if (!canon) return true;
  if (sets.library.has(canon)) return true;   // already owned
  if (sets.hubs.has(canon)) return true;      // famous is not a discovery
  if (sets.blocked.has(canon)) return true;   // owner said "not for me"
  if (sets.seen.has(canon)) return true;      // shown recently
  return false;
}

/* The sentence under a pick. Built from the chain that actually produced it,
 * so it is always true — a generated one would read better and would sometimes
 * be wrong, and a recommendation nobody can check is a recommendation nobody
 * trusts.
 */
function smartPickReason(rec) {
  if (rec.kind === "stretch") {
    return rec.genre
      ? "Nothing like your library — a cornerstone of " + rec.genre
      : "Nothing like your library";
  }
  const names = (rec.seedNames || []).filter(Boolean);
  if (!names.length) return "Close to what you already listen to";
  if (names.length === 1) return "Because you play " + names[0];
  return "Because you play " + names[0] + " and " + names[1];
}

/* The library as {canon → {canon, name, albums, plays}}.
 *
 * Built from album CREDITS, not from the play log's artist column — that one
 * holds the TRACK artist, so a various-artists compilation would credit taste
 * to whoever happened to be on it. Plays are matched on album title, the same
 * key lib/plays.js stores, with its same documented limitation: two albums
 * sharing a title share a bucket.
 *
 *   records   the library index rows ({title, subtitle})
 *   split     search.splitArtistNames  → [{name, k}]
 *   playsOf   (title) => play count for that album
 */
function libraryArtistProfile(records, split, playsOf) {
  const out = new Map();
  for (const rec of records || []) {
    if (!rec) continue;
    const plays = playsOf ? (playsOf(rec.title) || 0) : 0;
    const seenHere = new Set();
    for (const a of split(rec.subtitle || "")) {
      const k = a && a.k;
      if (!k || seenHere.has(k)) continue;   // one album counts once per artist
      seenHere.add(k);
      let e = out.get(k);
      if (!e) { e = { canon: k, name: a.name, albums: 0, plays: 0 }; out.set(k, e); }
      e.albums++;
      e.plays += plays;
    }
  }
  return out;
}

module.exports = {
  smartPickSeeds, smartStretchGenres, collectSmartCandidates,
  rankSmartCandidates, diversifySmartCandidates, smartPickExcluded,
  smartPickReason, libraryArtistProfile,
  SEED_COUNT, POOL_COUNT, STRETCH_SHARE, MAX_STRETCH_GENRES,
  MAX_STRETCH_ROSTER, MAX_RESOLVES, PICK_COUNT,
};
