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
// How many near-neighbours to step beyond when looking for the stretch pick.
// Each batch of 10 is one ListenBrainz request, so 20 is two.
const STRETCH_HOP1 = 20;
// How many hop-2 artists to try to resolve before giving up on the stretch.
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

/* The STRETCH pick: two hops out in the taste graph.
 *
 * It used to be chosen by GENRE — a genre the library barely touched — and on
 * the owner's own library that could never work: the album genre comes from
 * the file's GENRE tag, and a library can carry none at all. No threshold
 * fixes an input that isn't there, and the tag vocabulary MusicBrainz rosters
 * use is a different one again. So the stretch is now measured in the same
 * graph the adjacent picks come from, which needs no metadata beyond what
 * already produces those picks.
 *
 * Hop 1 is everything similar to a seed — that is where the five adjacent
 * picks come from. Hop 2 is what those near-neighbours are similar to. A
 * candidate qualifies only if NOTHING near the library reaches it directly:
 * not a seed, not a hop-1 artist, not anything owned. That is the operational
 * meaning of "nothing you own is close to this".
 *
 *   nearCanons   artist keys for seeds + hop-1 + the library — the "close" set
 *   viaNameByMbid  hop-1 mbid → display name, for the reason line
 */
function collectStretchCandidates(rows, viaNameByMbid, nearCanons, canon) {
  const byMbid = new Map();
  for (const r of rows || []) {
    if (!r || !r.mbid || !r.name) continue;
    const k = canon(r.name);
    if (!k || nearCanons.has(k)) continue;   // one hop from the library, not two
    let rec = byMbid.get(r.mbid);
    if (!rec) {
      rec = { mbid: r.mbid, name: r.name, canon: k, comment: r.comment || "",
              score: 0, vias: [], viaNames: [] };
      byMbid.set(r.mbid, rec);
    }
    if (r.score > rec.score) rec.score = r.score;
    if (r.seed && rec.vias.indexOf(r.seed) === -1) {
      rec.vias.push(r.seed);
      const vn = viaNameByMbid && viaNameByMbid.get(r.seed);
      if (vn) rec.viaNames.push(vn);
    }
  }
  return Array.from(byMbid.values());
}

/* Rank the hop-2 field: MOST referrers first.
 *
 * The opposite of rankSmartCandidates, and deliberately so. There, distance
 * from the library is the whole point and everything is one hop away, so fewer
 * connections means further out. Here EVERY candidate is already two hops out
 * by construction, so the question is no longer "how far" but "is this real":
 * an artist several of your near-neighbours point at is a genuine cluster just
 * beyond your library, where one with a single referrer is as likely to be a
 * tail entry nobody would call a discovery.
 *
 * There is deliberately NO minimum referrer count. Every hard filter added to
 * this path so far has turned into a new way for the stretch to vanish
 * silently; a sort expresses the same preference and cannot empty the field.
 */
function rankStretchCandidates(cands) {
  return (cands || []).slice().sort((a, b) =>
    b.vias.length - a.vias.length ||
    b.score - a.score ||
    byCanon(a, b));
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
    // The genre wording survives for picks STORED before the stretch moved to
    // the taste graph — a reason is written once, at build time, and a card
    // from last week must not start describing itself by the new rule.
    if (rec.genre) return "Nothing like your library — a cornerstone of " + rec.genre;
    const via = (rec.viaNames || []).filter(Boolean);
    if (via.length) return "Two steps from your library, by way of " + via[0];
    return "Two steps from your library — nothing you own is close to this";
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
  smartPickSeeds, collectSmartCandidates,
  rankSmartCandidates, diversifySmartCandidates, smartPickExcluded,
  collectStretchCandidates, rankStretchCandidates,
  smartPickReason, libraryArtistProfile,
  SEED_COUNT, POOL_COUNT, STRETCH_HOP1,
  MAX_STRETCH_ROSTER, MAX_RESOLVES, PICK_COUNT,
};
