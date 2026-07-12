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
const { fetchPitchfork } = require("./lib/pitchfork");
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
  return { offset: rec.offset, title: rec.title || "", subtitle: rec.subtitle || "", image_key: rec.image_key || null };
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

app.get("/api/artist-albums", (req, res) => {
  const artist = (req.query.artist || "").trim();
  if (!artist) return res.status(400).json({ error: "artist required" });
  if (!index.records.length) return res.json({ artist, primary: [], featured: [] });
  const norm = search.normalize(artist);
  const primary = [], featured = [];
  for (const al of index.records) {
    const sub = search.normalize(al.subtitle || "");
    if (!sub) continue;
    if (sub === norm) primary.push(albumOut(al));
    else if (sub.includes(norm)) featured.push(albumOut(al));
  }
  primary.sort((a, b) => (a.title || "").localeCompare(b.title || ""));
  featured.sort((a, b) => (a.title || "").localeCompare(b.title || ""));
  res.json({ artist, primary, featured });
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

// Queue for a zone.
app.get("/api/queue", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = req.query.zone;
  if (!zoneId) return res.status(400).json({ error: "zone required" });
  try {
    const q = await state.lms.queue(zoneId);
    res.json({ items: q.map(t => ({
      queue_item_id: t.index, title: t.title || "", subtitle: t.artist || "",
      image_key: t.coverId || null, length: t.duration || null
    })) });
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
// Album metadata extras: Pitchfork score/Best-New-Music/review-link lookup.
// Frontend passes title and artist (album modal, share card, service-album
// detail view) so we don't need Roon/LMS metadata to look it up.
//
// Only the Pitchfork album-review lookup is ported here — release year
// (MusicBrainz), artist/album bios (Discogs/Qobuz/Wikipedia scraping), and
// the label-disk-cache are separate, much bigger subsystems not yet ported;
// their fields are left null so the frontend degrades gracefully.
// ---------------------------------------------------------------------------
app.get("/api/album/extras", async (req, res) => {
  const title  = String(req.query.title  || "");
  const artist = String(req.query.artist || "");
  if (!title) return res.status(400).json({ error: "title query parameter required" });
  try {
    const pitchfork = await fetchPitchfork(title, artist).catch(() => null);
    let album = null;
    if (pitchfork) {
      album = {
        // COMPLIANCE (UK law): Pitchfork's written review must not be
        // displayed — only the score, the Best New Music flag, and a LINK
        // to read the review on pitchfork.com are emitted.
        description:    null,
        year:           null,
        label:          null,
        url:            pitchfork.url,
        source:         "Pitchfork",
        score:          pitchfork.score,
        isBestNewMusic: pitchfork.isBestNewMusic
      };
    }
    res.json({ year: null, album, artist: null });
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
      offset: a.offset, title: a.title || "", subtitle: a.subtitle || "", image_key: a.image_key || null
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
app.get("/api/settings/display",     (req, res) => res.json({ enabled: false, seconds: 10 })); // PHASE 2
app.get("/api/update/status",        (req, res) => res.json({ available: false, latest: pkg.version, current: pkg.version, is_docker: true })); // PHASE 2
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
    console.log("[settings] qobuz connected as " + qobuzDisplayName);
    res.json({ ok: true, displayName: qobuzDisplayName });
  } catch (e) {
    res.status(502).json({ ok: false, error: e.message });
  }
});
// Disconnect: clear all stored Qobuz credentials/token.
app.post("/api/settings/qobuz/disconnect", (req, res) => {
  qobuzUsername = qobuzPasswordMd5 = qobuzToken = qobuzDisplayName = "";
  saveSettings({ qobuzUsername: "", qobuzPasswordMd5: "", qobuzToken: "", qobuzDisplayName: "" });
  res.json({ ok: true });
});
app.get("/api/settings/tidal",       (req, res) => res.json({ connected: false })); // PHASE 2
app.post("/api/settings/tidal/start", notPorted("Tidal"));                         // PHASE 2

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
