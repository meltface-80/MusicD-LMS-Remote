"use strict";

/* Smart Picks store — the day's picks, what has already been suggested, and
 * what the owner has told us never to suggest again.
 *
 * The Roon build keeps all of this in SQLite. This app has no native
 * dependencies (the Dockerfile forbids them), so it is plain JSON under
 * DATA_DIR in the style of lib/plays.js and lib/homepicks.js. The shapes are
 * otherwise the same, deliberately — the two builds should be arguing about
 * the same things.
 *
 *   picks    day  → [{kind, rank, mbid, artist, canon, album, image, reason,
 *                     genre, ts}]   — rank IS display order, stretch last
 *   seen     canon → ts             — suggested recently; not offered again
 *   blocks   canon → {name, ts}     — "Not for me", permanent until cleared
 *   attempts day  → {at, picks}     — a build was TRIED, so a day that
 *                                     legitimately yields nothing isn't
 *                                     rebuilt on every request
 *
 * Everything is pruned on write. A pick's `rank` is what orders the screen —
 * the Roon build has a note about a bug where sorting on `kind` as well put
 * the stretch pick first, so ordering is by rank alone here too.
 */

const fs = require("fs");
const path = require("path");

// How long a suggested artist stays out of the pool. Long enough that the
// picks don't visibly cycle, short enough that a library's whole neighbourhood
// isn't exhausted forever.
const SEEN_TTL_MS = 120 * 24 * 60 * 60 * 1000;
// Days of picks kept. Enough to answer "what was yesterday's" and no more —
// this is not a history feature.
const KEEP_DAYS = 7;
const KEEP_ATTEMPT_DAYS = 2;

const KINDS = new Set(["adjacent", "stretch"]);

function factory(opts) {
  const FILE = path.join(opts.dataDir, "smart-picks.json");

  function load() {
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      if (!j || typeof j !== "object") return blank();
      return {
        picks:    (j.picks    && typeof j.picks    === "object") ? j.picks    : {},
        seen:     (j.seen     && typeof j.seen     === "object") ? j.seen     : {},
        blocks:   (j.blocks   && typeof j.blocks   === "object") ? j.blocks   : {},
        attempts: (j.attempts && typeof j.attempts === "object") ? j.attempts : {},
      };
    } catch (e) {
      return blank();   // absent/corrupt → start fresh, never throws
    }
  }
  const blank = () => ({ picks: {}, seen: {}, blocks: {}, attempts: {} });

  // Bounded on every write, so the file can't grow without limit on a server
  // that runs for years.
  function prune(all) {
    const now = Date.now();
    const days = Object.keys(all.picks).sort().reverse();
    for (const d of days.slice(KEEP_DAYS)) delete all.picks[d];
    const att = Object.keys(all.attempts).sort().reverse();
    for (const d of att.slice(KEEP_ATTEMPT_DAYS)) delete all.attempts[d];
    for (const [canon, ts] of Object.entries(all.seen)) {
      if (!Number.isFinite(ts) || now - ts > SEEN_TTL_MS) delete all.seen[canon];
    }
    return all;
  }

  function save(all) {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(prune(all), null, 2));
      fs.renameSync(tmp, FILE);   // no half-written file on a crash
    } catch (e) {
      // Best-effort: a failed write costs a rebuild, never a crash.
    }
  }

  const str = (v) => (v == null ? "" : String(v));

  // A stored pick, normalised on the way in AND on the way out, so a
  // hand-edited or older file degrades instead of reaching the client as
  // whatever it happened to contain.
  function clean(p, i) {
    if (!p || typeof p !== "object") return null;
    const kind = KINDS.has(str(p.kind)) ? str(p.kind) : null;
    const artist = str(p.artist).trim();
    const canon = str(p.canon).trim();
    if (!kind || !artist || !canon) return null;
    const rank = Number.isFinite(Number(p.rank)) ? Number(p.rank) : i;
    return {
      kind, rank, canon, artist,
      mbid:   str(p.mbid) || null,
      album:  str(p.album) || null,
      image:  str(p.image) || null,
      reason: str(p.reason),
      genre:  str(p.genre),
      ts:     Number(p.ts) || 0,
    };
  }

  // ---- picks ----------------------------------------------------------------
  function readDay(day) {
    const all = load();
    const rows = Array.isArray(all.picks[day]) ? all.picks[day] : [];
    // Ordered by RANK alone. Sorting on kind as well would float the stretch
    // pick to the top, which is not where it belongs.
    return rows.map(clean).filter(Boolean).sort((a, b) => a.rank - b.rank);
  }

  // Replaces the day wholesale — a partial write would leave a half-built set
  // looking like a finished one.
  function writeDay(day, picks) {
    const all = load();
    const rows = (picks || []).map(clean).filter(Boolean);
    rows.forEach((p, i) => { p.rank = i; p.ts = p.ts || Date.now(); });
    all.picks[day] = rows;
    // Everything offered is remembered, so tomorrow doesn't offer it again.
    for (const p of rows) all.seen[p.canon] = Date.now();
    save(all);
    return rows;
  }

  function deleteDay(day) {
    const all = load();
    delete all.picks[day];
    delete all.attempts[day];
    save(all);
  }

  // Used by "Not for me": the card must not survive a refresh, so the artist's
  // picks go from every day, not just today's.
  function deleteByCanon(canon) {
    const all = load();
    let n = 0;
    for (const day of Object.keys(all.picks)) {
      const before = all.picks[day].length;
      all.picks[day] = all.picks[day].filter(p => p && p.canon !== canon);
      n += before - all.picks[day].length;
    }
    save(all);
    return n;
  }

  // ---- seen / blocked -------------------------------------------------------
  function seenSet() {
    const all = load(), now = Date.now(), out = new Set();
    for (const [canon, ts] of Object.entries(all.seen)) {
      if (Number.isFinite(ts) && now - ts <= SEEN_TTL_MS) out.add(canon);
    }
    return out;
  }

  function blockedSet() {
    return new Set(Object.keys(load().blocks));
  }

  function block(canon, name) {
    const c = str(canon).trim();
    if (!c) return false;
    const all = load();
    all.blocks[c] = { name: str(name).trim() || c, ts: Date.now() };
    save(all);
    return true;
  }

  function unblock(canon) {
    const all = load();
    const had = Object.prototype.hasOwnProperty.call(all.blocks, str(canon));
    delete all.blocks[str(canon)];
    save(all);
    return had;
  }

  function blocks() {
    const all = load();
    return Object.entries(all.blocks)
      .map(([canon, v]) => ({ canon, name: (v && v.name) || canon, ts: (v && v.ts) || 0 }))
      .sort((a, b) => b.ts - a.ts);
  }

  // ---- attempts -------------------------------------------------------------
  // A day with no service, or one where nothing resolved, must not re-run the
  // whole build on every page load. The marker says "we tried".
  function attempted(day) {
    const a = load().attempts[day];
    return a && typeof a === "object" ? a : null;
  }

  function markAttempt(day, info) {
    const all = load();
    all.attempts[day] = Object.assign({ at: Date.now() }, info || {});
    save(all);
  }

  return { readDay, writeDay, deleteDay, deleteByCanon,
           seenSet, blockedSet, block, unblock, blocks,
           attempted, markAttempt,
           _internal: { clean, prune, load, FILE, SEEN_TTL_MS, KEEP_DAYS } };
}

module.exports = { makeSmartPicks: factory, SEEN_TTL_MS, KEEP_DAYS };
