// ---------------------------------------------------------------------------
// Plays log — lightweight play-history used by the Home "not played in N
// months" row (/api/home/unplayed).
//
// The sibling Roon-based MusicD Remote app records this in a SQLite `plays`
// table (better-sqlite3, a native module). This repo deliberately has NO
// native dependencies (see Dockerfile), so instead we persist a plain JSON
// array of play rows under DATA_DIR — same pattern as index.js's
// loadSettings()/saveSettings() for lms-settings.json. We don't need the
// sibling's two-phase insert/complete scrobble-stats tracking, just one row
// per qualifying listen.
//
// Pure-ish module: takes the file path as a parameter so it's easy to point
// at a scratch file in tests instead of the real data dir.
// ---------------------------------------------------------------------------
"use strict";

const fs = require("fs");
const path = require("path");

// Cap growth since there's no DB indexing here — keep only recent/bounded
// history. Either limit alone would do; both together avoid unbounded growth
// from either "plays forever" or "a burst of many plays in a short window".
const MAX_ROWS = 5000;
const MAX_AGE_MS = 13 * 30 * 24 * 60 * 60 * 1000; // ~13 months

function makePlaysLog(file) {
  function load() {
    try {
      const rows = JSON.parse(fs.readFileSync(file, "utf8"));
      return Array.isArray(rows) ? rows : [];
    } catch (e) {
      return []; // missing/corrupt — start empty, degrade gracefully
    }
  }

  function save(rows) {
    try {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, JSON.stringify(rows));
    } catch (e) {
      // Best-effort — a failed write just loses this one play record; playback
      // and the rest of the app continue regardless.
    }
  }

  // Record one qualifying listen. `ts` defaults to now (tests may pass an
  // explicit timestamp to simulate old plays).
  function recordPlay({ album, artist, track, duration, ts }) {
    const rows = load();
    rows.push({
      ts:       ts != null ? ts : Date.now(),
      album:    album || "",
      artist:   artist || "",
      track:    track || "",
      duration: duration || 0
    });
    const cutoff  = Date.now() - MAX_AGE_MS;
    const trimmed = rows.filter(r => r.ts >= cutoff);
    const bounded = trimmed.length > MAX_ROWS ? trimmed.slice(trimmed.length - MAX_ROWS) : trimmed;
    save(bounded);
    return bounded;
  }

  // Set of album titles (lowercased, trimmed) with at least one play since
  // cutoffMs. Mirrors the sibling's getPlayedTitlesSince() — matching is by
  // title only (imprecise, but accepted/documented behaviour).
  function getPlayedTitlesSince(cutoffMs) {
    const rows = load();
    const set = new Set();
    for (const r of rows) {
      if (r && r.ts > cutoffMs && r.album) {
        const t = String(r.album).toLowerCase().trim();
        if (t) set.add(t);
      }
    }
    return set;
  }

  return { recordPlay, getPlayedTitlesSince, _load: load };
}

module.exports = { makePlaysLog };
