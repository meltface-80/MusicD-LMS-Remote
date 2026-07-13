// ---------------------------------------------------------------------------
// MusicD LMS Remote — server entry point.
//
// This is the Lyrion Music Server (LMS) port of MusicD Remote. It serves the
// SAME PWA frontend and preserves the SAME /api/* contract as the Roon build,
// swapping the Roon integration for the LMS JSON-RPC adapter (lib/lms.js).
//
// Design: the frontend talks only to our /api/* routes and never knew it was
// Roon, so as long as each route returns the identical JSON shape, the whole
// UI (grid, search, album view, transport, wall display) works unchanged.
//
// PHASE 1 (this file): connection + discovery, the in-memory album search
// index, artwork proxy, and the core library + playback/transport routes.
// Advanced routes (labels pipeline, Home rows, Qobuz/Tidal, Pitchfork, wall
// display, self-update) are stubbed with safe empty responses so the UI
// degrades gracefully rather than erroring; they are ported in later phases.
// Each stub is tagged `// PHASE 2`.
// ---------------------------------------------------------------------------
"use strict";

const path = require("path");
const fs = require("fs");
const http = require("http");
const express = require("express");
const compression = require("compression");

const { createLms, discover } = require("./lib/lms");
const search = require("./lib/search");
const { makePlaysLog } = require("./lib/plays");

const pkg = require("./package.json");
const DEBUG = process.env.DEBUG === "1";
const PORT = Number(process.env.PORT) || 3390;

// ---------------------------------------------------------------------------
// Persisted settings (LMS connection + app-local prefs) on the data volume.
// ---------------------------------------------------------------------------
const DATA_DIR = path.join(__dirname, "data");
const SETTINGS_FILE = path.join(DATA_DIR, "lms-settings.json");

function loadSettings() {
  try { return JSON.parse(fs.readFileSync(SETTINGS_FILE, "utf8")) || {}; }
  catch (e) { return {}; }   // missing/corrupt — start with defaults
}
function saveSettings(patch) {
  const cur = loadSettings();
  const next = { ...cur, ...patch };
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(SETTINGS_FILE, JSON.stringify(next, null, 2));
  } catch (e) { if (DEBUG) console.error("[settings] save failed:", e.message); }
  return next;
}

// ---------------------------------------------------------------------------
// Plays log — feeds /api/home/unplayed ("albums not played in N months").
// See lib/plays.js for why this is a JSON file rather than the sibling Roon
// app's SQLite table (no native deps in this repo).
// ---------------------------------------------------------------------------
const playsLog = makePlaysLog(path.join(DATA_DIR, "plays.json"));

// Per-player "what's currently playing, and did it already qualify as a play"
// state, keyed by player id. Mirrors the sibling's scrobbleUpdate(), but
// simpler: LMS's `time` is already true elapsed playback position (unlike
// Roon, no seek-position-delta accumulation is needed), and we only need a
// single qualifying-play record, not the sibling's two-phase insert/complete
// scrobble-stats tracking.
const scrobbleState = new Map(); // playerId -> { key, recorded, track, artist, album, duration }

function scrobbleTrackKey(t) {
  return (t.title || "") + "|" + (t.artist || "") + "|" + (t.album || "");
}

// Called on every status poll (~2.5s) for every player. Records exactly one
// play per track-listen, once it crosses the qualifying threshold: elapsed
// >= 30s AND (elapsed >= 50% of duration OR elapsed >= 240s) — same threshold
// the sibling app uses for its scrobble-stats feature.
function scrobbleUpdate(playerId, st) {
  const t = st && st.track;
  if (!st || !st.playing || !t || !t.title) {
    scrobbleState.delete(playerId); // stopped/paused/idle — nothing to track
    return;
  }
  const key = scrobbleTrackKey(t);
  let prev = scrobbleState.get(playerId);
  if (!prev || prev.key !== key) {
    // New track (or first sighting) — start tracking it fresh.
    prev = { key, recorded: false, track: t.title, artist: t.artist, album: t.album, duration: st.duration || t.duration || 0 };
    scrobbleState.set(playerId, prev);
  }
  if (prev.recorded) return; // already logged this listen
  const elapsed  = st.time || 0;
  const duration = st.duration || prev.duration || 0;
  if (elapsed >= 30 && (elapsed >= duration * 0.5 || elapsed >= 240)) {
    prev.recorded = true;
    playsLog.recordPlay({ album: prev.album, artist: prev.artist, track: prev.track, duration });
  }
}

// Qobuz (UNOFFICIAL API — see lib/qobuz.js). Credentials/token set via Settings.
// We persist the username, the md5 of the password (for silent re-login), the
// user_auth_token, and the display name. Never the plaintext password.
const qobuz = require("./lib/qobuz");
const { fetchPitchfork, getPitchforkReviews, searchPitchforkReviews } = require("./lib/pitchfork");
const _persistedQobuz = loadSettings();
let qobuzUsername    = _persistedQobuz.qobuzUsername    || "";
let qobuzPasswordMd5 = _persistedQobuz.qobuzPasswordMd5 || "";
let qobuzToken       = _persistedQobuz.qobuzToken       || "";
let qobuzDisplayName = _persistedQobuz.qobuzDisplayName || "";

// Discogs / FanArt.tv / label-folder-depth — persisted settings, no connection
// required. Never expose the raw token/key back to the client, only masked.
let discogsToken     = _persistedQobuz.discogsToken     || "";
let fanartKey        = _persistedQobuz.fanartKey        || "";
let labelFolderDepth = Number(_persistedQobuz.labelFolderDepth) || 0;

// Wall display (/display): off by default. When off the page fetches nothing
// and the content endpoint refuses, so flipping the toggle brings a mounted
// wall tablet to life without a reload. `youtubeKey` (optional) enables the
// muted video-clip slides; without it, video is simply omitted.
let displayEnabled = _persistedQobuz.displayEnabled === true;
let displaySeconds = (() => {
  const s = parseInt(_persistedQobuz.displaySeconds, 10);
  return Number.isFinite(s) && s >= 5 && s <= 60 ? s : 10;
})();
let youtubeKey = _persistedQobuz.youtubeKey || "";

// ---------------------------------------------------------------------------
// Shared HTTP JSON helper (global fetch), deadlined. Used by the Qobuz browse
// routes and the wall-display YouTube lookup. A non-2xx throws with the status
// in the message so callers can map 429/401 to the right response.
// ---------------------------------------------------------------------------
async function httpJson(url, headers, timeoutMs = 8000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctl.signal });
    if (!res.ok) { const e = new Error("HTTP " + res.status); e.code = res.status; throw e; }
    return await res.json();
  } finally { clearTimeout(timer); }
}

// ---------------------------------------------------------------------------
// Qobuz browse infrastructure (the Qobuz page/tab + external search). Login is
// already handled below; these add authenticated-call plumbing and the slow-
// changing featured/favourite caches, ported from the Roon build.
// ---------------------------------------------------------------------------
// Short-lived cache of the user's favourited album ids, shared by every Qobuz
// browse route so each render doesn't re-fetch the full favourites list (429
// risk). Best-effort: on failure it serves recent ids (bounded staleness) or an
// empty Set — the list still renders, just without favourite marks.
function makeFavIdsCache({ name, fetchIds, cacheMs = 60 * 1000, staleMaxMs = 10 * 60 * 1000 }) {
  let ids = null, at = 0, pending = null;
  return {
    async get() {
      if (ids && (Date.now() - at) < cacheMs) return ids;
      if (pending) return pending;
      pending = (async () => {
        try { const fresh = await fetchIds(); ids = fresh; at = Date.now(); return fresh; }
        catch (e) {
          if (DEBUG) console.error("[" + name + "] favourite-ids lookup failed:", e.message);
          if (ids && (Date.now() - at) < staleMaxMs) return ids;
          return new Set();
        } finally { pending = null; }
      })();
      return pending;
    },
    add(id)    { if (ids) ids.add(String(id)); },
    remove(id) { if (ids) ids.delete(String(id)); },
    clear()    { ids = null; at = 0; }
  };
}
// TTL memo keyed by string. Featured lists change slowly (~daily) but each tab
// tap would otherwise hit the rate-limit-sensitive unofficial API. Values are
// cached RAW; favourite flags are applied per request from the fresher fav-ids
// cache. Errors are not cached — a failed fetch just throws.
function makeTtlCache(ttlMs) {
  const map = new Map();
  return {
    async get(key, fetchFn) {
      const hit = map.get(key);
      if (hit && (Date.now() - hit.at) < ttlMs) return hit.value;
      const value = await fetchFn();
      map.set(key, { value, at: Date.now() });
      return value;
    },
    clear() { map.clear(); }
  };
}
let qobuzLoginPending  = null;
let qobuzLoginFailedAt = 0;
// Silent re-login from the stored md5 (single-flight; 60s failure backoff).
function qobuzRelogin() {
  if (Date.now() - qobuzLoginFailedAt < 60 * 1000) {
    return Promise.reject(new Error("Qobuz not connected — recent login attempt failed, retrying shortly"));
  }
  if (!qobuzLoginPending) {
    qobuzLoginPending = (async () => {
      try {
        const r = await qobuz.login(qobuzUsername, qobuzPasswordMd5, true);
        qobuzToken = r.token; qobuzDisplayName = r.displayName; qobuzLoginFailedAt = 0;
        saveSettings({ qobuzToken, qobuzDisplayName });
      } catch (e) { qobuzLoginFailedAt = Date.now(); throw e; }
      finally { qobuzLoginPending = null; }
    })();
  }
  return qobuzLoginPending;
}
// Run an authenticated Qobuz call; on a 401 (expired token) re-login once and
// retry. Throws a "not connected" error when no credentials are stored.
async function qobuzWithToken(fn) {
  if (!qobuzToken && qobuzUsername && qobuzPasswordMd5) await qobuzRelogin();
  if (!qobuzToken) throw new Error("Qobuz not connected — add your Qobuz login in Settings");
  try { return await fn(qobuzToken); }
  catch (e) {
    if (e && e.code === 401 && qobuzUsername && qobuzPasswordMd5) { await qobuzRelogin(); return await fn(qobuzToken); }
    throw e;
  }
}
const qobuzFavIds = makeFavIdsCache({ name: "qobuz", fetchIds: () => qobuzWithToken(t => qobuz.getFavoriteAlbumIds(t)) });
const qobuzFeaturedCache = makeTtlCache(10 * 60 * 1000); // type → raw items[]
function getFeaturedItemsCached(type) {
  return qobuzFeaturedCache.get(type, () => qobuzWithToken(t => qobuz.getFeaturedAlbums(t, type, 150)));
}
// Best-effort release timestamp (ms) from a Qobuz album object.
function qobuzReleaseTs(a) {
  if (a.released_at && Number.isFinite(a.released_at)) return a.released_at * 1000;
  const d = a.release_date_original || a.release_date_stream || a.release_date_download;
  if (d) { const t = Date.parse(d); if (Number.isFinite(t)) return t; }
  return null;
}
// Shared album→JSON normalizer for every album-returning Qobuz route.
function normalizeQobuzAlbum(a, favIds) {
  return {
    id:           String(a.id),
    title:        a.title || "",
    version:      a.version || null,
    artist:       (a.artist && a.artist.name) || (a.performer && a.performer.name) || "",
    artist_id:    (a.artist && a.artist.id != null) ? String(a.artist.id) : null,
    image:        qobuz.pickImage(a),
    released_at:  qobuzReleaseTs(a),
    release_date: a.release_date_original || null,
    favourited:   favIds.has(String(a.id))
  };
}
function normalizeQobuzAlbums(items, favIds) {
  const albums = [];
  for (const a of items || []) { if (a && a.id) albums.push(normalizeQobuzAlbum(a, favIds)); }
  return albums;
}
// Streaming-service HTTP status mapping: 429 passes through, "not connected" is
// the caller's fault (400), everything else is upstream (502).
function serviceErrorStatus(e) {
  return e && e.code === 429 ? 429 : (/not connected/i.test(e.message) ? 400 : 502);
}
function parseOffsetParam(req) {
  const offset = parseInt(req.query.offset, 10);
  return (Number.isFinite(offset) && offset > 0) ? offset : 0;
}
// Per-source deadline so one slow source can't hold a combined response. The
// timer is cleared once the race settles so a resolved-fast source doesn't
// leave a 10s timer pinning the event loop until it fires.
function withDeadline(promise, ms) {
  let timer;
  const guard = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error("source deadline")), ms); });
  return Promise.race([promise, guard]).finally(() => clearTimeout(timer));
}

