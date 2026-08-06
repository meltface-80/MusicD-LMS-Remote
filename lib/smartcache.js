"use strict";

/* TTL cache for the Smart Picks discovery lookups.
 *
 * The daily build asks ListenBrainz for the world chart, then for the
 * neighbours of ~24 seed artists, then MusicBrainz for a genre roster, then
 * the LMS Qobuz plugin to resolve each candidate to a real album. Without a
 * cache that is the whole set of requests every single day, against services
 * that are free and ask politely not to be hammered.
 *
 * Namespaces and TTLs match the Roon build's SQLite table exactly, so the two
 * implementations age the same data out at the same time:
 *
 *   hubs            the world's top artists                     14 days
 *   sim:<mbid>      one seed's similar artists                  30 days
 *   tag:<genre>     a genre's roster                            30 days
 *   alb:<canon>     an artist's resolvable album, or a MISS      7 days
 *
 * MISSES ARE CACHED for `alb:` on purpose: "this artist has nothing findable"
 * is the expensive answer, and re-asking it daily is the thing most likely to
 * make a build slow.
 *
 * The `sim:` namespace is capped and evicted oldest-first — 24 seeds a day for
 * a month is several hundred entries, and this is a JSON file, not a database.
 */

const fs = require("fs");
const path = require("path");

const DAY = 24 * 60 * 60 * 1000;
const TTL = { hubs: 14 * DAY, sim: 30 * DAY, tag: 30 * DAY, alb: 7 * DAY, built: 1 * DAY };
const SIM_MAX = 400;

// Writes are debounced: a build writes ~30 entries in a burst, and rewriting
// the whole file each time is pure waste.
const SAVE_DEBOUNCE_MS = 400;

function factory(opts) {
  const FILE = path.join(opts.dataDir, "cache", "smart-cache.json");
  let mem = null;
  let saveTimer = null;

  function load() {
    if (mem) return mem;
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      mem = (j && typeof j === "object") ? j : {};
    } catch (e) { mem = {}; }
    return mem;
  }

  const ns = (key) => String(key).split(":")[0];
  const ttlFor = (key) => TTL[ns(key)] || DAY;

  function flush() {
    saveTimer = null;
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(mem || {}));
      fs.renameSync(tmp, FILE);
    } catch (e) {
      // Best-effort: a failed write costs a re-fetch, never a crash.
    }
  }
  function scheduleSave() {
    if (saveTimer) return;
    saveTimer = setTimeout(flush, SAVE_DEBOUNCE_MS);
    if (saveTimer.unref) saveTimer.unref();   // never hold the process open
  }

  // → the stored value, or undefined when absent or stale. `undefined` and a
  // stored `null` are different answers: null is a cached MISS.
  function get(key) {
    const all = load();
    const row = all[key];
    if (!row || typeof row !== "object") return undefined;
    if (!Number.isFinite(row.ts) || Date.now() - row.ts > ttlFor(key)) return undefined;
    return row.body;
  }

  function set(key, body) {
    const all = load();
    all[key] = { ts: Date.now(), body };
    if (ns(key) === "sim") evictSim(all);
    scheduleSave();
    return body;
  }

  // Oldest-first, so the seeds still in rotation survive.
  function evictSim(all) {
    const keys = Object.keys(all).filter(k => ns(k) === "sim");
    if (keys.length <= SIM_MAX) return;
    keys.sort((a, b) => (all[a].ts || 0) - (all[b].ts || 0));
    for (const k of keys.slice(0, keys.length - SIM_MAX)) delete all[k];
  }

  // Drop everything past its own namespace's TTL. Run once per build.
  function prune() {
    const all = load(), now = Date.now();
    let n = 0;
    for (const [k, row] of Object.entries(all)) {
      if (!row || !Number.isFinite(row.ts) || now - row.ts > ttlFor(k)) { delete all[k]; n++; }
    }
    if (n) scheduleSave();
    return n;
  }

  function clear() {
    mem = {};
    scheduleSave();
  }

  return { get, set, prune, clear, _internal: { FILE, TTL, SIM_MAX, flush } };
}

module.exports = { makeSmartCache: factory, TTL, SIM_MAX };