// ---------------------------------------------------------------------------
// Connection state
// ---------------------------------------------------------------------------
const state = {
  lms:        null,     // adapter instance (rebuilt when host/port change)
  connected:  false,
  lastError:  null,     // reason the last connection attempt failed (null once connected)
  server:     null,     // { version, uuid, playerCount, ... }
  players:    [],       // [{ id, name, model, connected, power }]
  statuses:   new Map() // playerId → normalised status (for cheap zone reads)
};

const index = search.makeIndex();
let indexBuilding = null;   // Promise while a build is in flight
let indexProgress = 0;

// ---------------------------------------------------------------------------
// Record-label index + background scanner (lib/labels.js). LMS has no
// first-class label facet, so labels are derived per album from file tags and
// the free metadata APIs and cached to disk — see lib/labels.js. Everything it
// needs is injected: the album list (the same in-memory search index), the
// normaliser, and the persisted Discogs/FanArt/folder-depth settings (read via
// getters so it always sees the current values after a Settings change).
// ---------------------------------------------------------------------------
const { makeLabels } = require("./lib/labels");
const labels = makeLabels({
  dataDir:             DATA_DIR,
  getAlbums:           () => index.records,
  normalize:           search.normalize,
  getDiscogsToken:     () => discogsToken,
  getFanartKey:        () => fanartKey,
  getLabelFolderDepth: () => labelFolderDepth,
  debug:               DEBUG
});

// ---------------------------------------------------------------------------
// Album reviews + artist bios (lib/albuminfo.js): the LMS "Music and Artist
// Information" plugin first (id-based, so the plugin can use the MusicBrainz
// ids LMS stores for tagged local files), then Qobuz's wiki-style album
// descriptions / artist biographies as the no-plugin fallback (also covers
// TIDAL albums, which rarely carry reviews, by matching them on Qobuz).
// ---------------------------------------------------------------------------
const { makeAlbumInfo } = require("./lib/albuminfo");
const albumInfo = makeAlbumInfo({
  getLms:    () => (state.connected ? state.lms : null),
  qobuzCall: (fn) => qobuzWithToken(fn),
  qobuz,
  dataDir:   DATA_DIR,
  normalize: search.normalize,
  artistKey: search.artistKey,
  debug:     DEBUG
});

// ---------------------------------------------------------------------------
// In-app self-updater — checks GitHub for a newer release and, on request,
// downloads + applies it and restarts into the new code (no `docker build`).
// The restart is coordinated by launcher.js (PID 1), which sets
// RRA_VIA_LAUNCHER=1; see lib/updater.js. Backs the /api/update/* routes.
// ---------------------------------------------------------------------------
const { makeUpdater } = require("./lib/updater");
const updater = makeUpdater({
  owner: "meltface-80",
  repo: "MusicD-LMS-Remote",
  currentVersion: pkg.version,
  dir: __dirname,
  viaLauncher: process.env.RRA_VIA_LAUNCHER === "1",
  debug: DEBUG
});

// FNV-1a string hash — a stable seed for deterministic daily/weekly picks
// (album-of-the-day, label-of-the-week). Returns an unsigned 32-bit int;
// callers do `hash % n` to choose an index.
function fnv1aHash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

// ISO-week key (e.g. "2026-W28"), Monday–Sunday — stable seed for the label of
// the week so the pick holds all week and rotates weekly.
function isoWeekKey(d = new Date()) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  t.setUTCDate(t.getUTCDate() + 4 - (t.getUTCDay() || 7)); // ISO: Thursday sets the week-year
  const yStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  const wk = Math.ceil(((t - yStart) / 86400000 + 1) / 7);
  return t.getUTCFullYear() + "-W" + wk;
}

// Confident library match for a review's album/artist, or null. Uses the same
// in-memory search as the search box, but only accepts the top hit when the
// title matches closely (normalized equality or a prefix) so a "Play" button
// never points at the wrong album.
function matchLibraryAlbum(album, artist) {
  if (!album || !index.records.length) return null;
  const want = search.normalize(album);
  if (!want) return null;
  const hits = search.searchAlbums(index, (artist ? artist + " " : "") + album, 3);
  for (const h of hits) {
    const got = search.normalize(h.title);
    if (!got) continue;
    if (got === want || got.startsWith(want) || want.startsWith(got)) {
      return { offset: h.offset, title: h.title, subtitle: h.subtitle, image_key: h.image_key };
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Wall-display muted video clip via the YouTube Data API — only when the user
// supplied a key in Settings. PRECISION-FIRST: show the artist's official music
// video or an official live performance, or NOTHING — never chat-show clips,
// fan uploads, or " - Topic" auto-uploads (static art with audio: worthless on
// a muted screen). Candidates are scored on channel ownership + title keywords,
// must clear a threshold, then verified via videos.list (embeddable, public,
// not age-restricted). Cached per artist+track incl. negatives (search costs
// 100 quota units of the 10k/day default).
const displayVideoCache = new Map();
function scoreDisplayVideo(item, artistN, trackTokens) {
  const title    = (item.snippet && item.snippet.title        || "");
  const channel  = (item.snippet && item.snippet.channelTitle || "");
  const titleN   = search.normalize(title);
  const channelN = search.normalize(channel);
  if (/ - topic$/i.test(channel)) return -1;
  if (/\b(audio|lyric|lyrics|visuali[sz]er|cover|reaction|remix|sped|slowed|8d|karaoke|instrumental|full album|teaser|trailer|interview|behind the scenes|epk|shorts?)\b/i.test(title)) return -1;
  for (const t of trackTokens) if (titleN.indexOf(t) === -1) return -1;
  let score = 0;
  const channelIsArtist = channelN === artistN || channelN === artistN + " vevo" ||
                          channelN === artistN + " music" || channelN === artistN + " official" ||
                          channelN.replace(/\s+/g, "") === artistN.replace(/\s+/g, "") + "vevo";
  if (channelIsArtist) score += 70;
  else if (channelN.indexOf(artistN) !== -1) score += 40;
  else return -1;
  if (/\bofficial (music )?video\b/i.test(title)) score += 30;
  else if (/\(official\b/i.test(title)) score += 20;
  if (/\blive\b/i.test(title)) { if (score >= 70) score += 20; else return -1; }
  return score;
}
async function fetchDisplayVideo(artistName, trackName) {
  if (!youtubeKey || !artistName || !trackName) return null;
  const key = search.normalize(artistName) + "||" + search.normalize(trackName);
  const hit = displayVideoCache.get(key);
  if (hit) {
    // Positive verdicts hold for the session; a "no video" verdict expires
    // after 30 min so transient API failures don't blank a track for good.
    if (hit.video || (Date.now() - hit.at) < 30 * 60 * 1000) return hit.video;
    displayVideoCache.delete(key);
  }
  let video = null;
  try {
    const q = `${artistName} ${trackName}`;
    const searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
      "&videoEmbeddable=true&videoSyndicated=true&maxResults=10" +
      "&q=" + encodeURIComponent(q) + "&key=" + encodeURIComponent(youtubeKey);
    const json = await httpJson(searchUrl);
    const artistN = search.normalize(artistName);
    const trackTokens = search.normalize(trackName).split(" ").filter(t => t.length > 2);
    const scored = ((json && json.items) || [])
      .filter(it => it && it.id && it.id.videoId && it.snippet)
      .map(it => ({ id: it.id.videoId, score: scoreDisplayVideo(it, artistN, trackTokens) }))
      .filter(c => c.score >= 70)
      .sort((a, b) => b.score - a.score);
    if (scored.length) {
      const statusUrl = "https://www.googleapis.com/youtube/v3/videos?part=status,contentDetails,statistics" +
        "&id=" + encodeURIComponent(scored.map(c => c.id).join(",")) + "&key=" + encodeURIComponent(youtubeKey);
      const st = await httpJson(statusUrl);
      const playable = new Map(((st && st.items) || [])
        .filter(v => v && v.status && v.status.embeddable && v.status.privacyStatus === "public" &&
                     !(v.contentDetails && v.contentDetails.contentRating && v.contentDetails.contentRating.ytRating === "ytAgeRestricted"))
        .map(v => [v.id, parseInt((v.statistics && v.statistics.viewCount) || "0", 10)]));
      const best = scored.filter(c => playable.has(c.id))
        .sort((a, b) => (b.score - a.score) || (playable.get(b.id) - playable.get(a.id)))[0];
      if (best) {
        video = { videoId: best.id, embedUrl: "https://www.youtube-nocookie.com/embed/" + best.id +
          "?autoplay=1&mute=1&controls=0&modestbranding=1&playsinline=1&rel=0&loop=1&playlist=" + best.id + "&enablejsapi=1" };
      }
    }
  } catch (e) { if (DEBUG) console.error("[display:youtube]", e.message); }
  displayVideoCache.set(key, { at: Date.now(), video });
  return video;
}

function lmsConfigFromSettings() {
  const s = loadSettings();
  return {
    host:     process.env.LMS_HOST || s.host || null,
    port:     Number(process.env.LMS_PORT || s.port) || 9000,
    username: process.env.LMS_USER || s.username || null,
    password: process.env.LMS_PASS || s.password || null
  };
}

// (Re)create the adapter from current settings. Returns the adapter or null if
// no host is known yet (awaiting discovery / user config).
function rebuildAdapter() {
  const cfg = lmsConfigFromSettings();
  if (!cfg.host) { state.lms = null; return null; }
  state.lms = createLms(cfg);
  return state.lms;
}

// Try to establish/refresh the connection. Called on boot and on a timer.
let refreshing = false;
async function refreshConnection() {
  // The poll interval (2.5s) is shorter than the RPC timeout (8s); against a
  // slow/wedged LMS, overlapping runs would pile up per-player status sockets.
  if (refreshing) return;
  refreshing = true;
  try { await refreshConnectionInner(); }
  finally { refreshing = false; }
}
async function refreshConnectionInner() {
  if (!state.lms) {
    if (!rebuildAdapter()) {
      // No configured host — try one round of UDP discovery.
      try {
        const found = await discover({ timeoutMs: 2500 });
        if (found && found.host) {
          saveSettings({ host: found.host, port: found.port });
          rebuildAdapter();
          if (DEBUG) console.log("[lms] discovered", found.host + ":" + found.port, found.name || "");
        }
      } catch (e) { /* discovery best-effort; user can configure manually */ }
    }
  }
  if (!state.lms) {
    state.connected = false;
    state.lastError = "No LMS host configured (set LMS_HOST or use Settings)";
    return;
  }

  try {
    const ss = await state.lms.serverStatus();
    const wasConnected = state.connected;
    state.connected = true;
    state.lastError = null;
    state.server = ss;
    state.players = ss.players;
    // Refresh per-player status (cheap for a handful of players).
    for (const p of ss.players) {
      try {
        const st = await state.lms.playerStatus(p.id);
        state.statuses.set(p.id, st);
        scrobbleUpdate(p.id, st);
      }
      catch (e) { /* a single player being unreachable is non-fatal */ }
    }
    if (!wasConnected) {
      if (DEBUG) console.log("[lms] connected to", state.lms.cfg.host + ":" + state.lms.cfg.port);
      ensureIndex();   // build the search index on (re)connect
    }
  } catch (e) {
    state.connected = false;
    // Log on every distinct failure (not just under DEBUG) so `docker logs`
    // shows why, without needing a container recreate to add -e DEBUG=1.
    if (e.message !== state.lastError) {
      console.error("[lms] connection to", state.lms.cfg.host + ":" + state.lms.cfg.port, "failed:", e.message);
    }
    state.lastError = e.message;
  }
}

// ---------------------------------------------------------------------------
// Album search index — built by paging the LMS `albums` query.
// ---------------------------------------------------------------------------
const INDEX_PAGE = 500;
const INDEX_MAX_AGE_MS = 12 * 60 * 60 * 1000;

async function buildIndex() {
  if (!state.lms) throw new Error("Not connected to LMS");
  indexProgress = 0;
  const total = await state.lms.countAlbums();
  const rows = [];
  for (let start = 0; start < total; start += INDEX_PAGE) {
    const { albums } = await state.lms.listAlbums({ start, count: INDEX_PAGE });
    if (!albums.length) break;
    rows.push(...albums);
    indexProgress = total ? Math.min(1, rows.length / total) : 1;
  }
  search.loadRecords(index, rows);
  indexProgress = 1;
  if (DEBUG) console.log("[index] built", index.records.length, "albums");
  // Labels ride on the album index: re-project cached labels onto the fresh
  // offsets (fast, no network), then kick the background scan to fill in any
  // albums we haven't looked up yet. Both are best-effort — a labels failure
  // must never break the core library index.
  try { labels.onAlbumIndexRebuilt(); } catch (e) { if (DEBUG) console.error("[labels] reseed:", e.message); }
  labels.runScan().catch(e => { if (DEBUG) console.error("[labels] scan:", e.message); });
  return index;
}

function ensureIndex() {
  // Keyed off builtAt (set by loadRecords), NOT records.length — a genuinely
  // empty/still-scanning library has a valid built-but-empty index and must not
  // re-trigger a build (and its countAlbums RPC) on every request.
  const stale = !index.builtAt || (Date.now() - index.builtAt) > INDEX_MAX_AGE_MS;
  if (stale && !indexBuilding) {
    indexBuilding = buildIndex()
      .catch(e => { if (DEBUG) console.error("[index] build failed:", e.message); })
      .finally(() => { indexBuilding = null; });
  }
  return indexBuilding;
}

// ---------------------------------------------------------------------------
// Genre list cache — the Home genre row and the genre-filtered wall both need
// {id, title, count}; genre_id resolution (title → id) for the filtered wall
// reuses this instead of re-querying LMS's `genres` + per-genre counts on
// every /api/random-albums call.
// ---------------------------------------------------------------------------
let genresCache = null;           // { at, list }
const GENRES_CACHE_MAX_AGE_MS = 5 * 60 * 1000;

async function getGenres() {
  if (genresCache && (Date.now() - genresCache.at) < GENRES_CACHE_MAX_AGE_MS) return genresCache.list;
  const list = await state.lms.genres();
  genresCache = { at: Date.now(), list };
  return list;
}

// ---------------------------------------------------------------------------
// Image proxy + byte-bounded LRU cache (mirrors the Roon build's server cache).
// ---------------------------------------------------------------------------
const IMG_CACHE_MAX_BYTES = 96 * 1024 * 1024;
const imgCache = new Map();  // key → { body, type, bytes }
let imgCacheBytes = 0;

function imgGet(key) {
  const v = imgCache.get(key);
  if (!v) return null;
  imgCache.delete(key); imgCache.set(key, v);  // LRU bump
  return v;
}
function imgPut(key, val) {
  // Concurrent misses for the same key both call imgPut; subtract any existing
  // entry's bytes first so the running total can't drift upward permanently.
  const prev = imgCache.get(key);
  if (prev) imgCacheBytes -= prev.bytes;
  imgCache.set(key, val);
  imgCacheBytes += val.bytes;
  while (imgCacheBytes > IMG_CACHE_MAX_BYTES && imgCache.size) {
    const oldest = imgCache.keys().next().value;
    imgCacheBytes -= imgCache.get(oldest).bytes;
    imgCache.delete(oldest);
  }
}

// Fetch an artwork URL from LMS as raw bytes.
function fetchArtwork(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      if (res.statusCode !== 200) { res.resume(); return reject(new Error("art HTTP " + res.statusCode)); }
      const chunks = [];
      res.on("data", c => chunks.push(c));
      res.on("end", () => resolve({ body: Buffer.concat(chunks), type: res.headers["content-type"] || "image/jpeg" }));
    });
    req.on("error", reject);
    req.setTimeout(8000, () => req.destroy(new Error("art timed out")));
  });
}

// ---------------------------------------------------------------------------
// Express app
// ---------------------------------------------------------------------------
const app = express();
app.use(compression());
app.use(express.json());
app.use(express.static(path.join(__dirname, "public")));

const notConnected = (res) => res.status(503).json({
  error: "Not connected to Lyrion Music Server yet" + (state.lastError ? " (" + state.lastError + ")" : "")
});

// ---- status / zones ----

app.get("/api/status", (req, res) => {
  res.json({
    paired:     state.connected,
    core_id:    state.server ? state.server.uuid : null,
    core_name:  state.server ? ("Lyrion Music Server " + (state.server.version || "")) : null,
    zone_count: state.players.length
  });
});

// Players are LMS "zones"; each player is its own single output.
app.get("/api/zones", (req, res) => {
  const list = state.players.map(p => {
    const st = state.statuses.get(p.id);
    return {
      zone_id:      p.id,
      display_name: p.name,
      state:        st ? (st.mode === "play" ? "playing" : st.mode === "pause" ? "paused" : "stopped") : "stopped",
      outputs:      [{ output_id: p.id, display_name: p.name }]
    };
  }).sort((a, b) => a.display_name.localeCompare(b.display_name));
  res.json({ zones: list });
});

// ---- library reads ----

function albumOut(rec) {
  return { offset: rec.offset, title: rec.title || "", subtitle: rec.subtitle || "", image_key: rec.image_key || null, source: rec.source || null };
}

app.get("/api/random-albums", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const count = Math.max(1, Math.min(96, parseInt(req.query.count || "24", 10)));
  try {
    // Genre isn't a tag in the in-memory search index (ALBUM_TAGS carries no
    // genre field), so a genre-filtered wall is served with a fresh, filtered
    // LMS query instead — simpler than adding a new tag and re-indexing the
    // whole library just for this one filter.
    if (req.query.filter_type === "genre" && req.query.filter_value) {
      const wanted = String(req.query.filter_value);
      const list = await getGenres();
      const match = list.find(g => g.title === wanted) ||
        list.find(g => g.title.toLowerCase() === wanted.toLowerCase());
      if (!match) return res.json({ albums: [], total: 0, filtered: true });
      const total = await state.lms.countAlbums({ genreId: match.id });
      if (!total) return res.json({ albums: [], total: 0, filtered: true });
      const want = Math.min(count, total);
      // No offset-based random access in one LMS call, so pull a page big
      // enough to cover the request and sample from it.
      const { albums } = await state.lms.listAlbums({ start: 0, count: Math.min(total, 500), genreId: match.id });
      const pool = albums;
      const picked = new Set();
      while (picked.size < Math.min(want, pool.length)) picked.add(Math.floor(Math.random() * pool.length));
      // listAlbums()'s `offset` is this filtered page's local position
      // (start + i), NOT the album's position in the full library — but
      // /api/album?offset=N looks up index.byOffset, the GLOBAL index. Map
      // each filtered album back to its real indexed record by LMS id so
      // tapping a genre tile opens the correct album.
      await ensureIndex();
      const out = [...picked]
        .map(i => index.byId.get(pool[i].id))
        .filter(Boolean)
        .map(albumOut);
      return res.json({ albums: out, total, filtered: true });
    }

    await ensureIndex();
    const pool = index.records;
    if (!pool.length) return res.json({ albums: [], total: 0, filtered: false });
    const want = Math.min(count, pool.length);
    const picked = new Set();
    while (picked.size < want) picked.add(Math.floor(Math.random() * pool.length));
    res.json({ albums: [...picked].map(i => albumOut(pool[i])), total: pool.length, filtered: false });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get("/api/search", (req, res) => {
  const q = (req.query.q || "").trim();
  const limit = Math.max(1, Math.min(60, parseInt(req.query.limit || "40", 10)));
  // The frontend reads `results` (albums), `labels` and `artists` — match the
  // Roon build's shape exactly. `albums` is kept as an alias for any older
  // caller. Labels come from the derived label index (empty until it seeds).
  if (!q) return res.json({ query: q, results: [], albums: [], labels: [], artists: [], indexed: index.records.length });
  const results = search.searchAlbums(index, q, limit);
  res.json({
    query:   q,
    indexed: index.records.length,
    results,
    albums:  results,
    labels:  labels.searchLabels(search.normalize(q)),
    // The frontend renders artist chips from `{ name }` objects (ar.name).
    artists: search.searchArtists(index, q, 8).map(name => ({ name }))
  });
});

app.get("/api/search-status", (req, res) => {
  res.json({ ready: index.records.length > 0, building: !!indexBuilding, count: index.records.length, progress: indexProgress });
});

// The artist page: the artist's OWN albums (solo or co-billed, e.g.
// "Artist A / Artist B") under `primary`, and albums they only APPEAR on
// (feat. credits, track-level contributions, compilations) under `featured`.
//
// Matching is by the stylization-folded identity key (search.artistKey), so
// "P!nk" and "Pink" are ONE artist — never disambiguated into two pages.
//
// Two passes:
//   1. String pass over the in-memory index: co-billed main artists →
//      primary; feat./anywhere-in-subtitle credits → featured.
//   2. LMS contributor pass (best-effort): `artists search:` → every
//      matching contributor id (all stylized spellings) → `albums
//      artist_id:` — this is LMS's own contributor table, so it also finds
//      track-level appearances (compilations) the subtitle string can't
//      show. Extra albums land in `featured` unless the artist is co-billed.
app.get("/api/artist-albums", async (req, res) => {
  const artist = (req.query.artist || "").trim();
  if (!artist) return res.status(400).json({ error: "artist required" });
  if (!index.records.length) return res.json({ artist, primary: [], featured: [] });
  const key = search.artistKey(artist) || search.normalize(artist);
  const norm = search.normalize(artist);

  const isMain = (al) => (al.mainArtists || []).some(a => (a.k || a.n) === key);
  const isCredited = (al) =>
    (al.artistNames || []).some(a => (a.k || a.n) === key) ||
    (norm && search.normalize(al.subtitle || "").includes(norm));

  const primary = new Map(), featured = new Map();   // offset → record
  for (const al of index.records) {
    if (isMain(al)) primary.set(al.offset, al);
    else if (isCredited(al)) featured.set(al.offset, al);
  }

  // LMS contributor augmentation — additive only; any failure (older server,
  // mid-reconnect) leaves the string-pass result intact. LMS's own search is
  // literal, so it must be run for EVERY stylized spelling of this identity
  // present in the library ("Pink" won't find "P!nk"); the index knows them.
  if (state.connected) {
    try {
      const spellings = new Set([artist]);
      for (const al of index.records) {
        for (const a of (al.artistNames || [])) {
          if ((a.k || a.n) === key) spellings.add(a.name);
          if (spellings.size >= 6) break;
        }
      }
      const seen = new Set();
      const contributors = [];
      for (const sp of spellings) {
        for (const c of await state.lms.searchArtists(sp, 20)) {
          if (search.artistKey(c.name) === key && !seen.has(c.id)) { seen.add(c.id); contributors.push(c); }
        }
      }
      for (const c of contributors) {
        const { albums } = await state.lms.listAlbums({ start: 0, count: 500, artistId: c.id });
        for (const row of albums) {
          const rec = index.byId.get(String(row.id));
          if (!rec || primary.has(rec.offset)) continue;
          if (isMain(rec)) { featured.delete(rec.offset); primary.set(rec.offset, rec); }
          else featured.set(rec.offset, rec);
        }
      }
    } catch (e) { if (DEBUG) console.error("[artist-albums] LMS contributor pass failed:", e.message); }
  }

  const byTitle = (a, b) => (a.title || "").localeCompare(b.title || "");
  res.json({
    artist,
    primary:  [...primary.values()].map(albumOut).sort(byTitle),
    featured: [...featured.values()].map(albumOut).sort(byTitle)
  });
});

app.get("/api/library-stats", (req, res) => {
  if (!state.connected) return notConnected(res);
  res.json({ albums: index.records.length, building: index.records.length === 0 && !!indexBuilding });
});

// Genre list for the Home "Browse by genre" row, biggest-first (the frontend
// slices to its own top-N; we just need to return the counts sorted).
app.get("/api/filters/genres", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    const list = await getGenres();
    res.json({ genres: list.map(g => ({ title: g.title, count: g.count })).sort((a, b) => b.count - a.count) });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get("/api/music-mount", (req, res) => {
  // LMS owns the files; the PWA's local file-metadata scanner isn't used here.
  res.json({ mounted: false, path: null });
});

// Album detail by offset → LMS album id → tracks.
app.get("/api/album", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const offset = parseInt(req.query.offset, 10);
  if (!Number.isFinite(offset) || offset < 0) return res.status(400).json({ error: "Valid offset query parameter required" });
  const rec = index.byOffset.get(offset);
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  try {
    const tracks = await state.lms.albumTracks(rec.id);
    res.json({
      album:  { title: rec.title, subtitle: rec.subtitle, image_key: rec.image_key, year: rec.year },
      tracks: tracks.map(t => ({ title: t.title, subtitle: t.artist || "" })),
      actions: [
        { kind: "play_now",  title: "Play Now" },
        { kind: "queue",     title: "Queue" },
        { kind: "play_next", title: "Next" }
      ]
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Artwork proxy: image_key is the LMS coverid.
app.get("/api/image/:image_key", async (req, res) => {
  const size = Math.max(64, Math.min(1200, parseInt(req.query.size || "400", 10)));
  const key = req.params.image_key + "@" + size;
  const cached = imgGet(key);
  if (cached) {
    res.set("Content-Type", cached.type);
    res.set("Cache-Control", "public, max-age=604800, immutable");
    return res.send(cached.body);
  }
  if (!state.lms) return res.status(503).end();
  try {
    const { body, type } = await fetchArtwork(state.lms.artworkUrl(req.params.image_key, size));
    imgPut(key, { body, type, bytes: body.length });
    res.set("Content-Type", type);
    res.set("Cache-Control", "public, max-age=604800, immutable");
    res.send(body);
  } catch (e) { res.status(404).end(); }
});

// ---- playback ----

const KIND_TO_MODE = { play_now: "now", play_next: "next", queue: "queue" };

app.post("/api/play", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { offset, zone_or_output_id, kind } = req.body || {};
  if (!Number.isFinite(offset))      return res.status(400).json({ error: "offset required" });
  if (!zone_or_output_id)            return res.status(400).json({ error: "zone_or_output_id required" });
  const mode = KIND_TO_MODE[kind];
  if (!mode)                         return res.status(400).json({ error: "kind required" });
  const rec = index.byOffset.get(offset);
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  try { await state.lms.playAlbum(zone_or_output_id, rec.id, mode); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/play-multi", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { offsets, zone_or_output_id, kind } = req.body || {};
  if (!Array.isArray(offsets) || !offsets.length) return res.status(400).json({ error: "offsets required" });
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const mode = KIND_TO_MODE[kind];
  if (!mode) return res.status(400).json({ error: "kind required" });
  try {
    // The first album that actually resolves uses the requested mode (e.g.
    // Play Now = replace); the rest are appended in order. Tracking "first
    // resolved" (not index 0) means an unknown leading offset can't silently
    // demote a Play Now into an append onto the existing queue.
    let first = true;
    for (const off of offsets) {
      const rec = index.byOffset.get(off);
      if (!rec) continue;
      await state.lms.playAlbum(zone_or_output_id, rec.id, first ? mode : "queue");
      first = false;
    }
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Play/queue a single track. body { offset, track (index), zone_or_output_id, kind }
app.post("/api/play-track", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { offset, track, zone_or_output_id, kind } = req.body || {};
  if (!Number.isFinite(offset)) return res.status(400).json({ error: "offset required" });
  if (!Number.isInteger(track) || track < 0) return res.status(400).json({ error: "track index required" });
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const mode = KIND_TO_MODE[kind];
  if (!mode) return res.status(400).json({ error: "kind must be play_now, queue or play_next" });
  const rec = index.byOffset.get(offset);
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  try {
    const tracks = await state.lms.albumTracks(rec.id);
    const t = tracks[track];
    if (!t) return res.status(409).json({ error: "Track index out of range (library changed?)" });
    await state.lms.playTracks(zone_or_output_id, [t.id], mode);
    res.json({ ok: true, action: kind, track: t.title });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/play-from-here", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { zone_or_output_id, queue_item_id } = req.body || {};
  if (!zone_or_output_id || queue_item_id === undefined || queue_item_id === null) {
    return res.status(400).json({ error: "zone_or_output_id and queue_item_id required" });
  }
  // In the LMS queue, queue_item_id is the playlist index (see /api/queue).
  try { await state.lms.playIndex(zone_or_output_id, Number(queue_item_id)); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/queue/remove", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { zone_or_output_id, queue_item_id } = req.body || {};
  if (!zone_or_output_id || queue_item_id === undefined || queue_item_id === null) {
    return res.status(400).json({ error: "zone_or_output_id and queue_item_id required" });
  }
  // In the LMS queue, queue_item_id is the playlist index (see /api/queue).
  try { await state.lms.removeFromQueue(zone_or_output_id, Number(queue_item_id)); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// ---- transport / mini-transport ----

const CONTROL_MAP = {
  play: "play", pause: "pause", playpause: "toggle",
  stop: "stop", previous: "prev", next: "next"
};

app.post("/api/control", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { zone_or_output_id, command } = req.body || {};
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const action = CONTROL_MAP[command];
  if (!action) return res.status(400).json({ error: "invalid command, allowed: " + Object.keys(CONTROL_MAP).join(", ") });
  try { await state.lms.transport(zone_or_output_id, action); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/seek", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { zone_or_output_id } = req.body || {};
  const seconds = Number(req.body && req.body.seconds);
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  if (!Number.isFinite(seconds) || seconds < 0) return res.status(400).json({ error: "seconds must be a non-negative number" });
  try { await state.lms.seek(zone_or_output_id, seconds); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/volume", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { zone_or_output_id } = req.body || {};
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  try {
    if (req.body.mute !== undefined) {
      await state.lms.setMute(zone_or_output_id, !!req.body.mute);
    } else if (req.body.value !== undefined) {
      const v = parseFloat(req.body.value);
      if (!Number.isFinite(v)) return res.status(400).json({ error: "value must be a number" });
      await state.lms.setVolume(zone_or_output_id, v);
    } else if (req.body.relative !== undefined) {
      const d = parseFloat(req.body.relative);
      if (!Number.isFinite(d)) return res.status(400).json({ error: "relative must be a number" });
      // LMS applies the delta atomically, so rapid taps accumulate correctly.
      await state.lms.adjustVolume(zone_or_output_id, d);
    } else {
      return res.status(400).json({ error: "value, relative, or mute required" });
    }
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Transfer = sync target to source then unsync source (LMS's move idiom).
app.post("/api/transfer-zone", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { from_zone, to_zone } = req.body || {};
  if (!from_zone || !to_zone) return res.status(400).json({ error: "from_zone and to_zone required" });
  if (from_zone === to_zone) return res.json({ ok: true, noop: true });
  try {
    await state.lms.syncPlayers(from_zone, to_zone);  // to_zone joins from_zone
    await state.lms.unsync(from_zone);                // from_zone leaves; to_zone keeps playing
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Live zone state for the mini-transport bar.
app.get("/api/zone-state", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = req.query.zone;
  const player = state.players.find(p => p.id === zoneId);
  if (!player) return res.json({ zone: null });
  let st = state.statuses.get(zoneId);
  try { st = await state.lms.playerStatus(zoneId); state.statuses.set(zoneId, st); }
  catch (e) { /* fall back to the cached status if a live fetch fails */ }
  const t = (st && st.track) || null;
  res.json({
    zone: {
      zone_id: player.id,
      display_name: player.name,
      state: st ? (st.mode === "play" ? "playing" : st.mode === "pause" ? "paused" : "stopped") : "stopped",
      is_play_allowed: true, is_pause_allowed: true, is_next_allowed: true,
      is_previous_allowed: true, is_seek_allowed: true,
      outputs: [{
        output_id: player.id, display_name: player.name,
        is_muted: !!(st && st.muted),
        volume: st && st.volume != null ? { value: st.volume, min: 0, max: 100, step: 1, soft_limit: 100, type: "number" } : null
      }],
      now_playing: t ? {
        line1: t.title || "", line2: t.artist || "", line3: t.album || "",
        image_key: t.coverId || null, length: st.duration || null, seek_position: st.time || null
      } : null
    }
  });
});

// The current album's tracks for the now-playing modal.
app.get("/api/album/now-playing", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = req.query.zone;
  if (!zoneId) return res.status(400).json({ error: "zone required" });
  try {
    const st = await state.lms.playerStatus(zoneId);
    const t = st.track;
    if (!t) return res.json({ album: { title: "", subtitle: "", image_key: null }, tracks: [] });
    const fallback = { album: { title: t.album || "", subtitle: t.artist || "", image_key: t.coverId || null }, tracks: [] };
    // Try to resolve the full album (by matching the index) for the track list.
    // Require a non-empty artist match — otherwise normalize("") makes the
    // artist test vacuously true and any same-titled album (e.g. "Greatest
    // Hits") would match, showing the wrong track list. Empty artist (radio/
    // remote streams) → keep the honest fallback.
    const na = search.normalize(t.artist);
    const nt = search.normalize(t.album);
    const rec = na && nt
      ? index.records.find(r => search.normalize(r.title) === nt && search.normalize(r.subtitle).includes(na))
      : null;
    if (!rec) return res.json(fallback);
    const tracks = await state.lms.albumTracks(rec.id);
    res.json({
      album: { title: rec.title, subtitle: rec.subtitle, image_key: rec.image_key },
      tracks: tracks.map(x => ({ title: x.title, subtitle: x.artist || "" }))
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Queue for a zone. Only the CURRENT track and what's still to come are
// returned — already-played entries are dropped server-side, so the Queue tab
// (and its total-time/quality summary) reflects just the remaining queue.
// queue_item_id stays the REAL LMS playlist index, so play-from-here and
// remove keep working on the sliced list.
app.get("/api/queue", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = req.query.zone;
  if (!zoneId) return res.status(400).json({ error: "zone required" });
  try {
    const { tracks, curIndex } = await state.lms.queue(zoneId);
    const from = curIndex != null ? curIndex : 0;
    const remaining = tracks.filter(t => t.index == null || t.index >= from);
    res.json({
      cur_index: curIndex,
      items: remaining.map(t => ({
        queue_item_id: t.index, title: t.title || "", subtitle: t.artist || "",
        image_key: t.coverId || null, length: t.duration || null,
        // Quality info for the summary line ("FLAC 16/44.1" etc.).
        type: t.type || null, bitrate: t.bitrate || null,
        samplerate: t.samplerate || null, samplesize: t.samplesize || null
      }))
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Reindex on demand.
app.post("/api/reindex", async (req, res) => {
  if (!state.connected) return notConnected(res);
  index.builtAt = 0;
  ensureIndex();
  res.json({ ok: true });
});

// ---- iOS Shortcuts helpers ----
app.get("/api/shortcut/zones", (req, res) => {
  res.json({ zones: state.players.map(p => ({ name: p.name, id: p.id })) });
});
app.post("/api/play-unheard", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = (req.body && req.body.zone) || null;
  if (!zoneId) return res.status(400).json({ error: "zone required" });
  if (!index.records.length) return res.status(503).json({ error: "No albums available" });
  const rec = index.records[Math.floor(Math.random() * index.records.length)];
  try { await state.lms.playAlbum(zoneId, rec.id, "now"); res.json({ ok: true, album: { title: rec.title, subtitle: rec.subtitle } }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// ---- LMS connection settings (used by the new Material-skin settings UI) ----
app.get("/api/lms/connection", (req, res) => {
  const cfg = lmsConfigFromSettings();
  res.json({ host: cfg.host, port: cfg.port, connected: state.connected, server: state.server, lastError: state.lastError });
});
app.post("/api/lms/connection", async (req, res) => {
  const { host, port, username, password } = req.body || {};
  if (!host) return res.status(400).json({ error: "host required" });
  saveSettings({ host, port: Number(port) || 9000, username: username || null, password: password || null });
  state.lms = null; state.connected = false;
  await refreshConnection();
  res.json({ ok: true, connected: state.connected });
});
app.get("/api/lms/discover", async (req, res) => {
  try { const found = await discover({ timeoutMs: 2500 }); res.json({ found: found || null }); }
  catch (e) { res.json({ found: null }); }
});
// Material-skin-level prefs: server + per-player get/set.
app.get("/api/lms/pref/:name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { res.json({ name: req.params.name, value: await state.lms.getPref(req.params.name) }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/pref/:name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { await state.lms.setPref(req.params.name, (req.body || {}).value); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.get("/api/lms/player/:id/pref/:name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { res.json({ name: req.params.name, value: await state.lms.getPlayerPref(req.params.id, req.params.name) }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/player/:id/pref/:name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { await state.lms.setPlayerPref(req.params.id, req.params.name, (req.body || {}).value); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/rescan", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { await state.lms.rescan((req.body || {}).mode || null); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Album metadata extras: release year + record label + Pitchfork score/
// Best-New-Music/review-link lookup. Frontend passes title and artist (album
// modal, share card, service-album detail view) so we don't need a live
// Roon/LMS round-trip to look it up.
//
// - Year comes from LMS: the album index carries the year LMS read from the
//   local file tags (the `y` album tag; lib/search.js keeps rec.year), which
//   is the authoritative local-file source. We match the requested title/
//   artist against the in-memory index the same way /api/album/now-playing
//   does and take that record's year.
// - Label comes from labels.labelForAlbum() — the same override→file-tag→
//   disk-cache lookup the labels browser uses, so the modal and the browser
//   always agree on an album's label (and its grouped display form).
// - Pitchfork stays exactly as before. COMPLIANCE (UK law): the written
//   review body is never emitted — only the score, the Best New Music flag,
//   and a LINK to read the review on pitchfork.com (description stays null).
// ---------------------------------------------------------------------------
app.get("/api/album/extras", async (req, res) => {
  const title  = String(req.query.title  || "");
  const artist = String(req.query.artist || "");
  if (!title) return res.status(400).json({ error: "title query parameter required" });
  try {
    // LMS year: match the index like /api/album/now-playing — normalized
    // title equality, plus (when an artist is given) a normalized subtitle
    // that contains the artist. No artist → match on title alone.
    const nt = search.normalize(title);
    const na = search.normalize(artist);
    const rec = index.records.find(r =>
      search.normalize(r.title) === nt &&
      (!na || search.normalize(r.subtitle).includes(na))
    );
    const year = rec && rec.year != null ? rec.year : null;

    // Label: same override→file-tag→disk-cache lookup the labels browser uses.
    // Canonicalize it (strip "Records"/country suffixes) to the exact grouped
    // display form the browser shows, so the modal's label text — and its
    // tappable "more on this label" link — land on the same label the browser
    // groups the album under, not a raw variant spelling.
    const rawLabel = labels.labelForAlbum({ title, subtitle: artist });
    const label = rawLabel ? labels.canonicalName(rawLabel) : null;

    // Review TEXT comes from the LMS Music & Artist Information plugin or
    // Qobuz (see lib/albuminfo.js) — never from Pitchfork. Pitchfork stays a
    // score + link. Deadlined so a cold multi-source lookup can't hold the
    // modal's year/label; the result is cached, so the next open has it.
    const [pitchfork, review] = await Promise.all([
      fetchPitchfork(title, artist).catch(() => null),
      withDeadline(albumInfo.albumReview({
        albumId: rec ? rec.id : null,
        title, artist,
        extid:  rec ? rec.extid  : null,
        source: rec ? rec.source : null
      }), 15000).catch(() => null)
    ]);

    // Build the album object whenever there is ANY datum to carry (label, year,
    // a review, or a Pitchfork hit) — otherwise the label/year never reach the
    // modal for albums the sources don't cover.
    let album = null;
    if (label != null || year != null || pitchfork || review) {
      album = {
        description:        review ? review.text        : null,
        descriptionSource:  review ? review.attribution : null,
        year,
        label,
        url:            pitchfork ? pitchfork.url            : null,
        source:         pitchfork ? "Pitchfork"              : null,
        score:          pitchfork ? pitchfork.score          : null,
        isBestNewMusic: pitchfork ? pitchfork.isBestNewMusic : false
      };
    }
    res.json({ year, album, artist: null });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Home section: random albums NOT played in the last N months (default 6).
// Uses the in-memory album search index (no LMS round-trip) filtered against
// the plays log, so it's fast. Returns the same album shape as
// /api/random-albums so the tiles open via the existing modal/play path.
// Matching is by album title (lowercased/trimmed) — the plays log only
// records the title, same imprecision as the sibling Roon build's version.
app.get("/api/home/unplayed", async (req, res) => {
  if (!state.connected) return notConnected(res);
  let months = parseInt(req.query.months, 10);
  if (!Number.isFinite(months) || months <= 0 || months > 60) months = 6;
  let count = parseInt(req.query.count, 10);
  if (!Number.isFinite(count) || count <= 0 || count > 96) count = 12;
  try {
    await ensureIndex();
    const pool = index.records;
    if (!pool.length) return res.json({ albums: [], total: 0, months });
    const cutoff = Date.now() - months * 30 * 24 * 60 * 60 * 1000;
    const heard = playsLog.getPlayedTitlesSince(cutoff);
    const candidates = pool.filter(rec => {
      const t = (rec.title || "").toLowerCase().trim();
      return !(t && heard.has(t)); // played within the window — skip
    });
    if (!candidates.length) return res.json({ albums: [], total: 0, months });
    const want = Math.min(count, candidates.length);
    const picked = new Set();
    while (picked.size < want) picked.add(Math.floor(Math.random() * candidates.length));
    const albums = [...picked].map(i => albumOut(candidates[i]));
    res.json({ albums, total: candidates.length, months });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---------------------------------------------------------------------------
// Home section: "album of the day" — one completely random album, chosen
// deterministically from today's local date so it's stable all day and changes
// each day. Once it has been played today (a play row with that title since
// local midnight) it's withheld ({ album: null, played: true }) until tomorrow.
// The Roon build reads its SQLite plays table for the "played today" check; we
// read the JSON plays log's title set instead (lib/plays.js), same idea.
// ---------------------------------------------------------------------------
app.get("/api/home/album-of-the-day", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    await ensureIndex();
    const pool = index.records;
    if (!pool.length) return res.json({ album: null });
    // Deterministic index from the local date (YYYY-M-D, no zero padding — the
    // exact format the sibling app hashes, so picks stay in step).
    const now = new Date();
    const dstr = now.getFullYear() + "-" + (now.getMonth() + 1) + "-" + now.getDate();
    const rec = pool[fnv1aHash(dstr) % pool.length];
    // Played today? Plays log records album titles lowercased/trimmed.
    const midnight = new Date(); midnight.setHours(0, 0, 0, 0);
    const heard = playsLog.getPlayedTitlesSince(midnight.getTime());
    if (heard.has((rec.title || "").toLowerCase().trim())) return res.json({ album: null, played: true });
    res.json({ album: { offset: rec.offset, title: rec.title || "", subtitle: rec.subtitle || "", image_key: rec.image_key || null } });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Home section: "label of the week" — one record label featured for the whole
// ISO week, chosen deterministically from the week key so it's stable all week
// and rotates weekly. Only labels with a fuller catalogue (>= 6 albums) are
// eligible so the single-row carousel fills out. Cached ~1h; recomputed when
// the week changes or the label index grows (a fresh scan can add labels and
// would otherwise shift the pick mid-week).
let lotwCache = { weekKey: "", at: 0, count: -1, data: null };
app.get("/api/home/label-of-the-week", (req, res) => {
  try {
    const wk = isoWeekKey();
    const { keys, count, get } = labels.weekCandidates(6);
    if (lotwCache.data && lotwCache.weekKey === wk && lotwCache.count === count &&
        (Date.now() - lotwCache.at) < 60 * 60 * 1000) {
      return res.json(lotwCache.data);
    }
    if (!keys.length) {
      const empty = { label: null, albums: [] };
      lotwCache = { weekKey: wk, at: Date.now(), count, data: empty };
      return res.json(empty);
    }
    const entry = get(keys[fnv1aHash(wk) % keys.length]);
    const albums = entry.albums.slice(0, 24).map(a => ({
      offset: a.offset, title: a.title || "", subtitle: a.subtitle || "", image_key: a.image_key || null, source: a.source || null
    }));
    const data = { label: entry.display, albums };
    lotwCache = { weekKey: wk, at: Date.now(), count, data };
    res.json(data);
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Labels browser + management. The derived label index (lib/labels.js) is
// seeded on every index build and refreshed by a background scan; these routes
// read it and drive the scan/merge/logo tooling in the settings UI.
// ---------------------------------------------------------------------------
app.get("/api/filters/labels", (req, res) => {
  if (!state.connected) return notConnected(res);
  // Kick a scan if one has never run (or the last is stale). seedFromCache has
  // already run on the index build, so a fresh restart still returns whatever
  // was cached to disk immediately.
  labels.maybeAutoRescan();
  const st = labels.status();
  const list = labels.listLabels();
  // Report scanning until we actually have labels, so the UI shows progress
  // rather than a permanent "no labels" state during the first scan.
  const noDataYet = list.length === 0 && (st.scanning || index.records.length === 0);
  res.json({ labels: list, scanning: st.scanning || noDataYet, progress: st.progress, count: st.count });
});

// All albums for one label, ordered. ?label=NAME&order=alpha|random
app.get("/api/label-albums", (req, res) => {
  const name  = String(req.query.label || "").trim();
  if (!name) return res.status(400).json({ error: "label query parameter required" });
  res.json(labels.labelAlbums(name, req.query.order));
});

// Labels scan status — lets the UI poll while the background scan runs.
app.get("/api/labels-scan-status", (req, res) => res.json(labels.status()));

// Trigger a rescan (only new albums) / a full rescan (re-query everything).
app.post("/api/labels/rescan", (req, res) => {
  if (!state.connected) return notConnected(res);
  res.json(labels.requestRescan());
});
app.post("/api/labels/rescan-force", (req, res) => {
  if (!state.connected) return notConnected(res);
  res.json(labels.forceRescan());
});

// Serve locally cached label logo images (downloaded at save time).
app.get("/api/labels/logo-image/:filename", (req, res) => {
  const p = labels.logoImagePath(req.params.filename);
  if (!p) return res.status(404).end();
  res.sendFile(p);
});

// Discogs logo candidates for the logo picker UI.
app.get("/api/labels/logo-candidates", async (req, res) => {
  const name = (req.query.label || "").trim();
  if (!name) return res.status(400).json({ error: "label required" });
  try { res.json({ candidates: await labels.logoCandidates(name) }); }
  catch (e) { res.status(/token/i.test(e.message) ? 400 : 500).json({ error: e.message }); }
});

// Manually set (or override) the logo URL for a label tile. Body: { label, url }
app.post("/api/labels/logo", async (req, res) => {
  const { label, url } = req.body || {};
  try { res.json({ ok: true, storedUrl: await labels.setLogo(label, url) }); }
  catch (e) { res.status(400).json({ error: e.message }); }
});

// Merge two or more label tiles into one. Body: { items: [target, ...sources] }
app.post("/api/labels/merge", (req, res) => {
  const r = labels.mergeLabels((req.body || {}).items);
  res.status(r.ok ? 200 : 400).json(r);
});
// Remove a single source label from a merge group.
app.delete("/api/labels/merge/:sourceKey", (req, res) => {
  res.json(labels.unmerge(req.params.sourceKey));
});

// Scan log — downloaded / copied from the settings UI.
app.get("/api/labels-scan-log", (req, res) => {
  const log = labels.readScanLog();
  res.setHeader("Content-Type", "text/plain; charset=utf-8");
  if (log == null) return res.send("No scan log yet — run a scan first.\n");
  res.setHeader("Content-Disposition", "attachment; filename=\"labels-scan.log\"");
  res.send(log);
});

// ---------------------------------------------------------------------------
// PHASE 2 stubs — advanced features not yet ported. Each returns a safe empty
// shape so the existing frontend degrades gracefully instead of erroring.
// ---------------------------------------------------------------------------
app.get("/api/home/genre-groups",    (req, res) => res.json({ groups: [] }));     // PHASE 2
app.get("/api/filters/tags",         (req, res) => res.json({ tags: [] }));       // PHASE 2
app.get("/api/filters/decades",      (req, res) => res.json({ decades: [] }));    // PHASE 2 (LMS `years` query)
// ---------------------------------------------------------------------------
// Wall display (/display). The page polls /api/settings/display to honour the
// toggle live; /api/display/content assembles the per-album rotation extras.
// ---------------------------------------------------------------------------
app.get("/api/settings/display", (req, res) => res.json({ enabled: displayEnabled, seconds: displaySeconds }));
app.post("/api/settings/display", (req, res) => {
  const b = req.body || {};
  if (typeof b.enabled === "boolean") displayEnabled = b.enabled;
  if (b.seconds != null) {
    const s = parseInt(b.seconds, 10);
    if (Number.isFinite(s) && s >= 5 && s <= 60) displaySeconds = s;
  }
  const next = saveSettings({ displayEnabled, displaySeconds });
  res.json({ ok: next.displayEnabled === displayEnabled, enabled: displayEnabled, seconds: displaySeconds });
});
// Optional YouTube Data API key (masked on read, like the fanart key).
app.get("/api/settings/youtube-key", (req, res) => {
  res.json({ set: !!youtubeKey, masked: youtubeKey ? youtubeKey.slice(0, 4) + "…" : "" });
});
app.post("/api/settings/youtube-key", (req, res) => {
  youtubeKey = String((req.body && req.body.key) || "").trim();
  displayVideoCache.clear(); // a new key may find videos the old one couldn't
  const next = saveSettings({ youtubeKey });
  res.json({ ok: next.youtubeKey === youtubeKey, set: !!youtubeKey });
});
// The wall page itself. Served regardless of the toggle — the page shows a
// "turned off" note (and fetches nothing) when disabled, so flipping the
// Settings toggle brings a mounted wall tablet to life without a reload.
app.get("/display", (req, res) => res.sendFile(path.join(__dirname, "public", "display.html")));

// Assembled rotation content for the now-playing album on a zone: library
// recommendations (other albums by the artist + label-mates, both from the
// in-memory indexes — instant, no keys), the album review + credited-artist
// bios (LMS Music & Artist Information plugin → Qobuz fallback, see
// lib/albuminfo.js), plus a best-effort YouTube video clip when a key is set.
// Artist photos still degrade to empty (no source ported). Every part is
// best-effort — the page rotates whatever arrived. Cached 6h per album.
const displayContentCache = new Map();
const DISPLAY_CONTENT_TTL_MS = 6 * 60 * 60 * 1000;
app.get("/api/display/content", async (req, res) => {
  if (!displayEnabled) return res.status(403).json({ error: "Wall display is turned off in Settings" });
  if (!state.connected) return notConnected(res);
  const zoneId = String(req.query.zone || "");
  let st = state.statuses.get(zoneId);
  try { st = await state.lms.playerStatus(zoneId); state.statuses.set(zoneId, st); }
  catch (e) { /* fall back to cached status */ }
  const t = (st && st.track) || null;
  const empty = { artistPhotos: [], review: null, bio: null, bios: [], video: null, moreAlbums: { artist: null, label: null } };
  if (!t) return res.json(empty);
  const track  = t.title || "";
  const artist = t.artist || "";
  const album  = t.album || "";
  const primaryArtist = artist.split(" / ")[0].trim();

  const cacheKey = search.normalize(artist) + "||" + search.normalize(album) + "||" + search.normalize(track);
  const hit = displayContentCache.get(cacheKey);
  if (hit && (Date.now() - hit.at) < DISPLAY_CONTENT_TTL_MS) return res.json(hit.data);

  try {
    // Review + bios (LMS Music & Artist Information plugin → Qobuz fallback,
    // lib/albuminfo.js) and the video clip, fetched in parallel. Bios cover
    // every credited artist (capped) — the display cycles through them. The
    // library album record supplies the LMS album id / extid so the plugin
    // can identify by id (and stored MusicBrainz ids) rather than by name.
    const npTitleN = search.normalize(album);
    const npRec = index.records.find(r => search.normalize(r.title) === npTitleN &&
      search.normalize(r.subtitle || "").includes(search.normalize(primaryArtist))) || null;
    const creditedArtists = search.splitArtistNames(artist).map(a => a.name).slice(0, 3);
    const [video, review, ...bioResults] = await Promise.all([
      fetchDisplayVideo(primaryArtist, track).catch(() => null),
      withDeadline(albumInfo.albumReview({
        albumId: npRec ? npRec.id : null,
        title:   album,
        artist,
        extid:   npRec ? npRec.extid  : null,
        source:  npRec ? npRec.source : null
      }), 20000).catch(() => null),
      ...creditedArtists.map(name =>
        withDeadline(albumInfo.artistBio(name), 20000).catch(() => null))
    ]);
    const bios = bioResults.filter(Boolean)
      .map(b => ({ name: b.name, text: b.text, attribution: b.attribution }));
    // More by this artist — from the in-memory album index (no API keys).
    const artistN  = search.normalize(primaryArtist);
    const moreArtist = [];
    if (artistN) {
      for (const al of index.records) {
        if (moreArtist.length >= 12) break;
        if (search.normalize(al.title) === npTitleN) continue;
        const subN = search.normalize(al.subtitle || "");
        if (subN === artistN || subN.split(" / ").indexOf(artistN) !== -1 ||
            subN.startsWith(artistN + " /") || subN.indexOf(" / " + artistN) !== -1) {
          moreArtist.push({ offset: al.offset, title: al.title || "", subtitle: al.subtitle || "", image_key: al.image_key || null });
        }
      }
    }
    // More on this label — project the live album index onto the now-playing
    // album's label via the labels module (offsets stay valid this way).
    let moreLabel = null;
    const labelName = labels.labelForAlbum({ title: album, subtitle: artist });
    const targetKey = labelName ? labels.groupKey(labelName) : null;
    if (targetKey) {
      const picks = [];
      for (const al of index.records) {
        if (picks.length >= 12) break;
        if (search.normalize(al.title) === npTitleN) continue;
        const alLabel = labels.labelForAlbum(al);
        if (!alLabel || labels.groupKey(alLabel) !== targetKey) continue;
        picks.push({ offset: al.offset, title: al.title || "", subtitle: al.subtitle || "", image_key: al.image_key || null });
      }
      if (picks.length >= 3) moreLabel = { name: labels.canonicalName(labelName), albums: picks };
    }
    const data = {
      artistPhotos: [],
      review: review ? { text: review.text, attribution: review.attribution } : null,
      bio:    bios.length ? bios[0] : null,   // legacy single-bio field
      bios,
      video,
      moreAlbums: {
        artist: moreArtist.length >= 3 ? { name: primaryArtist, albums: moreArtist } : null,
        label:  moreLabel
      }
    };
    displayContentCache.delete(cacheKey);
    displayContentCache.set(cacheKey, { at: Date.now(), data });
    if (displayContentCache.size > 200) displayContentCache.delete(displayContentCache.keys().next().value);
    res.json(data);
  } catch (e) { res.status(500).json({ error: e.message }); }
});
// Self-update routes. getStatus() carries `apply` (live download/extract/restart
// progress) and `viaLauncher`, which the frontend poll reads to drive the UI.
app.get("/api/update/status", (req, res) => {
  updater.maybeCheck(); // fire-and-forget background refresh (throttled to hourly)
  res.json({ ...updater.getStatus(), current: pkg.version, is_docker: true });
});
app.post("/api/update/check", async (req, res) => {
  await updater.checkNow();
  res.json({ ...updater.getStatus(), current: pkg.version, is_docker: true });
});
app.post("/api/update/apply", async (req, res) => {
  // Respond BEFORE apply() runs — a successful apply exits the process (code 75)
  // so the launcher can restart into the new build, and we'd never get to send a
  // reply after that. The frontend then polls /api/update/status for progress.
  let st = updater.getStatus();
  if (!st.available) {
    st = await updater.checkNow();
    if (!st.available) return res.status(409).json({ error: "No update available", status: st });
  }
  res.json({ ok: true, status: updater.getStatus() });
  updater.apply().catch(() => {});
});
const notPorted = (name) => (req, res) => res.status(501).json({
  ok: false, error: name + " login isn't ported to this LMS build yet — see PORTING.md"
});
// Connection status (never returns credentials).
app.get("/api/settings/qobuz", (req, res) => {
  res.json({ connected: !!qobuzToken, username: qobuzUsername || "", displayName: qobuzDisplayName || "" });
});
// Connect: log in with email/password, persist token (+ md5 for re-login).
app.post("/api/settings/qobuz", async (req, res) => {
  const username = ((req.body && req.body.username) || "").trim();
  const password = ((req.body && req.body.password) || "");
  if (!username || !password) return res.status(400).json({ ok: false, error: "username and password required" });
  try {
    const r = await qobuz.login(username, password);
    qobuzUsername    = username;
    qobuzPasswordMd5 = r.passwordMd5;
    qobuzToken       = r.token;
    qobuzDisplayName = r.displayName;
    saveSettings({ qobuzUsername, qobuzPasswordMd5, qobuzToken, qobuzDisplayName });
    qobuzFavIds.clear();        // account may have changed — drop cached favourite ids
    qobuzFeaturedCache.clear();
    console.log("[settings] qobuz connected as " + qobuzDisplayName);
    res.json({ ok: true, displayName: qobuzDisplayName });
  } catch (e) {
    res.status(502).json({ ok: false, error: e.message });
  }
});
// Disconnect: clear all stored Qobuz credentials/token.
app.post("/api/settings/qobuz/disconnect", (req, res) => {
  qobuzUsername = qobuzPasswordMd5 = qobuzToken = qobuzDisplayName = "";
  qobuzFavIds.clear();
  qobuzFeaturedCache.clear();
  saveSettings({ qobuzUsername: "", qobuzPasswordMd5: "", qobuzToken: "", qobuzDisplayName: "" });
  res.json({ ok: true });
});

// ---- Qobuz browse (the Qobuz page/tab) ----
// New releases from the last N days (default 30), newest first.
app.get("/api/qobuz/new-releases", async (req, res) => {
  let days = parseInt(req.query.days, 10);
  if (!Number.isFinite(days) || days <= 0 || days > 365) days = 30;
  try {
    const [items, favIds] = await Promise.all([getFeaturedItemsCached("new-releases-full"), qobuzFavIds.get()]);
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    const future = Date.now() + 2 * 24 * 60 * 60 * 1000; // tolerate a couple days' skew
    const albums = [];
    for (const a of items) {
      if (!a || !a.id) continue;
      const ts = qobuzReleaseTs(a);
      if (ts !== null && (ts < cutoff || ts > future)) continue;
      albums.push(normalizeQobuzAlbum(a, favIds));
    }
    albums.sort((x, y) => (y.released_at || 0) - (x.released_at || 0));
    res.json({ albums, days });
  } catch (e) { res.status(serviceErrorStatus(e)).json({ error: e.message }); }
});
app.post("/api/qobuz/favorite", async (req, res) => {
  const albumId = ((req.body && req.body.album_id) || "").toString().trim();
  if (!albumId) return res.status(400).json({ ok: false, error: "album_id required" });
  try { await qobuzWithToken(t => qobuz.favoriteAlbum(t, albumId)); qobuzFavIds.add(albumId); res.json({ ok: true }); }
  catch (e) { res.status(serviceErrorStatus(e)).json({ ok: false, error: e.message }); }
});
app.post("/api/qobuz/unfavorite", async (req, res) => {
  const albumId = ((req.body && req.body.album_id) || "").toString().trim();
  if (!albumId) return res.status(400).json({ ok: false, error: "album_id required" });
  try { await qobuzWithToken(t => qobuz.unfavoriteAlbum(t, albumId)); qobuzFavIds.remove(albumId); res.json({ ok: true }); }
  catch (e) { res.status(serviceErrorStatus(e)).json({ ok: false, error: e.message }); }
});
// Full Qobuz catalog search (albums + artists), paged by offset.
app.get("/api/qobuz/search", async (req, res) => {
  const q = String(req.query.q || "").trim();
  if (!q) return res.status(400).json({ error: "q required" });
  const offset = parseOffsetParam(req);
  try {
    const [r, favIds] = await Promise.all([qobuzWithToken(t => qobuz.searchCatalog(t, q, 50, offset)), qobuzFavIds.get()]);
    const albums = normalizeQobuzAlbums(r.albums.items, favIds);
    const artists = [];
    if (offset === 0) {
      for (const x of r.artists.items.slice(0, 8)) {
        if (!x || !x.id) continue;
        artists.push({ id: String(x.id), name: x.name || "", image: qobuz.pickImage(x), albums_count: x.albums_count || 0 });
      }
    }
    // has_more from the RAW page length (normalization can drop malformed items).
    const hasMore = offset + r.albums.items.length < r.albums.total;
    res.json({ query: q, offset, limit: 50, total: r.albums.total, has_more: hasMore, albums, artists });
  } catch (e) { res.status(serviceErrorStatus(e)).json({ error: e.message }); }
});
// A Qobuz artist's discography, paged by offset (kept in Qobuz's own order).
app.get("/api/qobuz/artist-albums", async (req, res) => {
  const artistId = String(req.query.artist_id || "").trim();
  if (!artistId) return res.status(400).json({ error: "artist_id required" });
  const offset = parseOffsetParam(req);
  try {
    const [r, favIds] = await Promise.all([qobuzWithToken(t => qobuz.getArtist(t, artistId, 50, offset)), qobuzFavIds.get()]);
    const albums = normalizeQobuzAlbums(r.albums.items, favIds);
    const hasMore = offset + r.albums.items.length < r.albums.total;
    res.json({ artist: r.artist, offset, limit: 50, total: r.albums.total, has_more: hasMore, albums });
  } catch (e) { res.status(serviceErrorStatus(e)).json({ error: e.message }); }
});
// Qobuz featured/browse categories (albums stay in Qobuz's own order).
const QOBUZ_FEATURED_TYPES = new Set([
  "new-releases-full", "best-sellers", "most-streamed", "press-awards",
  "editor-picks", "qobuzissims", "ideal-discography", "recent-releases"
]);
app.get("/api/qobuz/featured", async (req, res) => {
  const type = String(req.query.type || "").trim();
  if (!QOBUZ_FEATURED_TYPES.has(type)) return res.status(400).json({ error: "invalid type" });
  try {
    const [items, favIds] = await Promise.all([getFeaturedItemsCached(type), qobuzFavIds.get()]);
    res.json({ type, albums: normalizeQobuzAlbums(items, favIds) });
  } catch (e) { res.status(serviceErrorStatus(e)).json({ error: e.message }); }
});

app.get("/api/settings/tidal",       (req, res) => res.json({ connected: false })); // PHASE 2
app.post("/api/settings/tidal/start", notPorted("Tidal"));                         // PHASE 2

// ---- Global search across external sources (Qobuz + Pitchfork; Tidal N/A) ----
app.get("/api/search/external", async (req, res) => {
  const q = String(req.query.q || "").trim();
  const LIM = 6, DEADLINE_MS = 10000;
  if (!q) return res.json({ query: q, qobuz: null, tidal: null, pitchfork: [] });
  const [qb, pf] = await Promise.all([
    (async () => {
      try {
        const r = await withDeadline(qobuzWithToken(t => qobuz.searchCatalog(t, q, LIM, 0)), DEADLINE_MS);
        return normalizeQobuzAlbums(r.albums.items.slice(0, LIM), new Set());
      } catch (e) { return null; /* not connected / blocked / slow — section absent */ }
    })(),
    withDeadline(searchPitchforkReviews(q, LIM), DEADLINE_MS).catch(() => [])
  ]);
  res.json({ query: q, qobuz: qb, tidal: null, pitchfork: pf });
});

// ---- Pitchfork magazine (browse + per-card library match) ----
// Browsable listing of recent album reviews or Best New Music (?type=latest|best).
app.get("/api/pitchfork/reviews", async (req, res) => {
  const type = req.query.type === "best" ? "best" : "latest";
  try { res.json({ type, items: await getPitchforkReviews(type) }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
// Library match for one listing card so its detail view can offer to play the
// album if it's in the library. COMPLIANCE (UK law): the written review is
// never served — `review` is always null; the client links to pitchfork.com.
app.get("/api/pitchfork/review", (req, res) => {
  let u;
  try { u = new URL(String(req.query.url || "")); } catch (e) { return res.status(400).json({ error: "Invalid url" }); }
  if (u.hostname !== "pitchfork.com" || !u.pathname.startsWith("/reviews/albums/")) {
    return res.status(400).json({ error: "Not a Pitchfork album-review URL" });
  }
  res.json({ review: null, match: matchLibraryAlbum(String(req.query.album || ""), String(req.query.artist || "")) });
});

// Discogs personal access token — get status (masked) or save. (Only the
// setting is ported here; the label-logo-matching pipeline that would use
// this token is a separate, much larger subsystem not yet ported.)
app.get("/api/settings/discogs-token", (req, res) => {
  res.json({
    set: !!discogsToken,
    masked: discogsToken ? "••••••••" + discogsToken.slice(-4) : ""
  });
});
app.post("/api/settings/discogs-token", (req, res) => {
  const token = ((req.body && req.body.token) || "").trim();
  if (!token) return res.status(400).json({ ok: false, error: "token is empty" });
  discogsToken = token;
  const next = saveSettings({ discogsToken: token });
  const saved = next.discogsToken === token;
  console.log("[settings] discogs token set (" + token.length + " chars), persisted=" + saved);
  res.json({ ok: true, saved });
});

// FanArt.tv API key — get status (masked) or save. (Same caveat as above: the
// fetch pipeline that would use this key isn't ported yet.)
app.get("/api/settings/fanart-key", (req, res) => {
  res.json({
    set: !!fanartKey,
    masked: fanartKey ? "••••••••" + fanartKey.slice(-4) : ""
  });
});
app.post("/api/settings/fanart-key", (req, res) => {
  const key = ((req.body && req.body.key) || "").trim();
  if (!key) return res.status(400).json({ ok: false, error: "key is empty" });
  fanartKey = key;
  const next = saveSettings({ fanartKey: key });
  const saved = next.fanartKey === key;
  console.log("[settings] fanart key set (" + key.length + " chars), persisted=" + saved);
  res.json({ ok: true, saved });
});

// Label-folder depth — for libraries organised in label folders. 0 = off (use
// the file's label tag). (The rescan side effect isn't ported yet — saving
// just persists the number for when that pipeline lands.)
app.get("/api/settings/label-folder-depth", (req, res) => {
  res.json({ depth: labelFolderDepth });
});
app.post("/api/settings/label-folder-depth", (req, res) => {
  const depth = parseInt((req.body && req.body.depth), 10);
  if (!Number.isFinite(depth) || depth < 0 || depth > 6) {
    return res.status(400).json({ ok: false, error: "depth must be 0–6" });
  }
  labelFolderDepth = depth;
  const next = saveSettings({ labelFolderDepth: depth });
  const saved = next.labelFolderDepth === depth;
  console.log("[settings] label folder depth set to " + depth + ", persisted=" + saved);
  // Folder depth changes which folder name becomes the label in the file-tag
  // pass, so re-run the scan to pick up the new mapping (no-op without /music).
  if (state.connected) labels.requestRescan();
  res.json({ ok: true, saved });
});

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
app.listen(PORT, () => {
  console.log(`MusicD LMS Remote v${pkg.version} listening on :${PORT}`);
  refreshConnection();
  const timer = setInterval(refreshConnection, 2500);
  if (timer.unref) timer.unref();
  // 12-hour label auto-rescan. maybeAutoRescan is a cheap no-op until its own
  // interval elapses (and while a scan is running), so a frequent tick is fine
  // and means a long-lived instance refreshes labels without a UI visit.
  const labelTimer = setInterval(() => { if (state.connected) labels.maybeAutoRescan(); }, 60 * 60 * 1000);
  if (labelTimer.unref) labelTimer.unref();
});

module.exports = app;
