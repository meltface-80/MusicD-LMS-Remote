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
const crypto = require("crypto");
const express = require("express");
const compression = require("compression");

const { createLms, discover } = require("./lib/lms");
const { assertPublicUrl } = require("./lib/urlguard");
const search = require("./lib/search");
const share  = require("./lib/share");
const { makePlaysLog } = require("./lib/plays");
const makeLivePlaylists = require("./lib/liveplaylists");
const makeHomePicks = require("./lib/homepicks");
const { makeSmartPicks } = require("./lib/smartpicks");
const { makeSmartCache } = require("./lib/smartcache");
const smartAlgo = require("./lib/smartpickalgo");
const discovery = require("./lib/discovery");
const { mbWait, MB_UA } = require("./lib/musicbrainz");
const makeFavourites = require("./lib/favourites");
const makeAlbumMerges = require("./lib/albummerges");
const makeRadio       = require("./lib/radio");

const pkg = require("./package.json");
const { makeLogger, levelName, setLogFile } = require("./lib/log");
const log = makeLogger("app");
// DEBUG stays a boolean for the many existing `if (DEBUG)` gates; the leveled
// logger (lib/log.js) reads the same env plus LOG_LEVEL for finer control.
const DEBUG = process.env.DEBUG === "1" || String(process.env.DEBUG).toLowerCase() === "trace";
const PORT = Number(process.env.PORT) || 3390;

// ---------------------------------------------------------------------------
// Persisted settings (LMS connection + app-local prefs) on the data volume.
// ---------------------------------------------------------------------------
const DATA_DIR = path.join(__dirname, "data");
const SETTINGS_FILE = path.join(DATA_DIR, "lms-settings.json");

// Roon-style rotating file log under the data volume (survives restarts + the
// in-app updater, which preserves data/). Console output is unchanged, so
// `docker logs` keeps working. Tunable via env; LOG_FILE=off disables the file.
if (String(process.env.LOG_FILE || "").toLowerCase() !== "off") {
  const logPath  = process.env.LOG_FILE || path.join(DATA_DIR, "logs", "musicd.log");
  const maxMb    = Number(process.env.LOG_MAX_MB) || 8;
  const archives = process.env.LOG_ARCHIVES != null ? Number(process.env.LOG_ARCHIVES) : 10;
  if (setLogFile(logPath, { maxBytes: maxMb * 1024 * 1024, archives })) {
    log.info("file log:", logPath, "(rotate at " + maxMb + "MB, keep " + archives + " archives)");
  }
}

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
// Remembers the day's album and the week's label so a restart can't re-roll
// them mid-period — see lib/homepicks.js for why they used to move.
const homePicks = makeHomePicks({ dataDir: DATA_DIR });
// This remote's OWN favourites — see lib/favourites.js. Keyed on title+artist
// so they survive rescans and can hold albums that aren't in the library at
// all. Nothing to do with the Qobuz heart, which writes to the Qobuz account.
const favourites = makeFavourites({ dataDir: DATA_DIR, debug: DEBUG });
// Multi-disc albums LMS split apart, collapsed back into one — see
// lib/albummerges.js. Applied to the raw rows during buildIndex().
const albumMerges = makeAlbumMerges({ dataDir: DATA_DIR, debug: DEBUG });
// Random Album Radio — keeps a player fed with whole random albums when its
// queue runs down. Stands down when LMS's own Don't Stop The Music is on for
// that player; see lib/radio.js for why two queue-fillers must not both run.
const radio = makeRadio({ dataDir: DATA_DIR, log: log.child("radio") });

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

const { fetchPitchfork, getPitchforkReviews, searchPitchforkReviews } = require("./lib/pitchfork");
const _persistedSettings = loadSettings();

// Discogs / FanArt.tv / label-folder-depth — persisted settings, no connection
// required. Never expose the raw token/key back to the client, only masked.
let discogsToken     = _persistedSettings.discogsToken     || "";
let fanartKey        = _persistedSettings.fanartKey        || "";
let labelFolderDepth = Number(_persistedSettings.labelFolderDepth) || 0;

// Wall display (/display): off by default. When off the page fetches nothing
// and the content endpoint refuses, so flipping the toggle brings a mounted
// wall tablet to life without a reload. `youtubeKey` (optional) enables the
// muted video-clip slides; without it, video is simply omitted.
let displayEnabled = _persistedSettings.displayEnabled === true;
let displaySeconds = (() => {
  const s = parseInt(_persistedSettings.displaySeconds, 10);
  return Number.isFinite(s) && s >= 5 && s <= 60 ? s : 10;
})();
let youtubeKey = _persistedSettings.youtubeKey || "";

// ---------------------------------------------------------------------------
// Shared HTTP JSON helper (global fetch), deadlined. Used by the wall-display
// YouTube lookup. A non-2xx throws with the status in the message.
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
  dataDir:   DATA_DIR,
  normalize: search.normalize,
  artistKey: search.artistKey,
  log:       makeLogger("albuminfo"),
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

// ---------------------------------------------------------------------------
// Album metadata overrides (lib/albumedits.js) + artwork rescue
// (lib/albumart.js). Both persist in the app's own database under data/ — the
// music mount is read-only, so the files/LMS are never modified. Edits are
// keyed by the ORIGINAL LMS title+artist so they survive rescans; artwork for
// local albums with no embedded/folder cover is fetched from external sources
// (MAI plugin → Cover Art Archive by MBID → MusicBrainz release-group →
// Qobuz → iTunes) and stored as "art-…" image keys served by /api/image.
// ---------------------------------------------------------------------------
const makeAlbumEdits = require("./lib/albumedits");
const albumEdits = makeAlbumEdits({ dataDir: DATA_DIR, debug: DEBUG });

const { makeAlbumArt } = require("./lib/albumart");
const albumArt = makeAlbumArt({
  getLms:    () => (state.connected ? state.lms : null),
  dataDir:   DATA_DIR,
  normalize: search.normalize,
  artistKey: search.artistKey,
  log:       makeLogger("albumart"),
  debug:     DEBUG
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
// Channels that host real live performances/sessions — acceptable sources for
// a "live version" even though the channel name isn't the artist's.
const LIVE_SESSION_CHANNELS = /\b(kexp|npr music|tiny desk|colors|a colors show|bbc|later with jools|glastonbury|austin city limits|acl|mtv|abbey road|vevo|triple j|like a version|radio 1|cardinal sessions|la blogotheque|paste (magazine|studio)|jimmy (kimmel|fallon)|the tonight show|saturday night live|snl|jools holland)\b/i;
function scoreDisplayVideo(item, artistN, artistK, trackTokens) {
  const title    = (item.snippet && item.snippet.title        || "");
  const channel  = (item.snippet && item.snippet.channelTitle || "");
  const titleN   = search.normalize(title);
  const channelN = search.normalize(channel);
  const channelK = search.artistKey(channel);   // stylization-folded ("PinkVEVO" → "pinkvevo")
  if (/ - topic$/i.test(channel)) return -1;    // auto-generated static album-art uploads
  if (/\b(audio|lyric|lyrics|visuali[sz]er|cover art|art track|reaction|remix|sped|slowed|8d|karaoke|instrumental|full album|full ep|teaser|trailer|interview|behind the scenes|epk|shorts?)\b/i.test(title)) return -1;
  if (/\bcover\b/i.test(title) && !/\bcover(ed)? by\b/i.test(channel)) {
    // "cover" in a title is a fan cover unless the ARTIST is covering someone.
    if (channelK.indexOf(artistK) === -1) return -1;
  }
  for (const t of trackTokens) if (titleN.indexOf(t) === -1) return -1;
  let score = 0;
  // Channel identity via the stylization-folded key, so "P!nk"/"PinkVEVO"/
  // "P!NK Official" all count as the artist's own channel.
  const channelIsArtist = !!artistK && (
    channelK === artistK || channelK === artistK + "vevo" ||
    channelK === artistK + "music" || channelK === artistK + "official" ||
    channelK === artistK + "tv");
  const isLive = /\b(live|session|acoustic|unplugged|performance)\b/i.test(title);
  if (channelIsArtist) score += 70;
  else if (artistK && channelK.indexOf(artistK) !== -1) score += 40;
  else if (isLive && LIVE_SESSION_CHANNELS.test(channel) && artistK && titleN.replace(/[^a-z0-9]+/g, "").indexOf(artistK) !== -1) {
    score += 70;   // known performance channel + artist named in the title
  }
  else return -1;
  if (/\bofficial (music )?video\b/i.test(title)) score += 30;
  else if (/\(official\b/i.test(title)) score += 20;
  if (isLive) { if (score >= 70) score += 20; else return -1; }
  return score;
}
// ISO-8601 YouTube duration ("PT3M52S") → seconds, null when unparsable.
function ytDurationSecs(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(String(iso || ""));
  if (!m) return null;
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0);
}
// A music video / live take should be in the same ballpark as the track:
// full-album statics and hour-long concerts are out, as are sub-45s teasers.
// Live versions run long, so the ceiling is generous.
function videoDurationOk(videoSecs, trackSecs) {
  if (videoSecs == null) return true;               // no data — don't reject
  if (videoSecs < 45) return false;
  if (!trackSecs) return videoSecs <= 15 * 60;      // no track length: cap at 15 min
  return videoSecs <= Math.max(trackSecs * 2.5, trackSecs + 8 * 60);
}
async function fetchYouTubeVideo(artistName, trackName, trackSecs) {
  if (!youtubeKey) return null;
  let video = null;
  // "official video OR live" nudges relevance toward real videos; category 10
  // (Music) drops reactions/vlogs before scoring even sees them.
  const q = `${artistName} ${trackName}`;
  const searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
    "&videoEmbeddable=true&videoSyndicated=true&videoCategoryId=10&maxResults=15" +
    "&q=" + encodeURIComponent(q) + "&key=" + encodeURIComponent(youtubeKey);
  const json = await httpJson(searchUrl);
  const artistN = search.normalize(artistName);
  const artistK = search.artistKey(artistName);
  const trackTokens = search.normalize(trackName).split(" ").filter(t => t.length > 2);
  const scored = ((json && json.items) || [])
    .filter(it => it && it.id && it.id.videoId && it.snippet)
    .map(it => ({ id: it.id.videoId, score: scoreDisplayVideo(it, artistN, artistK, trackTokens) }))
    .filter(c => c.score >= 70)
    .sort((a, b) => b.score - a.score);
  if (scored.length) {
    const statusUrl = "https://www.googleapis.com/youtube/v3/videos?part=status,contentDetails,statistics" +
      "&id=" + encodeURIComponent(scored.map(c => c.id).join(",")) + "&key=" + encodeURIComponent(youtubeKey);
    const st = await httpJson(statusUrl);
    const playable = new Map(((st && st.items) || [])
      .filter(v => v && v.status && v.status.embeddable && v.status.privacyStatus === "public" &&
                   !(v.contentDetails && v.contentDetails.contentRating && v.contentDetails.contentRating.ytRating === "ytAgeRestricted") &&
                   // Duration sanity: kills full-album statics and teasers that
                   // sneak past the title filters.
                   videoDurationOk(ytDurationSecs(v.contentDetails && v.contentDetails.duration), trackSecs))
      .map(v => [v.id, parseInt((v.statistics && v.statistics.viewCount) || "0", 10)]));
    const best = scored.filter(c => playable.has(c.id))
      .sort((a, b) => (b.score - a.score) || (playable.get(b.id) - playable.get(a.id)))[0];
    if (best) {
      video = { provider: "youtube", videoId: best.id,
        embedUrl: "https://www.youtube-nocookie.com/embed/" + best.id +
        "?autoplay=1&mute=1&controls=0&modestbranding=1&playsinline=1&rel=0&loop=1&playlist=" + best.id + "&enablejsapi=1" };
    }
  }
  return video;
}
// Dailymotion fallback — its data API is public (NO key needed). Same
// hygiene as the YouTube scorer: track tokens must appear in the title, art/
// audio/reaction junk is rejected, the artist must be named in the title or
// own the channel, embedding must be allowed, and the duration must be
// plausible for the track.
const DM_BAD_TITLE_RE = /\b(audio|lyric|lyrics|visuali[sz]er|cover|reaction|remix|sped|slowed|8d|karaoke|instrumental|full album|teaser|trailer|interview)\b/i;
async function fetchDailymotionVideo(artistName, trackName, trackSecs) {
  const q = `${artistName} ${trackName}`;
  const url = "https://api.dailymotion.com/videos?search=" + encodeURIComponent(q) +
    "&fields=id,title,duration,owner.screenname,allow_embed&limit=10&sort=relevance";
  const json = await httpJson(url);
  const artistK = search.artistKey(artistName);
  const trackTokens = search.normalize(trackName).split(" ").filter(t => t.length > 2);
  for (const v of ((json && json.list) || [])) {
    if (!v || !v.id || v.allow_embed === false) continue;
    const titleN = search.normalize(v.title || "");
    const titleK = titleN.replace(/[^a-z0-9]+/g, "");
    const ownerK = search.artistKey(v["owner.screenname"] || "");
    if (DM_BAD_TITLE_RE.test(v.title || "")) continue;
    if (trackTokens.some(t => titleN.indexOf(t) === -1)) continue;
    if (!(artistK && (titleK.indexOf(artistK) !== -1 || ownerK.indexOf(artistK) !== -1))) continue;
    if (!videoDurationOk(Number(v.duration) || null, trackSecs)) continue;
    return { provider: "dailymotion", videoId: "dm:" + v.id,
      embedUrl: "https://www.dailymotion.com/embed/video/" + encodeURIComponent(v.id) +
        "?autoplay=1&mute=1&controls=0&queue-enable=false&api=postMessage" };
  }
  return null;
}

// iTunes Search API fallback — public, NO key. Returns real music-video
// PREVIEWS (~30s .m4v) as direct media files, so no embed roulette: the
// display loops the clip in a plain <video>. Better than album art, clearly
// labelled a preview by its length.
async function fetchITunesPreview(artistName, trackName) {
  const q = `${artistName} ${trackName}`;
  const url = "https://itunes.apple.com/search?term=" + encodeURIComponent(q) +
    "&entity=musicVideo&limit=10";
  const json = await httpJson(url);
  const artistK = search.artistKey(artistName);
  const trackN = search.normalize(trackName);
  for (const r of ((json && json.results) || [])) {
    if (!r || !r.previewUrl) continue;
    const tn = search.normalize(r.trackName || "");
    const ak = search.artistKey(r.artistName || "");
    if (!(tn === trackN || tn.startsWith(trackN) || trackN.startsWith(tn))) continue;
    if (!(artistK && ak && (ak === artistK || ak.indexOf(artistK) !== -1 || artistK.indexOf(ak) !== -1))) continue;
    return { provider: "itunes", videoId: "it:" + (r.trackId || r.previewUrl), videoUrl: r.previewUrl };
  }
  return null;
}

// Source order: YouTube (needs the user's Data API key; full videos, best
// coverage) → Dailymotion (no key; full videos, thinner catalog) → iTunes
// previews (no key; ~30s clips). Each step is best-effort.
async function fetchDisplayVideo(artistName, trackName, trackSecs) {
  if (!artistName || !trackName) return null;
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
    video = await fetchYouTubeVideo(artistName, trackName, trackSecs);
  } catch (e) { if (DEBUG) console.error("[display:youtube]", e.message); }
  if (!video) {
    try { video = await fetchDailymotionVideo(artistName, trackName, trackSecs); }
    catch (e) { if (DEBUG) console.error("[display:dailymotion]", e.message); }
  }
  if (!video) {
    try { video = await fetchITunesPreview(artistName, trackName); }
    catch (e) { if (DEBUG) console.error("[display:itunes]", e.message); }
  }
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
  state.lms = createLms({ ...cfg, log: makeLogger("lms") });
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
  // The playlist-folder answer is cached in the adapter; drop it on every
  // reconnect so fixing the setting on the server takes effect here without
  // restarting the app.
  if (state.lms && state.lms.forgetPlaylistFolder) state.lms.forgetPlaylistFolder();
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
    // Poll every player at once rather than one after another: this tick runs
    // every 2.5s forever, and serialising it made the whole tick as slow as the
    // SUM of its players. One unreachable player still must not fail the rest.
    await Promise.all(ss.players.map(async (p) => {
      try {
        const st = await state.lms.playerStatus(p.id);
        state.statuses.set(p.id, st);
        scrobbleUpdate(p.id, st);
      }
      catch (e) { /* a single player being unreachable is non-fatal */ }
    }));
    // Random Album Radio rides the existing 2.5s poll — no timer of its own,
    // and it works off the statuses this tick just fetched, so it costs one
    // extra LMS call only when it actually decides to queue something.
    if (radio.list().length) {
      radio.prune(state.players.map(p => p.id));
      for (const p of state.players) {
        if (radio.isOn(p.id)) runRadioFor(p.id, false).catch(() => {});
      }
    }
    if (!wasConnected) {
      if (DEBUG) console.log("[lms] connected to", state.lms.cfg.host + ":" + state.lms.cfg.port);
      ensureIndex();   // build the search index on (re)connect
      invalidateServices();
    } else if (scanChangedSinceIndex() && !indexBuilding) {
      // LMS finished a scan we didn't start. Rebuild once, here, rather than
      // waiting for a request to notice — merges, edits and artwork are all
      // re-layered by buildIndex, so this is the one place that heals them.
      log.info("lms: library rescan detected (lastScan changed) — rebuilding the index");
      libraryViewCache.clear();
      ensureIndex();
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

// The built index is cached to disk so a restart doesn't re-page the entire
// library out of LMS before the Home screen can answer anything. That rebuild
// was the dominant cost of a cold open: nothing was persisted, so every
// container restart paid for the whole library again.
//
// Validity is keyed on LMS's OWN last-scan timestamp plus the album count, so
// the cache is used only while the server's library is demonstrably unchanged;
// a rescan (or any change in album count) invalidates it automatically. RAW
// LMS rows are cached — owner edits and rescued artwork are layered on at
// build time, so those stay live and are never frozen into the cache.
const INDEX_CACHE_FILE = path.join(DATA_DIR, "index-cache.json");

function indexCacheSig(total) {
  const scan = state.server && state.server.lastScan;
  // No lastScan (older/odd servers) → no signature we can trust, so no cache.
  if (scan == null) return null;
  return String(scan) + "|" + String(total);
}

function readIndexCache(sig) {
  if (!sig) return null;
  try {
    const j = JSON.parse(fs.readFileSync(INDEX_CACHE_FILE, "utf8"));
    if (!j || j.sig !== sig || !Array.isArray(j.rows) || !j.rows.length) return null;
    return j.rows;
  } catch (e) { return null; }   // absent/corrupt → just rebuild
}

function writeIndexCache(sig, rows) {
  if (!sig || !rows.length) return;
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    // Write-then-rename so a crash mid-write can't leave a half file that
    // parses as valid JSON.
    const tmp = INDEX_CACHE_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify({ sig, at: Date.now(), rows }));
    fs.renameSync(tmp, INDEX_CACHE_FILE);
  } catch (e) {
    if (DEBUG) console.error("[index] cache write failed:", e.message);
  }
}

// How many album pages to have in flight at once. The pages were fetched
// strictly one after another, which on a big library meant dozens of serial
// round trips before ANY of the Home rows could answer — the single biggest
// contributor to a slow cold open. Kept modest so a rebuild doesn't monopolise
// the server (the keep-alive agent in lib/lms.js caps sockets at 8 anyway).
const INDEX_FETCH_CONCURRENCY = 4;

async function fetchAlbumPages(total) {
  const starts = [];
  for (let start = 0; start < total; start += INDEX_PAGE) starts.push(start);
  const pages = new Array(starts.length);
  let next = 0, done = 0;
  async function worker() {
    for (;;) {
      const i = next++;
      if (i >= starts.length) return;
      const { albums } = await state.lms.listAlbums({ start: starts[i], count: INDEX_PAGE });
      pages[i] = albums || [];
      done++;
      indexProgress = starts.length ? Math.min(1, done / starts.length) : 1;
    }
  }
  await Promise.all(Array.from({ length: Math.min(INDEX_FETCH_CONCURRENCY, starts.length) }, worker));
  // Concatenate in PAGE ORDER, not completion order — `offset` is a position in
  // this list and is the client's album identity, so it must be deterministic.
  const rows = [];
  for (const page of pages) { if (page) rows.push(...page); }
  return rows;
}

async function buildIndex() {
  if (!state.lms) throw new Error("Not connected to LMS");
  indexProgress = 0;
  const total = await state.lms.countAlbums();
  const sig = indexCacheSig(total);
  let rows = readIndexCache(sig);
  const fromCache = !!rows;
  if (fromCache) {
    indexProgress = 1;
    if (DEBUG) console.log("[index] reusing cached rows (" + rows.length + " albums, sig " + sig + ")");
  } else {
    rows = await fetchAlbumPages(total);
    writeIndexCache(sig, rows);
  }
  // Layer owner edits (title/artist/year/artwork overrides) and any
  // previously-rescued artwork onto the raw LMS rows before indexing — both
  // live in the app's own database; the files/LMS are untouched.
  for (const row of rows) {
    albumEdits.applyToRow(row);
    if (!row.coverId) {
      const stored = albumArt.storedFor(row.origTitle || row.title, row.origArtist || row.subtitle);
      if (stored) row.coverId = stored;
    }
  }
  // Collapse merged multi-disc sets. Must run after the edit/art layering
  // above (so a renamed part still matches its stored key) and before
  // loadRecords, which mints the offsets and byId/byOffset maps from whatever
  // row set it is handed.
  rows = albumMerges.apply(rows);
  search.loadRecords(index, rows);
  // Remember which scan this index reflects, so a later rescan is noticed even
  // when it was started outside the app.
  indexScanStamp = (state.server && state.server.lastScan) != null ? String(state.server.lastScan) : null;
  indexProgress = 1;
  // "Date added" is derived from the TRACK table (LMS exposes no album-level
  // added time, and sort:new is capped at browseagelimit ~100 albums, so it
  // can't drive a full-library sort). That sweep is a second pass over the
  // whole track table, so it runs in the BACKGROUND and patches records in
  // place — the Library is usable immediately and simply lacks that one sort
  // until it lands. Cached with the index so a restart doesn't repeat it.
  applyAddedTimes(sig, rows.length);
  if (DEBUG) console.log("[index] built", index.records.length, "albums" + (fromCache ? " (from cache)" : ""));
  // Background sweep: fetch + store covers for local albums that still have
  // none. Best-effort, rate-limited (MusicBrainz), mutates records in place so
  // tiles/modals pick the new art up on their next fetch. Never blocks build.
  albumArt.sweep(index.records, (rec, key) => { rec.image_key = key; })
    .catch(e => { if (DEBUG) console.error("[albumart] sweep:", e.message); });
  // Labels ride on the album index: re-project cached labels onto the fresh
  // offsets (fast, no network), then kick the background scan to fill in any
  // albums we haven't looked up yet. Both are best-effort — a labels failure
  // must never break the core library index.
  try { labels.onAlbumIndexRebuilt(); } catch (e) { if (DEBUG) console.error("[labels] reseed:", e.message); }
  labels.runScan().catch(e => { if (DEBUG) console.error("[labels] scan:", e.message); });
  return index;
}

const ADDED_CACHE_FILE = path.join(DATA_DIR, "added-times.json");
let addedSweeping = false;

function readAddedCache(sig) {
  if (!sig) return null;
  try {
    const j = JSON.parse(fs.readFileSync(ADDED_CACHE_FILE, "utf8"));
    if (!j || j.sig !== sig || !j.added) return null;
    // `format` arrived later than `added`; a cache written by an older build has
    // no formats and simply re-sweeps for them rather than being thrown away.
    return { added: new Map(Object.entries(j.added)),
             format: j.format ? new Map(Object.entries(j.format)) : null };
  } catch (e) { return null; }
}
function writeAddedCache(sig, added, format) {
  if (!sig || !added.size) return;
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = ADDED_CACHE_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify({ sig, at: Date.now(),
      added: Object.fromEntries(added),
      format: format ? Object.fromEntries(format) : undefined }));
    fs.renameSync(tmp, ADDED_CACHE_FILE);
  } catch (e) { if (DEBUG) console.error("[index] added-time cache write failed:", e.message); }
}

// ---- album quality, derived from the album's first track ------------------
//
// LMS reports format per TRACK; there is no album-level tag. The values ride in
// on the added-time sweep (one pass over the track table, not two).

// Lossless containers. Everything else describes a DECODER rather than the
// recording — an MP3 reports 16-bit/44.1k because that is what it decodes to,
// and printing "16/44.1" on it would claim CD quality for a lossy file.
const LOSSLESS_TYPES = new Set(["flc", "flac", "alc", "alac", "aif", "aiff", "wav",
                                "wvp", "wv", "ape", "mpc", "dsf", "dff", "ogf"]);
const QUALITY_TYPE_LABELS = { flc: "FLAC", flac: "FLAC", alc: "ALAC", alac: "ALAC",
                              aif: "AIFF", aiff: "AIFF", wav: "WAV", dsf: "DSD", dff: "DSD",
                              mp3: "MP3", mp4: "AAC", aac: "AAC", ogg: "OGG", ops: "Opus",
                              wma: "WMA", wv: "WavPack", ape: "APE" };

function rateShort(hz) {
  if (!hz) return null;
  const k = hz / 1000;
  return String(Number.isInteger(k) ? k : Math.round(k * 10) / 10);
}

function albumQualityLabel(f) {
  if (!f) return null;
  const t = String(f.type || "").toLowerCase();
  const label = QUALITY_TYPE_LABELS[t] || (t ? t.toUpperCase() : null);
  // Lossy first, for the reason above.
  if (t && !LOSSLESS_TYPES.has(t)) return label;
  if (f.bits && f.rate) return f.bits + "/" + rateShort(f.rate);
  if (f.rate) return rateShort(f.rate) + " kHz";
  return label;
}

// Better than CD. There is no room to say so in words, so the badge takes the
// accent colour instead.
function albumIsHiRes(f) {
  if (!f) return false;
  const t = String(f.type || "").toLowerCase();
  if (t && !LOSSLESS_TYPES.has(t)) return false;
  return !!((f.bits && f.bits > 16) || (f.rate && f.rate > 48000));
}

function stampAlbumFormats(map) {
  let hit = 0;
  for (const rec of index.records) {
    // A merged album takes its primary part's format — the parts are discs of
    // one release and share it.
    const f = map.get(String(rec.id));
    if (!f) continue;
    const q = albumQualityLabel(f);
    if (!q) continue;
    rec.quality = q;
    rec.hires = albumIsHiRes(f);
    // The raw fields are kept, not just the derived badge string: the Format /
    // Sample rate / Bit depth facets need them separately, and re-deriving them
    // by parsing `quality` back apart would be a lossy round trip (a lossy
    // container deliberately prints no depth at all).
    rec.fmtType = f.type ? String(f.type).toLowerCase() : null;
    rec.fmtRate = f.rate || null;
    rec.fmtBits = f.bits || null;
    hit++;
  }
  return hit;
}

function stampAddedTimes(map) {
  let hit = 0;
  for (const rec of index.records) {
    const at = map.get(String(rec.id));
    if (at != null) { rec.addedAt = at; hit++; }
  }
  // The sorted-view memo is keyed partly on index.builtAt; nudge it so views
  // built before the sweep landed don't serve a stale "date added" ordering.
  if (hit) { index.builtAt = Date.now(); libraryViewCache.clear(); }
  return hit;
}

async function applyAddedTimes(sig, albumCount) {
  const cached = readAddedCache(sig);
  // Only a cache carrying BOTH is a complete hit; one written before formats
  // existed still supplies the added times while the sweep re-runs for the rest.
  if (cached) {
    stampAddedTimes(cached.added);
    if (cached.format) { stampAlbumFormats(cached.format); return; }
  }
  if (addedSweeping || !state.lms) return;
  addedSweeping = true;
  try {
    const { added, format } = await state.lms.albumAddedTimes();
    const hit = stampAddedTimes(added);
    const qhit = stampAlbumFormats(format);
    writeAddedCache(sig, added, format);
    if (DEBUG) console.log("[index] added times for", hit, "and formats for", qhit,
                           "of", albumCount, "albums");
  } catch (e) {
    // Always warn, not just under DEBUG: a failed sweep means the "Date added"
    // sort silently has nothing to sort by, which is exactly the kind of thing
    // that hides behind a quiet catch.
    console.warn("[index] added-time sweep failed — Date added sort unavailable:", e.message);
  } finally { addedSweeping = false; }
}

// The lastScan value the current index was built from. Nothing else watched
// this, so a rescan started from LMS's own web UI (or a scheduled scan) left
// the app serving a pre-scan index for up to INDEX_MAX_AGE_MS — 12 hours. The
// in-app rescan happened to be fine only because the client pokes /api/reindex
// when it sees the scan finish, which needs the page to be open.
let indexScanStamp = null;
function scanChangedSinceIndex() {
  const scan = state.server && state.server.lastScan;
  if (scan == null || indexScanStamp == null) return false;
  return String(scan) !== String(indexScanStamp);
}

function ensureIndex() {
  // Keyed off builtAt (set by loadRecords), NOT records.length — a genuinely
  // empty/still-scanning library has a valid built-but-empty index and must not
  // re-trigger a build (and its countAlbums RPC) on every request.
  const stale = !index.builtAt || (Date.now() - index.builtAt) > INDEX_MAX_AGE_MS
                || scanChangedSinceIndex();
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

// Fetch an artwork URL from LMS as raw bytes. Sends the same HTTP basic auth
// the JSON-RPC adapter uses — on a password-protected LMS, /music/... needs
// it too (without it every cover silently 404s while metadata still works).
function fetchArtwork(url) {
  const headers = {};
  const cfg = state.lms && state.lms.cfg;
  if (cfg && cfg.username) {
    headers.Authorization = "Basic " + Buffer.from(`${cfg.username}:${cfg.password || ""}`).toString("base64");
  }
  return new Promise((resolve, reject) => {
    const req = http.get(url, { headers }, (res) => {
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

// HTTP access log — one line per request with status + elapsed ms. At debug
// only /api/* is logged (static assets are noise); at trace everything is.
// Slow requests (>2s) and 5xx are surfaced at warn regardless of level.
const httpLog = makeLogger("http");
app.use((req, res, next) => {
  const started = Date.now();
  res.on("finish", () => {
    const ms = Date.now() - started;
    const line = [res.statusCode, req.method, req.originalUrl, ms + "ms"];
    if (res.statusCode >= 500 || ms > 2000) httpLog.warn(...line);
    else if (req.path.startsWith("/api/")) httpLog.debug(...line);
    else httpLog.trace(...line);
  });
  next();
});

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
  const o = { offset: rec.offset, title: rec.title || "", subtitle: rec.subtitle || "", image_key: rec.image_key || null, source: rec.source || null, qobuz_id: search.qobuzIdFromExtid(rec.extid),
    merge_id: rec.mergeId || null, part_count: rec.partCount || null };
  // Omitted entirely when unknown, so the badge is never a guess. The client
  // always receives it and hides it with one class — rendering it conditionally
  // would leave every tile already on screen showing its old state until
  // something rebuilt it, making the Appearance toggle look like it had done
  // nothing until you navigated away and back.
  if (rec.quality) { o.quality = rec.quality; if (rec.hires) o.hires = true; }
  return o;
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

    // Decade wall — straight off the in-memory index (records carry the LMS
    // year and their GLOBAL offsets, so play/queue work unchanged).
    if (req.query.filter_type === "decade" && req.query.filter_value) {
      await ensureIndex();
      const start = parseInt(String(req.query.filter_value), 10);   // "1990s" → 1990
      if (!Number.isFinite(start)) return res.json({ albums: [], total: 0, filtered: true });
      const pool = index.records.filter(r => r.year != null && r.year >= start && r.year <= start + 9);
      if (!pool.length) return res.json({ albums: [], total: 0, filtered: true });
      const want = Math.min(count, pool.length);
      const picked = new Set();
      while (picked.size < want) picked.add(Math.floor(Math.random() * pool.length));
      return res.json({ albums: [...picked].map(i => albumOut(pool[i])), total: pool.length, filtered: true });
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

// Tracks for a record. A merged album spans several LMS albums, so its list is
// each part's tracks concatenated in the merge's disc order.
//
// THIS IS THE ONLY PLACE that ordering is decided, deliberately: track identity
// on the client is (offset, array index), and /api/album, /api/play-track and
// /api/play-tracks all resolve through here. If any of them built the array
// differently, index N would mean a different track between listing it and
// tapping it — a silent wrong-track bug.
async function tracksForRecord(rec) {
  if (!rec.partIds || rec.partIds.length < 2) return state.lms.albumTracks(rec.id);
  // Fetched in parallel, then reassembled in part order — never completion order.
  const perPart = await Promise.all(rec.partIds.map(id => state.lms.albumTracks(id).catch(() => [])));
  const out = [];
  perPart.forEach((tracks, i) => {
    for (const t of tracks) out.push({ ...t, _disc: i + 1 });
  });
  return out;
}

// Play or queue a record. A merged album enqueues every part in disc order:
// the first honours the requested mode, the rest append, or each would replace
// the one before.
async function playRecord(zoneId, rec, mode) {
  if (!rec.partIds || rec.partIds.length < 2) return state.lms.playAlbum(zoneId, rec.id, mode);
  let first = true;
  for (const id of rec.partIds) {
    await state.lms.playAlbum(zoneId, id, first ? mode : "queue");
    first = false;
  }
}

// Album detail by offset → LMS album id → tracks.
app.get("/api/album", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const offset = parseInt(req.query.offset, 10);
  if (!Number.isFinite(offset) || offset < 0) return res.status(400).json({ error: "Valid offset query parameter required" });
  const rec = index.byOffset.get(offset);
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  try {
    const tracks = await tracksForRecord(rec);
    res.json({
      album:  { title: rec.title, subtitle: rec.subtitle, image_key: rec.image_key, year: rec.year,
                merge_id: rec.mergeId || null, part_count: rec.partCount || null,
                quality: rec.quality || null, hires: !!rec.hires },
      tracks: tracks.map(t => ({ title: t.title, subtitle: t.artist || "" })),
      actions: [
        { kind: "play_now",  title: "Play Now" },
        { kind: "queue",     title: "Queue" },
        { kind: "play_next", title: "Next" }
      ]
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// The album view's shape for a record, echoing the tile/modal fields plus the
// edited flag so the client can offer "Remove edits".
function albumView(rec) {
  return {
    offset: rec.offset, title: rec.title, subtitle: rec.subtitle,
    year: rec.year, image_key: rec.image_key, source: rec.source, edited: !!rec.edited,
    quality: rec.quality || null, hires: !!rec.hires
  };
}

// ---- album editor: owner metadata/artwork overrides (stored in app DB) ----

// Artwork candidates for an album (external sources) — powers the editor's
// "Find artwork" grid. Does NOT store anything; the client picks one and Save
// downloads it.
app.get("/api/albumart/candidates", async (req, res) => {
  const offset = parseInt(req.query.offset, 10);
  const rec = Number.isFinite(offset) ? index.byOffset.get(offset) : null;
  const title  = String(req.query.title  || (rec && rec.title)    || "").trim();
  const artist = String(req.query.artist || (rec && rec.subtitle) || "").trim();
  if (!title) return res.status(400).json({ error: "title required" });
  try {
    const cands = await withDeadline(
      albumArt.candidates({ title, artist, mbid: rec && rec.mbid }), 30000);
    res.json({ candidates: cands || [] });
  } catch (e) { res.json({ candidates: [] }); }
});

// SSRF guard for owner-supplied artwork URLs, with ONE deliberate exemption:
// the LMS server itself. The Music & Artist Information plugin returns cover
// candidates as URLs on the LMS host, which is nearly always a private address
// — guarding those blindly would block previewing and saving every MAI cover.
// The LMS host isn't caller-chosen, it's the server we are already configured
// to talk to, so it is not an SSRF target. Everything else goes through
// assertPublicUrl unchanged.
function isLmsHostedUrl(url) {
  try {
    const u = new URL(url);
    const cfg = state.lms && state.lms.cfg;
    if (!cfg || !cfg.host) return false;
    const port = u.port || (u.protocol === "https:" ? "443" : "80");
    return u.hostname.toLowerCase() === String(cfg.host).toLowerCase()
        && String(port) === String(cfg.port);
  } catch (e) { return false; }
}
async function assertAllowedArtUrl(url) {
  if (isLmsHostedUrl(url)) return;
  await assertPublicUrl(url);
}

// Server-side preview proxy for a candidate cover (remote sources are often
// CORS-less / hotlink-blocked in the browser).
app.get("/api/albumart/thumb", async (req, res) => {
  const url = String(req.query.url || "");
  if (!/^https?:\/\//i.test(url)) return res.status(400).end();
  try {
    // The caller supplies this URL, so it needs the same SSRF guard as the
    // album-edit art_url and the label logo. Without it this GET was a probe
    // for internal hosts and ports, and relayed the bytes of anything that
    // answered with an image content type.
    await assertAllowedArtUrl(url);
    const { body, type } = await withDeadline(albumArt.thumb(url), 15000);
    res.set("Content-Type", type);
    res.set("Cache-Control", "public, max-age=3600");
    res.send(body);
  } catch (e) { res.status(404).end(); }
});

// Save an owner edit. Body: { offset, title?, artist?, year?, art_url? }.
// Overrides are keyed by the album's ORIGINAL LMS title/artist so they survive
// library rescans. art_url is downloaded + stored; the resulting image key is
// remembered. Returns the updated album view.
app.post("/api/album/edit", async (req, res) => {
  const { offset, title, artist, year, art_url } = req.body || {};
  const rec = Number.isFinite(offset) ? index.byOffset.get(offset) : null;
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  const origTitle  = rec.origTitle  || rec.title;
  const origArtist = rec.origArtist || rec.subtitle;
  try {
    let art;   // undefined = leave artwork override as-is
    if (typeof art_url === "string" && art_url.trim()) {
      // SSRF guard on the owner-supplied cover URL. Exempts the LMS host, so a
      // Music & Artist Information candidate (served BY the LMS, on a private
      // address) can actually be saved — it could not be before.
      await assertAllowedArtUrl(art_url.trim());
      art = await withDeadline(
        albumArt.saveFromUrl(origTitle, origArtist, art_url.trim(), "Manual"), 25000);
    }
    // Empty string clears an override; undefined leaves it unchanged.
    const norm = (v) => (v === undefined ? undefined : (v === null || String(v).trim() === "" ? null : v));
    const yr = year === undefined ? undefined
             : (year === null || year === "" ? null : Number(year));
    // RENAMING A MERGED ALBUM goes to the merge record, never to an album edit.
    // An album edit keys on a raw LMS row and renames it — and a renamed row no
    // longer matches its own merge part, which is how a rename used to split
    // the set back apart and take several re-merges to put right. Year and
    // artwork still layer onto the primary part as usual: they don't touch the
    // string the merge is keyed on.
    const merged = rec.mergeId ? albumMerges.byId(rec.mergeId) : null;
    if (merged) {
      const mr = albumMerges.rename(rec.mergeId,
        title  === undefined ? merged.title  : norm(title),
        artist === undefined ? merged.artist : norm(artist));
      if (mr.ok) { rec.title = mr.merge.title; rec.subtitle = mr.merge.artist; }
    }
    albumEdits.set(origTitle, origArtist, {
      title:  merged ? undefined : norm(title),
      artist: merged ? undefined : norm(artist),
      year:   Number.isNaN(yr) ? undefined : yr,
      art
    });
    // Re-project the edit onto the live index record so the change shows
    // immediately without a full rebuild.
    const edit = albumEdits.get(origTitle, origArtist);
    rec.origTitle = origTitle; rec.origArtist = origArtist;
    if (!merged) {
      rec.title    = (edit && edit.title  != null) ? edit.title  : origTitle;
      rec.subtitle = (edit && edit.artist != null) ? edit.artist : origArtist;
    }
    rec.year     = (edit && edit.year   != null) ? edit.year   : rec.year;
    if (edit && edit.art != null) rec.image_key = edit.art;
    rec.edited = !!edit || !!merged;
    search.reindexRecord(index, rec);
    // Owner edits are durable immediately (not just on the debounce timer).
    albumEdits.flushNow();
    albumArt.flushNow();
    res.json({ album: albumView(rec) });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Remove all overrides for an album, restoring LMS values.
app.delete("/api/album/edit", async (req, res) => {
  const offset = parseInt(req.query.offset, 10);
  const rec = Number.isFinite(offset) ? index.byOffset.get(offset) : null;
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  const origTitle  = rec.origTitle  || rec.title;
  const origArtist = rec.origArtist || rec.subtitle;
  albumEdits.remove(origTitle, origArtist);
  albumEdits.flushNow();
  rec.title = origTitle; rec.subtitle = origArtist; rec.edited = false;
  // A merged album's name lives on the merge, so "remove edits" has to reset
  // that too — otherwise the rename would survive a restore that claims to
  // undo everything. Passing null re-derives it from the primary part.
  if (rec.mergeId) {
    const mr = albumMerges.rename(rec.mergeId, null, null);
    if (mr.ok) { rec.title = mr.merge.title; rec.subtitle = mr.merge.artist; }
  }
  if (rec.origYear !== undefined) rec.year = rec.origYear;
  // Restore artwork: the real LMS cover if the album had one, else whatever
  // the background sweep rescued, else nothing.
  const stored = albumArt.storedFor(origTitle, origArtist);
  rec.image_key = rec.origImageKey || stored || null;
  search.reindexRecord(index, rec);
  res.json({ album: albumView(rec) });
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
  // Rescued/owner-set artwork stored in the app's own database. These keys are
  // content-addressed (a new cover mints a new key), so serving them immutable
  // is safe. Served straight from disk — no LMS round-trip.
  if (String(req.params.image_key).startsWith("art-")) {
    const stored = albumArt.read(req.params.image_key);
    if (!stored) return res.status(404).end();
    imgPut(key, { body: stored.body, type: stored.type, bytes: stored.body.length });
    res.set("Content-Type", stored.type);
    res.set("Cache-Control", "public, max-age=604800, immutable");
    return res.send(stored.body);
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
  try { await playRecord(zone_or_output_id, rec, mode); res.json({ ok: true }); }
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
    let first = true, queued = 0, failed = 0;
    for (const off of offsets) {
      const rec = index.byOffset.get(off);
      // An offset that no longer resolves (a rescan mid-selection) is COUNTED,
      // not silently skipped: answering a bare ok for a 40-album selection that
      // only queued 37 is a lie the UI can't detect.
      if (!rec) { failed++; continue; }
      try {
        await playRecord(zone_or_output_id, rec, first ? mode : "queue");
        queued++; first = false;
      } catch (e) { failed++; log.debug("play-multi:", off, e.message); }
    }
    res.json({ ok: queued > 0, queued, failed, total: offsets.length });
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
    const tracks = await tracksForRecord(rec);
    const t = tracks[track];
    if (!t) return res.status(409).json({ error: "Track index out of range (library changed?)" });
    await state.lms.playTracks(zone_or_output_id, [t.id], mode);
    res.json({ ok: true, action: kind, track: t.title });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Play/queue SEVERAL tracks from one album in a single call.
// body { offset, tracks: [index], zone_or_output_id, kind }
//
// Track identity on the client is (album offset, array index) — LMS track ids
// are never sent to the browser — so, exactly as /api/play-track does, the
// album is re-read here and the indices are resolved positionally. The order
// LMS receives is the order asked for (playlistcontrol keeps track_id order),
// and indices that no longer resolve are skipped rather than failing the whole
// batch: a rescan between opening the modal and hitting Play shouldn't lose the
// tracks that are still valid. Only a batch where NOTHING resolves is an error.
app.post("/api/play-tracks", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { offset, tracks: idxs, zone_or_output_id, kind } = req.body || {};
  if (!Number.isFinite(offset)) return res.status(400).json({ error: "offset required" });
  if (!Array.isArray(idxs) || !idxs.length) return res.status(400).json({ error: "tracks required" });
  if (!idxs.every(i => Number.isInteger(i) && i >= 0)) return res.status(400).json({ error: "track indices must be non-negative integers" });
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const mode = KIND_TO_MODE[kind];
  if (!mode) return res.status(400).json({ error: "kind must be play_now, queue or play_next" });
  const rec = index.byOffset.get(offset);
  if (!rec) return res.status(404).json({ error: "Unknown album offset" });
  try {
    const tracks = await tracksForRecord(rec);
    const ids = [];
    let missing = 0;
    for (const i of idxs) {
      const t = tracks[i];
      if (t) ids.push(t.id); else missing++;
    }
    if (!ids.length) return res.status(409).json({ error: "Those tracks are no longer in this album (library changed?)" });
    await state.lms.playTracks(zone_or_output_id, ids, mode);
    res.json({ ok: true, action: kind, played: ids.length, missing });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Stored playlists. These are LMS's SAVED playlists, NOT the live player queue.
//
// The one awkward bit: Lyrion has no bulk "add these tracks to this playlist"
// command. `playlists edit cmd:add` appends ONE track, addressed by title+url,
// so adding a selection is N sequential calls. The alternative (fill a player's
// queue with playlistcontrol, then `playlist save`) would clobber whatever is
// currently playing, so it isn't used — a slower append that never disturbs
// playback is the right trade here.
// ---------------------------------------------------------------------------
// Cover mosaics for stored playlists. A playlist has no artwork of its own, so
// each tile borrows the first four DISTINCT album covers its tracks come from.
// That costs one extra LMS call per playlist, so it's cached by id.
//
// The cache MUST be dropped for a playlist we write to. It used to claim in a
// comment that it was keyed on the track count as well, but it was not — so a
// probe that ran while a playlist was still EMPTY (exactly the state a
// just-created one is in, and the state the add-to-playlist sheet's own listing
// can observe) pinned "0 tracks" and a blank mosaic on the tile for the whole
// TTL, while opening that same playlist showed its tracks. Every write path
// now calls forgetPlaylistArt().
const playlistArtCache = new Map();   // id -> { art, tracks, at }
const PLAYLIST_ART_TTL_MS = 10 * 60 * 1000;
const PLAYLIST_ART_CONCURRENCY = 3;

// Find a library record by album name (+ artist when we have one). Used where
// LMS hands back a title but no id — playlist rows, share imports.
function findRecordByName(title, artist) {
  const nT = search.normalize(String(title || ""));
  if (!nT) return null;
  const nA = search.normalize(String(artist || ""));
  const hits = index.records.filter(r => r.nTitle === nT);
  if (!hits.length) return null;
  if (nA) { const exact = hits.find(r => r.nArtist === nA); if (exact) return exact; }
  // Exactly one album with that title is unambiguous; more than one without an
  // artist to separate them is a coin toss, so don't guess.
  return hits.length === 1 ? hits[0] : null;
}

// Bumped by forgetPlaylistArt so a probe already in flight cannot write its
// now-stale answer back after the invalidation.
let playlistArtGen = 0;

async function playlistArtFor(id) {
  const gen = playlistArtGen;
  const hit = playlistArtCache.get(String(id));
  if (hit && (Date.now() - hit.at) < PLAYLIST_ART_TTL_MS) return hit;
  const { art, albums, total } = await state.lms.playlistArt(id);
  // Fill any cover LMS didn't give us from our own index. A Qobuz playlist's
  // tracks often come back with no cover id on this query, which left every
  // tile blank; the library holds a good image key for anything we own, and
  // matching is the same normalised title+artist used everywhere else.
  const filled = [];
  for (const a of (albums || [])) {
    if (a.cover) { filled.push(a.cover); continue; }
    const rec = a.album ? findRecordByName(a.album, a.artist) : null;
    // Push null rather than skipping, so the list stays in probe order and the
    // caller can tell "no cover for this album" from "no album here".
    filled.push(rec && rec.image_key ? rec.image_key : null);
  }
  // `filled` is every distinct album in probe order with its cover resolved —
  // take the first four that actually HAVE one, so a run of coverless albums
  // doesn't cost the mosaic its slots.
  const covers = filled.filter(Boolean).slice(0, 4);
  const rec = { art: covers.length ? covers : art.slice(0, 4), tracks: total, at: Date.now() };
  // NEVER cache an empty playlist. Emptiness is the transient state — a
  // playlist between "created" and "its tracks appended" — and it is the one
  // state that makes the tile lie. It is also the cheapest thing to re-probe.
  // This is the defence that does NOT depend on our knowing about the write:
  // LMS's own web UI, the Material skin and the Qobuz plugin all fill playlists
  // without telling us, so forgetPlaylistArt() alone would still leave those
  // pinned at "0 tracks" for the whole TTL.
  //
  // The generation check covers the other race: this `set` runs after an await,
  // so a listing that started before an invalidation could otherwise write its
  // stale answer back on top of it.
  if (total > 0 && gen === playlistArtGen) playlistArtCache.set(String(id), rec);
  return rec;
}

// Drop a playlist's cached tile so the next listing re-probes it. Called after
// anything that changes what the playlist holds.
function forgetPlaylistArt(id) {
  playlistArtGen++;
  if (id == null) playlistArtCache.clear();
  else playlistArtCache.delete(String(id));
}

// ---------------------------------------------------------------------------
// Album merges (multi-disc sets LMS split apart)
// ---------------------------------------------------------------------------

// Rebuild the index so a merge change takes effect everywhere at once. The
// collapse happens inside buildIndex, so there's no partial state to patch.
async function reindexAfterMergeChange() {
  // Let any build already in flight finish first: it read the OLD merge file,
  // so joining it would answer ok:true while the albums were still merged, and
  // nothing would rebuild again until the 12h staleness.
  if (indexBuilding) await indexBuilding.catch(() => {});
  index.builtAt = 0;
  libraryViewCache.clear();
  await ensureIndex();
}

app.get("/api/albums/merges", async (req, res) => {
  try {
    const rows = albumMerges.list();
    // Resolve each merge to its live album so the screen can show the cover and
    // link to it; a merge whose albums have left the library still lists, so it
    // can be undone rather than becoming unreachable.
    let byKey = null;
    if (state.connected) {
      try {
        await ensureIndex();
        byKey = new Map();
        for (const rec of index.records) if (rec.mergeId) byKey.set(rec.mergeId, rec);
      } catch (e) { byKey = null; }
    }
    res.json({
      total: rows.length,
      merges: rows.map(m => {
        const live = byKey && byKey.get(m.id);
        return {
          id: m.id, title: m.title, artist: m.artist, at: m.at,
          parts: m.parts.map(p => ({ title: p.title, artist: p.artist })),
          part_count: m.parts.length,
          offset: live ? live.offset : null,
          image_key: live ? live.image_key : null,
          present: live ? (live.partCount || 0) : 0,
        };
      }),
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// body { items: [{offset, title, subtitle}] } — items[0] is the primary and
// supplies the merged album's title (minus any disc marker) and artist.
//
// The client can only send what it displays, and a display title is not a
// durable identity: it changes when the owner renames the album, and for an
// already-merged album it is a synthesised name no LMS row ever had. So every
// item is resolved back to its index record here, and the merge is keyed on
// `origTitle`/`origArtist` — the album's real LMS name. An item that is itself
// a merge expands into that merge's existing parts, so merging a set with one
// more disc keeps the discs already absorbed.
function resolveMergeItems(items) {
  const out = [];
  // If the FIRST item is already a merge, the set keeps the name the owner
  // gave it rather than reverting to the primary disc's title.
  let title = null, artist = null;
  for (const it of items || []) {
    const rec = it && Number.isFinite(it.offset) ? index.byOffset.get(it.offset) : null;
    if (rec && rec.mergeId) {
      const m = albumMerges.byId(rec.mergeId);
      if (m && Array.isArray(m.parts) && m.parts.length) {
        // Whichever selected item is the merge supplies the name — not only
        // items[0]. Picking a loose disc first used to rename the whole set to
        // that disc's title ("The Wall" becoming "The Wall (Disc 3)").
        if (title == null) { title = m.title || null; artist = m.artist || null; }
        for (const p of m.parts) {
          out.push({ title: p.title, artist: p.artist, id: p.id || null,
                     origTitle: p.origTitle || p.title, origArtist: p.origArtist || p.artist });
        }
        continue;
      }
    }
    if (rec) {
      out.push({ title: rec.title, artist: rec.subtitle,
                 // The LMS album id is a second handle for rescan repair. For a
                 // merged record take the primary part's id, not the record's.
                 id: (rec.partIds && rec.partIds[0]) || rec.id || null,
                 origTitle: rec.origTitle || rec.title, origArtist: rec.origArtist || rec.subtitle });
      continue;
    }
    // No offset (or a stale one): fall back to what was sent. Still better than
    // refusing the merge outright.
    if (it && it.title) out.push({ title: it.title, artist: it.subtitle || it.artist || "" });
  }
  return { items: out, title, artist };
}

app.post("/api/albums/merge", async (req, res) => {
  const items = (req.body || {}).items;
  if (!Array.isArray(items) || items.length < 2) return res.status(400).json({ error: "Pick at least two albums to merge" });
  try {
    if (state.connected) { try { await ensureIndex(); } catch (e) { /* fall back to sent titles */ } }
    const resolved = resolveMergeItems(items);
    const r = albumMerges.merge(resolved.items, { title: resolved.title, artist: resolved.artist });
    if (!r.ok) return res.status(400).json(r);
    await reindexAfterMergeChange();
    res.json({ ok: true, merge: r.merge });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.delete("/api/albums/merge/:id", async (req, res) => {
  try {
    const r = albumMerges.unmerge(req.params.id);
    if (!r.ok) return res.status(404).json(r);
    await reindexAfterMergeChange();
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Favourites (this app's own collection)
// ---------------------------------------------------------------------------

// The full collection, newest first. Library albums are re-resolved to a CURRENT
// offset by title+artist so tapping one opens the right album even after a
// rescan moved it; an album that has since left the library (or was only ever a
// catalogue album) still lists, just without an offset.
app.get("/api/favourites", async (req, res) => {
  try {
    const rows = favourites.list();
    let byKey = null;
    if (state.connected) {
      try {
        await ensureIndex();
        byKey = new Map();
        for (const rec of index.records) {
          const k = makeFavourites.keyFor(rec.title, rec.subtitle);
          if (k && !byKey.has(k)) byKey.set(k, rec);
        }
      } catch (e) { byKey = null; }   // index unavailable — list without offsets
    }
    res.json({
      total: rows.length,
      albums: rows.map(r => {
        const live = byKey && byKey.get(r.key);
        return {
          key: r.key,
          title: r.title,
          subtitle: r.artist || "",
          // Prefer the live record's art: a rescued cover found since
          // favouriting should show, rather than the key stored at the time.
          image_key: (live && live.image_key) || r.image_key || null,
          source: r.source || (live && live.source) || null,
          qobuz_id: r.qobuz_id || null,
          offset: live ? live.offset : null,   // null = not currently in the library
          at: r.at,
        };
      }),
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Just the keys, so a grid can mark which tiles are favourited without
// shipping the whole collection.
app.get("/api/favourites/keys", (req, res) => {
  try { res.json({ keys: [...favourites.keys()] }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// Toggle one album. Body carries the album as the client knows it — title and
// artist are the identity, everything else is stored as context.
app.post("/api/favourites/toggle", (req, res) => {
  const b = req.body || {};
  const title = String(b.title || "").trim();
  if (!title) return res.status(400).json({ error: "title required" });
  try {
    const on = favourites.toggle({
      title,
      artist: b.subtitle || b.artist || "",
      source: b.source || null,
      image_key: b.image_key || null,
      qobuz_id: b.qobuz_id || null,
      extid: b.extid || null,
    }, b.favourite);
    res.json({ ok: true, favourite: on, total: favourites.count() });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Favourite SEVERAL albums at once (the multi-select bar). Always adds rather
// than toggling: a mixed selection should end up all-favourited, not flipped
// item by item into an unpredictable state.
app.post("/api/favourites/add-multi", async (req, res) => {
  const items = (req.body || {}).items;
  if (!Array.isArray(items) || !items.length) return res.status(400).json({ error: "items required" });
  try {
    let added = 0, skipped = 0;
    for (const it of items) {
      const rec = favourites.add({
        title: it.title, artist: it.subtitle || it.artist || "",
        source: it.source || null, image_key: it.image_key || null,
        qobuz_id: it.qobuz_id || null, extid: it.extid || null,
      });
      if (rec) added++; else skipped++;
    }
    res.json({ ok: true, added, skipped, total: favourites.count() });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// The mirror of add-multi: UN-favourite several at once. Offered only when
// every selected album is ALREADY a favourite, so like add-multi it is a
// one-way move and never flips a mixed selection into a state nobody chose.
app.post("/api/favourites/remove-multi", async (req, res) => {
  const items = (req.body || {}).items;
  if (!Array.isArray(items) || !items.length) return res.status(400).json({ error: "items required" });
  try {
    let removed = 0, skipped = 0;
    for (const it of items) {
      if (favourites.remove(it.title, it.subtitle || it.artist || "")) removed++;
      else skipped++;
    }
    res.json({ ok: true, removed, skipped, total: favourites.count() });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get("/api/playlists", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    const r = await state.lms.playlists({ search: req.query.q || undefined });
    const rows = r.playlists || [];
    // Bounded concurrency: a library with many playlists shouldn't open one
    // LMS request per playlist all at once.
    const out = new Array(rows.length);
    let next = 0;
    const worker = async () => {
      for (;;) {
        const i = next++;
        if (i >= rows.length) return;
        const pl = rows[i];
        try {
          const { art, tracks } = await playlistArtFor(pl.id);
          out[i] = { ...pl, art, tracks };
        } catch (e) {
          out[i] = { ...pl, art: [], tracks: null };   // art is decoration, never fatal
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(PLAYLIST_ART_CONCURRENCY, rows.length) }, worker));
    res.json({ total: r.total, playlists: out });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get("/api/playlist/tracks", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const id = req.query.playlist_id;
  if (!id) return res.status(400).json({ error: "playlist_id required" });
  try {
    const r = await state.lms.playlistTracks(id);
    res.json({
      total: r.total,
      tracks: r.tracks.map((t, i) => ({
        index: i, title: t.title, subtitle: t.artist || "",
        album: t.album || "", duration: t.duration, image_key: t.coverId || null,
      })),
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// LMS answers a status-error (bad config, bad params, unknown verb) by CLOSING
// THE SOCKET rather than returning an error payload, so the app sees a bare
// "socket hang up" / ECONNRESET with nothing to say what went wrong. For
// playlist writes the overwhelmingly likely cause is a server with no Playlists
// folder configured, so say that instead of showing the user a transport error
// they can do nothing with.
function playlistWriteError(e) {
  const m = String((e && e.message) || e || "");
  if (/socket hang up|ECONNRESET|aborted/i.test(m)) {
    return "The server rejected the playlist command and closed the connection. " +
           "This usually means it has no Playlists folder set — check LMS Settings → " +
           "Basic Settings → Playlists folder, and that the folder is writable.";
  }
  return m;
}

app.post("/api/playlists/create", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const name = String((req.body && req.body.name) || "").trim();
  if (!name) return res.status(400).json({ error: "name required" });
  try {
    const r = await state.lms.playlistCreate(name);
    forgetPlaylistArt(r && r.id);
    // created:false means the name already existed and LMS created nothing —
    // report it honestly so the client can say "added to the existing one".
    res.json(r);
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/playlists/delete", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const id = req.body && req.body.playlist_id;
  if (!id) return res.status(400).json({ error: "playlist_id required" });
  try { await state.lms.playlistDelete(id); forgetPlaylistArt(id); res.json({ ok: true }); }
  catch (e) {
    // An unknown id makes LMS set a status error, which it answers by closing
    // the socket — so without this the user would see "socket hang up".
    log.warn("playlist delete failed:", e.message);
    res.status(500).json({ error: playlistWriteError(e) });
  }
});

app.post("/api/playlists/rename", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { playlist_id, name } = req.body || {};
  const nm = String(name || "").trim();
  if (!playlist_id) return res.status(400).json({ error: "playlist_id required" });
  if (!nm) return res.status(400).json({ error: "name required" });
  try { await state.lms.playlistRename(playlist_id, nm); forgetPlaylistArt(playlist_id); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// Add a selection to a playlist. Accepts EITHER whole albums (offsets) or
// specific tracks of one album (offset + track indices) — the two things the
// UI can have selected — and resolves both to a flat, ordered track list.
// body { playlist_id? , name? , offsets?: [], offset?, tracks?: [idx] }
// Exactly one of playlist_id / name: name creates (or reuses) a playlist first.
app.post("/api/playlists/add", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { playlist_id, name, offsets, offset, tracks: trackIdxs } = req.body || {};
  const wantName = String(name || "").trim();
  if (!playlist_id && !wantName) return res.status(400).json({ error: "playlist_id or name required" });
  try {
    await ensureIndex();
    // Resolve the selection to concrete tracks, preserving the order asked for.
    const picked = [];
    // Through tracksForRecord(), NOT albumTracks() — a merged multi-disc album
    // has to walk every part in disc order. albumTracks(rec.id) returns only the
    // PRIMARY part, so on a merge the client's indices (which came from
    // /api/album, i.e. from tracksForRecord) meant different tracks here: disc-2
    // picks fell off the end and were silently dropped, and if every pick was on
    // a later disc the whole request 409'd with "Nothing to add". Track identity
    // is (offset, array index) and only one function may decide that ordering.
    if (Array.isArray(offsets) && offsets.length) {
      for (const off of offsets) {
        const rec = index.byOffset.get(off);
        if (!rec) continue;
        const ts = await tracksForRecord(rec);
        for (const t of ts) picked.push(t);
      }
    } else if (Number.isFinite(offset) && Array.isArray(trackIdxs) && trackIdxs.length) {
      const rec = index.byOffset.get(offset);
      if (!rec) return res.status(404).json({ error: "Unknown album offset" });
      const ts = await tracksForRecord(rec);
      for (const i of trackIdxs) { if (ts[i]) picked.push(ts[i]); }
    } else {
      return res.status(400).json({ error: "offsets, or offset + tracks, required" });
    }
    if (!picked.length) return res.status(409).json({ error: "Nothing to add (library changed?)" });

    let id = playlist_id, created = false;
    if (!id) {
      const made = await state.lms.playlistCreate(wantName);
      id = made.id; created = made.created;
    }
    // No bulk add exists — append one at a time, in order. A track LMS gave no
    // URL for can't be added; count those rather than aborting the whole batch.
    let added = 0, skipped = 0;
    for (const t of picked) {
      if (!t.url) { skipped++; continue; }
      try { await state.lms.playlistAddTrack(id, { title: t.title, url: t.url }); added++; }
      // WARN, not debug: this is the failure the user actually sees, and at the
      // default level there was no server-side trace of it at all.
      catch (e) { skipped++; log.warn("playlist add failed for", t.title, "-", e.message); }
    }
    if (!added) {
      log.warn("playlist add: every track failed for playlist", id);
      return res.status(500).json({ error: "Could not add any of those tracks" });
    }
    // The tile's cover mosaic and track count come from a cached probe; a
    // playlist we just wrote to must be re-probed or the tile keeps showing
    // what it held before (for a brand-new playlist, "0 tracks" and no art).
    forgetPlaylistArt(id);
    res.json({ ok: true, playlist_id: String(id), created, added, skipped });
  } catch (e) {
    log.warn("playlist add failed:", e.message);
    res.status(500).json({ error: playlistWriteError(e) });
  }
});

// ---------------------------------------------------------------------------
// Playlist sharing — the MDRP1 interchange format (lib/share.js).
//
// A share describes MUSIC, not our library: no LMS ids, no offsets, no image
// keys. That is what lets a blob move between this app and the sibling Roon
// build, which reads and writes the same format. Import therefore RESOLVES
// against whatever this library happens to hold, and says plainly what it
// couldn't find.
// ---------------------------------------------------------------------------

// Gather a shareable track list from a stored LMS playlist.
async function shareTracksForPlaylist(playlistId) {
  const r = await state.lms.playlistTracks(playlistId);
  return (r.tracks || []).map((t, i) => ({
    title: t.title, artist: t.artist || "", album: t.album || "",
    track_no: t.tracknum || (i + 1),
    // JSPF durations are milliseconds; LMS gives seconds. The Roon build has no
    // track length to put here at all, so filling it is strictly additive.
    duration_ms: Number.isFinite(t.duration) ? Math.round(t.duration * 1000) : null,
    year: t.year || null,
  }));
}

app.post("/api/share/encode", async (req, res) => {
  const body = req.body || {};
  try {
    let name = String(body.name || "").trim();
    let tracks = Array.isArray(body.tracks) ? body.tracks : null;
    // The client can hand us a track list (the Roon build's shape), or just
    // name a stored playlist and let the server read it — the server has better
    // metadata than a rendered row does.
    if (!tracks && body.playlist_id) {
      if (!state.connected) return notConnected(res);
      tracks = await shareTracksForPlaylist(body.playlist_id);
    }
    if (!tracks || !tracks.length) return res.status(400).json({ error: "tracks required" });
    if (tracks.length > share.INPUT_MAX) {
      return res.status(400).json({ error: `Too many entries — ${share.INPUT_MAX} at most` });
    }
    const built = share.buildShareDoc({ name, annotation: body.annotation }, tracks, pkg.version);
    if (!built.track_count) return res.status(400).json({ error: "None of those tracks had a title to share" });
    const blob = share.encodeSharePayload(built.doc);
    res.json({
      blob,
      bytes: Buffer.byteLength(blob, "utf8"),
      track_count: built.track_count,
      skipped: built.skipped,
      truncated: built.truncated,
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Resolve a decoded share against the library. ALBUM-level only and entirely
// in-memory: no LMS round-trips, so a big paste answers immediately. The track
// itself is matched by title when the playlist is actually saved, because that
// is the only point we need its URL.
function resolveSharedEntry(entry) {
  const title = share.shareText(entry && entry.title, share.TEXT_MAX);
  if (!title) return null;
  const album  = share.shareText(entry && entry.album, share.TEXT_MAX);
  const artist = share.shareText(entry && entry.creator, share.TEXT_MAX);
  if (!album) return null;    // nothing to open on this server
  // findRecordByName refuses a coin toss: exactly one title match, or an exact
  // artist match, or nothing. A wrong album here would put someone else's track
  // in the playlist under the right name, which is worse than a reported miss.
  let rec = findRecordByName(album, artist);
  if (!rec && artist) {
    // The credit may list several artists ("A & B") where the share names one.
    const key = search.artistKey(artist);
    const hits = index.records.filter(r => r.nTitle === search.normalize(album));
    if (hits.length) {
      const byCredit = hits.filter(r =>
        search.splitArtistNames(r.subtitle || "").some(a => search.artistKey(a) === key));
      if (byCredit.length === 1) rec = byCredit[0];
    }
  }
  if (!rec) return null;
  return {
    offset: rec.offset, title,
    artist, album_title: rec.title, album_subtitle: rec.subtitle,
    image_key: rec.image_key || null,
    track_no: share.shareInt(entry && entry.trackNum, 1, 999),
  };
}

app.post("/api/share/import", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const blob = (req.body || {}).blob;
  if (!blob) return res.status(400).json({ error: "blob required" });
  let doc;
  // The decoder's messages are written for the person holding the blob; pass
  // them through rather than flattening them to "bad request".
  try { doc = share.decodeSharePayload(blob); }
  catch (e) { return res.status(400).json({ error: e.message }); }
  try {
    await ensureIndex();
    const entries = doc.playlist.track.slice(0, share.TRACK_MAX);
    const resolved = [], missing = [];
    for (const e of entries) {
      const hit = resolveSharedEntry(e);
      if (hit) resolved.push(hit);
      // Reported, never silently dropped: "38 of 45" is the honest outcome and
      // the missing 7 are the interesting part.
      else missing.push({
        title:  share.shareText(e && e.title, 200),
        artist: share.shareText(e && e.creator, 200),
        album:  share.shareText(e && e.album, 200),
      });
    }
    res.json({
      ok: true,
      name: share.shareText(doc.playlist.title, 200) || "Shared playlist",
      total: doc.playlist.track.length,
      truncated: doc.playlist.track.length > share.TRACK_MAX,
      resolved, missing,
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Save an import as a real LMS playlist. Separate from /import on purpose:
// importing tells you what would happen, saving is the act. This is where the
// LMS round-trips finally happen, because a stored playlist is addressed by
// track URL and nothing else can supply one.
app.post("/api/share/save", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { name, items } = req.body || {};
  const wantName = String(name || "").trim();
  if (!wantName) return res.status(400).json({ error: "name required" });
  if (!Array.isArray(items) || !items.length) return res.status(400).json({ error: "items required" });
  try {
    await ensureIndex();
    // One LMS read per DISTINCT album, not per track — a shared album usually
    // arrives as a run of consecutive tracks.
    const byAlbum = new Map();
    const picked = [];
    let skipped = 0;
    for (const it of items.slice(0, 500)) {
      const rec = index.byOffset.get(it && it.offset);
      if (!rec) { skipped++; continue; }
      if (!byAlbum.has(rec.offset)) {
        try { byAlbum.set(rec.offset, await tracksForRecord(rec)); }
        catch (e) { byAlbum.set(rec.offset, []); }
      }
      const ts = byAlbum.get(rec.offset);
      const want = search.normalize(String(it.title || ""));
      const hit = ts.find(t => search.normalize(t.title || "") === want);
      if (hit && hit.url) picked.push({ title: hit.title, url: hit.url });
      else skipped++;
    }
    if (!picked.length) return res.status(409).json({ error: "None of those tracks are still in the library" });

    const made = await state.lms.playlistCreate(wantName);
    let added = 0;
    for (const t of picked) {
      try { await state.lms.playlistAddTrack(made.id, t); added++; }
      catch (e) { skipped++; log.warn("share save: add failed for", t.title, "-", e.message); }
    }
    if (!added) return res.status(500).json({ error: "Could not add any of those tracks" });
    forgetPlaylistArt(made.id);
    res.json({ ok: true, playlist_id: String(made.id), created: made.created, name: wantName, added, skipped });
  } catch (e) {
    log.warn("share save failed:", e.message);
    res.status(500).json({ error: playlistWriteError(e) });
  }
});

app.post("/api/playlist/play", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { playlist_id, zone_or_output_id, kind } = req.body || {};
  if (!playlist_id) return res.status(400).json({ error: "playlist_id required" });
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const mode = KIND_TO_MODE[kind];
  if (!mode) return res.status(400).json({ error: "kind must be play_now, queue or play_next" });
  try {
    await state.lms.playPlaylist(zone_or_output_id, playlist_id, mode);
    res.json({ ok: true });
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

// Fixed-volume check (playerpref digitalVolumeControl === "0" — "Output
// level is fixed at 100%"). Cached 60s per player: the transport polls
// every ~1.5s and the pref rarely changes; a Player-settings save clears
// the cache so the slider appears/disappears promptly.
const fixedVolCache = new Map();   // playerId → { at, fixed }
async function isFixedVolume(playerId) {
  const hit = fixedVolCache.get(playerId);
  if (hit && (Date.now() - hit.at) < 60 * 1000) return hit.fixed;
  let fixed = false;
  try {
    const v = await state.lms.getPlayerPref(playerId, "digitalVolumeControl");
    fixed = String(v) === "0";
  } catch (e) { /* unknown → assume adjustable */ }
  fixedVolCache.set(playerId, { at: Date.now(), fixed });
  return fixed;
}

// Live zone state for the mini-transport bar.
// Coalesce concurrent player-status fetches per zone: the phone app and the
// wall display both poll /api/zone-state, often within the same tick — sharing
// one in-flight LMS call (rather than one each) halves that load with zero
// staleness (it's the same live fetch, just not duplicated).
const zoneStatusInflight = new Map();
function playerStatusShared(zoneId) {
  const existing = zoneStatusInflight.get(zoneId);
  if (existing) return existing;
  const p = state.lms.playerStatus(zoneId).finally(() => zoneStatusInflight.delete(zoneId));
  zoneStatusInflight.set(zoneId, p);
  return p;
}

app.get("/api/zone-state", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const zoneId = req.query.zone;
  const player = state.players.find(p => p.id === zoneId);
  if (!player) return res.json({ zone: null });
  let st = state.statuses.get(zoneId);
  try { st = await playerStatusShared(zoneId); state.statuses.set(zoneId, st); }
  catch (e) { /* fall back to the cached status if a live fetch fails */ }
  const t = (st && st.track) || null;
  // A player set to "Output level is fixed at 100%" gets NO volume object —
  // the UI then hides its volume controls entirely (Roon behaviour).
  const fixedVol = await isFixedVolume(zoneId);
  res.json({
    zone: {
      zone_id: player.id,
      display_name: player.name,
      state: st ? (st.mode === "play" ? "playing" : st.mode === "pause" ? "paused" : "stopped") : "stopped",
      // Transport modes, so the Now Playing screen paints the real state and
      // sends a concrete value rather than a blind toggle.
      shuffle: st && Number.isFinite(st.shuffle) ? st.shuffle : 0,
      repeat:  st && Number.isFinite(st.repeat)  ? st.repeat  : 0,
      radio:   radio.isOn(player.id),
      is_play_allowed: true, is_pause_allowed: true, is_next_allowed: true,
      is_previous_allowed: true, is_seek_allowed: true,
      outputs: [{
        output_id: player.id, display_name: player.name,
        is_muted: !!(st && st.muted),
        volume: !fixedVol && st && st.volume != null ? { value: st.volume, min: 0, max: 100, step: 1, soft_limit: 100, type: "number" } : null
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
  // "Unheard": prefer an album not played in the last 6 months (mirrors
  // /api/home/unplayed). Fall back to the whole library if everything has been
  // heard recently, so the Shortcut always plays something.
  const cutoff = Date.now() - 6 * 30 * 24 * 60 * 60 * 1000;
  const heard = playsLog.getPlayedTitlesSince(cutoff);
  const unplayed = index.records.filter(rec => {
    const t = (rec.title || "").toLowerCase().trim();
    return !(t && heard.has(t));
  });
  const from = unplayed.length ? unplayed : index.records;
  const rec = from[Math.floor(Math.random() * from.length)];
  try { await state.lms.playAlbum(zoneId, rec.id, "now"); res.json({ ok: true, album: { title: rec.title, subtitle: rec.subtitle } }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Whole-zone actions, and the Shortcuts / automation triggers.
// ---------------------------------------------------------------------------

// Pause every player that is actually playing. Best-effort per player: one
// unreachable player must not fail the rest, exactly as in the poll loop.
app.post("/api/pause-all", async (req, res) => {
  if (!state.connected) return notConnected(res);
  let paused = 0;
  await Promise.all(state.players.map(async (p) => {
    try {
      // Pause unconditionally: the cached status can be a poll behind, and
      // pausing something already paused is harmless. Skipping on a stale
      // cache would leave a zone playing, which is the one outcome that makes
      // "pause all" useless.
      await state.lms.transport(p.id, "pause");
      paused++;
    } catch (e) { log.debug("pause-all:", p.id, e.message); }
  }));
  res.json({ ok: true, paused });
});

// Mute or unmute every player. `how`: "mute" | "unmute".
app.post("/api/mute-all", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const mute = String((req.body || {}).how || "mute") !== "unmute";
  let changed = 0;
  await Promise.all(state.players.map(async (p) => {
    try { await state.lms.setMute(p.id, mute); changed++; }
    catch (e) { log.debug("mute-all:", p.id, e.message); }
  }));
  res.json({ ok: true, muted: mute, changed });
});

// ---- Random Album Radio -----------------------------------------------------
app.get("/api/radio", (req, res) => {
  const zoneId = req.query.zone;
  res.json({ enabled: zoneId ? radio.isOn(zoneId) : false, zones: radio.list() });
});
app.post("/api/radio", async (req, res) => {
  const zoneId  = (req.body || {}).zone || null;
  const enabled = !!(req.body || {}).enabled;
  if (!zoneId) return res.status(400).json({ error: "zone required" });
  radio.set(zoneId, enabled);
  res.json({ ok: true, enabled });
  // React immediately rather than waiting for the next poll — the owner just
  // asked for music, so a silent player should start now.
  if (enabled) { try { await runRadioFor(zoneId, true); } catch (e) { log.debug("radio kickstart:", e.message); } }
});

// One album at a time, and never two at once for the same player: the poll runs
// every 2.5s and appending twice would double up a whole album.
const radioBusy = new Set();
async function runRadioFor(zoneId, allowPlay) {
  if (radioBusy.has(zoneId)) return;
  if (!state.connected || !radio.isOn(zoneId)) return;
  let st = state.statuses.get(zoneId);
  if (!st) return;
  // Defer to LMS's own queue filler — see lib/radio.js.
  let dstm = false;
  try { dstm = !!(await state.lms.getPlayerPref(zoneId, "dontstopthemusic")); } catch (e) { /* assume off */ }
  const want = radio.decide(st, true, dstm);
  if (!want) return;
  if (want === "play" && !allowPlay && st.mode !== "stop") return;
  await ensureIndex();
  if (!index.records.length) return;
  radioBusy.add(zoneId);
  try {
    const rec = index.records[Math.floor(Math.random() * index.records.length)];
    await playRecord(zoneId, rec, want === "play" ? "now" : "queue");
    log.info("radio:", want, "\u201c" + rec.title + "\u201d on", zoneId);
  } catch (e) {
    log.debug("radio failed:", e.message);
  } finally {
    // Hold the lock briefly past the call so the next poll sees the new queue
    // length rather than the pre-append one.
    setTimeout(() => radioBusy.delete(zoneId), 4000);
  }
}

// ---- Apple Shortcuts / automation: GET so a Shortcut can just fetch a URL ----
// Zones are addressed by DISPLAY NAME because that is what someone types into
// a Shortcut; ids are MAC addresses.
function zoneByName(name) {
  const want = String(name || "").trim().toLowerCase();
  if (!want) return null;
  return state.players.find(p => String(p.name || "").trim().toLowerCase() === want) || null;
}
async function shortcutPlay(req, res, pickUnheard) {
  if (!state.connected) return notConnected(res);
  const p = zoneByName(req.query.zone);
  if (!p) return res.status(404).json({ error: "Unknown zone", zones: state.players.map(x => x.name) });
  await ensureIndex();
  if (!index.records.length) return res.status(503).json({ error: "No albums available" });
  let pool = index.records;
  if (pickUnheard) {
    const cutoff = Date.now() - 6 * 30 * 24 * 60 * 60 * 1000;
    const heard = playsLog.getPlayedTitlesSince(cutoff);
    const unplayed = index.records.filter(rec => {
      const t = (rec.title || "").toLowerCase().trim();
      return !(t && heard.has(t));
    });
    if (unplayed.length) pool = unplayed;
  }
  const rec = pool[Math.floor(Math.random() * pool.length)];
  try {
    await playRecord(p.id, rec, "now");
    res.json({ ok: true, zone: p.name, album: { title: rec.title, subtitle: rec.subtitle } });
  } catch (e) { res.status(500).json({ error: e.message }); }
}
app.get("/api/shortcut/play-random",  (req, res) => shortcutPlay(req, res, false));
app.get("/api/shortcut/play-unheard", (req, res) => shortcutPlay(req, res, true));

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
// Only the prefs this app's Settings screen actually offers. The rescan route
// next door whitelists its modes for the same reason: an unauthenticated POST
// must not be able to reconfigure the LMS server itself (mediadirs, auth, …).
const WRITABLE_SERVER_PREFS = new Set([
  "playtrackalbum", "playlistmode", "defeatDestructiveTouchToPlay",
  "groupdiscs", "variousArtistAutoIdentification", "useBandAsAlbumArtist",
  "ignoredarticles", "browseagelimit", "itemsPerPage",
  "scheduledScan", "autorescan", "rescan-scheduled-time",
]);
app.post("/api/lms/pref/:name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  if (!WRITABLE_SERVER_PREFS.has(req.params.name)) {
    return res.status(403).json({ error: "That server preference isn\u2019t writable from here" });
  }
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
  try {
    await state.lms.setPlayerPref(req.params.id, req.params.name, (req.body || {}).value);
    // Volume-mode changes must reach the transport bar promptly.
    if (req.params.name === "digitalVolumeControl") fixedVolCache.delete(req.params.id);
    res.json({ ok: true });
  }
  catch (e) { res.status(500).json({ error: e.message }); }
});
// Rescan the LMS library. `mode` (from the pane's dropdown) is whitelisted to
// LMS's known rescan sub-commands so nothing arbitrary is forwarded to the CLI:
//   (none)          → ["rescan"]               new & changed files
//   "full"          → ["rescan","full"]        clear + full rescan
//   "playlists"     → ["rescan","playlists"]   playlists only
//   "onlinelibrary" → ["rescan","onlinelibrary"] online-library import only
const RESCAN_MODES = new Set(["full", "playlists", "onlinelibrary"]);
app.post("/api/lms/rescan", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const raw = (req.body || {}).mode;
  const mode = RESCAN_MODES.has(raw) ? raw : null;
  try { await state.lms.rescan(mode); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Native per-player settings (the Settings → Player pane; SETTINGS.md §1/§3).
// One batched read returns everything the pane renders: identity, queue
// modes, the per-player prefs, and sync-group state. Prefs LMS doesn't have
// for this player come back null and the pane hides those controls — that's
// the Material-skin behaviour (tone controls only on hardware that has them).
// ---------------------------------------------------------------------------
// NB: bass/treble deliberately absent — LMS stores default values (50) for
// every player, so a pref probe can't tell whether the hardware actually has
// tone controls (LMS's own UI gates them on client capabilities). Tone/DSP
// is plugin territory; the owner asked for them removed.
const PLAYER_SETTING_PREFS = [
  "transitionType", "transitionDuration", "transitionSmart",
  "replayGainMode", "remoteReplayGain", "digitalVolumeControl",
  "powerOnResume", "fadeInDuration",
  "syncPower", "syncVolume", "maintainSync",
  "maxBitrate", "packetLatency", "startDelay", "playDelay",
  "alarmsEnabled", "alarmDefaultVolume"
];
app.get("/api/lms/player/:id/settings", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const id = req.params.id;
  try {
    const [name, power, modes, groups, dstm, ...prefVals] = await Promise.all([
      state.lms.getPlayerName(id).catch(() => null),
      state.lms.getPower(id).catch(() => null),
      state.lms.getPlayerModes(id).catch(() => ({ shuffle: null, repeat: null })),
      state.lms.syncGroups().catch(() => []),
      state.lms.dstmOptions(id).catch(() => ({ options: [], current: null })),
      ...PLAYER_SETTING_PREFS.map(p => state.lms.getPlayerPref(id, p).catch(() => null))
    ]);
    const prefs = {};
    PLAYER_SETTING_PREFS.forEach((p, i) => {
      const v = prefVals[i];
      prefs[p] = (v === undefined || v === null || v === "") ? null : v;
    });
    const player = state.players.find(p => p.id === id) || null;
    const myGroup = groups.find(g => g.members.includes(id)) || null;
    res.json({
      id,
      name: name != null ? name : (player ? player.name : ""),
      model: player ? player.model : "",
      power,
      modes,
      prefs,
      // Don't Stop The Music: providers with localized names + selection
      // (empty options → plugin disabled → the UI hides the row).
      dstm,
      sync: {
        // The other members of this player's group (empty = not synced).
        members: myGroup ? myGroup.members.filter(m => m !== id) : [],
        // Every other player, as sync candidates.
        others: state.players.filter(p => p.id !== id).map(p => ({ id: p.id, name: p.name }))
      }
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/player/:id/name", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const name = String((req.body || {}).name || "").trim();
  if (!name) return res.status(400).json({ error: "name required" });
  try { await state.lms.setPlayerName(req.params.id, name); res.json({ ok: true, name }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/player/:id/mode", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const b = req.body || {};
  try {
    if (b.shuffle != null) await state.lms.setShuffle(req.params.id, parseInt(b.shuffle, 10) || 0);
    if (b.repeat  != null) await state.lms.setRepeat(req.params.id, parseInt(b.repeat, 10) || 0);
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/player/:id/power", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { await state.lms.setPower(req.params.id, !!(req.body || {}).on); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
// Don't Stop The Music — the LMS-native "keep the music playing" feature
// (replaces this app's old Random album radio toggle). GET lists providers +
// current pick for a zone; POST sets it (provider "0"/null = disabled).
app.get("/api/lms/player/:id/dstm", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { res.json(await state.lms.dstmOptions(req.params.id)); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
app.post("/api/lms/player/:id/dstm", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try { await state.lms.setDstm(req.params.id, (req.body || {}).provider); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
// Sync this player WITH another (join its group), or unsync (with: null).
app.post("/api/lms/player/:id/sync", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const withId = (req.body || {}).with || null;
  try {
    if (withId) await state.lms.syncPlayers(withId, req.params.id);
    else await state.lms.unsync(req.params.id);
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Same-origin proxy for LMS's settings pages. The settings frame used to
// point the browser straight at the LMS origin, but Material's settings
// pages get their theme CSS VARIABLES injected by the Material app when it
// frames them itself — standalone, --popup-background-color & co are
// undefined, so its section-chooser dropdown rendered TRANSPARENT (mods.css:
// `.custom-select-panel { background-color: var(--popup-background-color) }`
// with only --std-popup-background-color defined in :root). A cross-origin
// iframe can't be patched, so the pages are proxied through this app and the
// missing variables (plus a belt-and-braces solid background for the menus)
// are injected into every HTML response. Same-origin framing also fixes the
// loopback-host case (LMS known as 127.0.0.1) and HTTPS mixed content.
// Trust-wise this exposes nothing new: /api already offers full LMS control.
// ---------------------------------------------------------------------------
const LMS_PROXY_PREFIXES = ["/material", "/settings", "/Default", "/DarkLogic", "/html", "/plugins", "/cometd", "/music", "/imageproxy"];
const LMS_EMBED_CSS =
  '<style id="musicd-embed-fix">\n' +
  ':root {\n' +
  '  --popup-background-color: var(--std-popup-background-color, #303030);\n' +
  '  --list-hover-color: rgba(128,128,128,.25);\n' +
  '  --menu-dlg-shadow: 0 4px 24px rgba(0,0,0,.6);\n' +
  '}\n' +
  '.custom-select-panel, .x-menu, .x-menu-list { background-color: var(--popup-background-color, #303030) !important; }\n' +
  // Framed on a phone: vertical scrolling ONLY, and no white ever — a dark
  // page background covers overscroll/short pages, and anything wider than
  // the screen is clamped instead of panning sideways.
  'html { background: #181818 !important; }\n' +
  'html, body { max-width: 100% !important; overflow-x: hidden !important; min-height: 100% !important; }\n' +
  'img, video, iframe, select, input { max-width: 100% !important; }\n' +
  '</style>';
// Settings pages ship without a viewport meta (they expect a desktop browser
// or Material's own frame) — without it a phone lays the page out at desktop
// width and pans sideways. Injected only when the page doesn't set its own.
const LMS_EMBED_VIEWPORT = '<meta name="viewport" content="width=device-width, initial-scale=1">';
function lmsProxy(req, res) {
  if (!state.connected || !state.lms) return res.status(503).send("Not connected to LMS");
  const cfg = state.lms.cfg;
  const headers = { ...req.headers };
  delete headers.host;
  delete headers["accept-encoding"];   // plain bodies so HTML can be patched
  if (cfg.username) {
    headers.authorization = "Basic " + Buffer.from(`${cfg.username}:${cfg.password || ""}`).toString("base64");
  }
  const preq = http.request(
    { host: cfg.host, port: cfg.port, path: req.originalUrl, method: req.method, headers },
    (pres) => {
      const type = String(pres.headers["content-type"] || "");
      if (/text\/html/i.test(type)) {
        const chunks = [];
        pres.on("data", (c) => chunks.push(c));
        pres.on("end", () => {
          let body = Buffer.concat(chunks).toString("utf8");
          let inject = LMS_EMBED_CSS;
          if (!/name=["']viewport["']/i.test(body)) inject = LMS_EMBED_VIEWPORT + inject;
          if (/<\/head>/i.test(body)) body = body.replace(/<\/head>/i, inject + "</head>");
          else body = inject + body;
          const out = { ...pres.headers };
          delete out["content-encoding"];
          delete out["transfer-encoding"];   // body is re-emitted whole — chunked + content-length is invalid
          out["content-length"] = Buffer.byteLength(body);
          res.writeHead(pres.statusCode, out);
          res.end(body);
        });
      } else {
        res.writeHead(pres.statusCode, pres.headers);
        pres.pipe(res);
      }
    }
  );
  preq.on("error", (e) => {
    if (!res.headersSent) res.status(502).send("LMS proxy error: " + e.message);
    else res.destroy();
  });
  preq.setTimeout(30000, () => preq.destroy(new Error("LMS proxy timeout")));
  req.pipe(preq);
}
for (const p of LMS_PROXY_PREFIXES) app.use(p, lmsProxy);

// Artist page header data: photo + bio + band membership. Photo/bio come
// from the LMS Music & Artist Information plugin when installed (its photos
// arrive absolute or as LMS-relative imageproxy paths — the client loads the
// relative ones through this app's LMS proxy), falling back to Qobuz; band
// members / member-of come from MusicBrainz artist relations. Every part is
// best-effort and disk-cached (lib/albuminfo.js).
app.get("/api/artist-info", async (req, res) => {
  const artist = String(req.query.artist || "").trim();
  if (!artist) return res.status(400).json({ error: "artist required" });
  try {
    const [info, bio] = await Promise.all([
      withDeadline(albumInfo.artistInfo(artist), 25000).catch(() => null),
      withDeadline(albumInfo.artistBio(artist), 20000).catch(() => null)
    ]);
    res.json({
      artist,
      photo:    info ? info.photo  : null,
      photos:   info ? info.photos : [],
      bio:      bio ? { text: bio.text, attribution: bio.attribution } : null,
      members:  info ? info.members  : [],
      memberOf: info ? info.memberOf : []
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Info for the embedded LMS server-settings frame: LMS's address, whether a
// scan is running, and which settings page to frame — served SAME-ORIGIN via
// the proxy above (the same approach Material Skin itself uses: it iframes
// the server settings). When the Material plugin is installed we frame ITS
// styled page (/material/settings/server/basic.html); otherwise Lyrion's
// classic settings (/settings/index.html). The probe result is cached 10 min.
let lmsSettingsProbe = null;   // { at, material }
app.get("/api/lms/settings-info", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const cfg = state.lms.cfg;
  if (!lmsSettingsProbe || (Date.now() - lmsSettingsProbe.at) > 10 * 60 * 1000) {
    let material = false;
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 4000);
      try {
        const r = await fetch(`http://${cfg.host}:${cfg.port}/material/settings/server/basic.html`,
          { signal: ctl.signal, redirect: "manual" });
        material = r.status === 200;
      } finally { clearTimeout(timer); }
    } catch (e) { /* Material not installed / unreachable — classic it is */ }
    lmsSettingsProbe = { at: Date.now(), material };
  }
  let scanning = false;
  try { scanning = (await state.lms.serverStatus()).scanning; } catch (e) { /* best-effort */ }
  res.json({
    host: cfg.host,
    port: cfg.port,
    material: lmsSettingsProbe.material,
    scanning,
    // A path on THIS app's origin — served by the LMS proxy above, so the
    // browser never needs direct reachability to the LMS host.
    settings_path: lmsSettingsProbe.material ? "/material/settings/server/basic.html" : "/settings/index.html"
  });
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
    // Remember the choice for the day by LMS album ID, not by array position.
    // The hash pick is positional, so a restart that rebuilds the index (or any
    // change in album count) would otherwise land on a different album for the
    // same date. Re-picks only if the remembered album has actually gone.
    const pickedId = homePicks.stable(
      "aotd", dstr,
      (id) => index.byId.has(String(id)),
      () => { const r = pool[fnv1aHash(dstr) % pool.length]; return r ? String(r.id) : null; }
    );
    const rec = pickedId != null ? index.byId.get(String(pickedId)) : null;
    if (!rec) return res.json({ album: null });
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
    // Same problem, worse: `keys` GROWS while the background label scan runs,
    // and the old cache invalidated on exactly that growth — so the featured
    // label could change several times during a week. Remember the chosen key
    // for the week and keep it, re-picking only if it stops qualifying.
    const pickedKey = homePicks.stable(
      "lotw", wk,
      (k) => keys.includes(k),
      () => keys[fnv1aHash(wk) % keys.length]
    );
    const entry = pickedKey != null ? get(pickedKey) : null;
    if (!entry) {
      const empty = { label: null, albums: [] };
      lotwCache = { weekKey: wk, at: Date.now(), count, data: empty };
      return res.json(empty);
    }
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
// Tags stays an empty stub for old cached clients — the Tags filter section
// was removed from the UI (owner decision; LMS tags aren't a browse facet here).
app.get("/api/filters/tags",         (req, res) => res.json({ tags: [] }));

// Decades, from the in-memory album index (LMS supplies each album's year in
// the `y` tag). Shape matches the filter sheet's renderer: title + subtitle.
app.get("/api/filters/decades", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    await ensureIndex();
    const counts = new Map();   // 1990 → n
    for (const rec of index.records) {
      if (!rec.year || rec.year < 1000) continue;
      const start = Math.floor(rec.year / 10) * 10;
      counts.set(start, (counts.get(start) || 0) + 1);
    }
    const decades = [...counts.entries()]
      .sort((a, b) => b[0] - a[0])   // newest first
      .map(([start, n]) => ({ title: start + "s", subtitle: n + (n === 1 ? " album" : " albums") }));
    res.json({ decades });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Library view — the ordered, paginated, faceted browse behind the Library
// wall's Sort/Focus controls. Everything else in this app samples the library
// randomly (/api/random-albums); this is the one deterministic view, so paging
// must be stable: the same query always yields the same order, page after page.
//
// Semantics deliberately mirror the sibling Roon build so both apps behave
// identically: values OR within a facet group, groups AND together; `dir`
// means literally "reverse the comparator" for every sort; ties always break
// on sortTitle so equal-ranked albums can't shuffle between pages.
// ---------------------------------------------------------------------------
const LIB_SORTS = new Set(["album", "artist", "year", "genre", "added", "plays", "lastplayed", "random"]);

// Deterministic shuffle: paging must not reshuffle between requests, so the
// order is a pure function of (album, seed) rather than Math.random(). No
// permutation is stored anywhere — the same seed simply re-derives the order.
function seededRank(str, seed) {
  let h = seed >>> 0;
  for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) >>> 0;
  return h;
}

// A sorted+filtered view is memoised per parameter combination (NOT per page),
// so scrolling a long library re-slices one cached array instead of re-sorting
// thousands of records for every page. `index.builtAt` is part of the key, so a
// reindex invalidates every entry without an explicit clear.
const libraryViewCache = new Map();
const LIBRARY_VIEW_CACHE_MAX = 8;
const MONTH_MS = 30 * 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Focus facets — ONE table drives both filtering and counting (v1.0.55).
//
// Filtering and counting used to be written twice: three bespoke `if` blocks in
// libraryView() and three hand-built Maps in /api/library/facets. Adding a
// facet meant editing both and hoping they agreed. Now a facet is one entry
// here: an id, a label, and a function from an album record to the values it
// holds. An album with NO value for a facet returns [] and matches only while
// that facet is unselected.
//
// VALUES ARE STRINGS END TO END. The decade filter used to parseInt its values,
// which would destroy the "!" exclusion prefix before the matcher ever saw it.
// Live Playlists persist these same strings (lib/liveplaylists.js), so the wire
// shape, the stored shape and the matcher all agree by construction.
const LIB_ADDED_WINDOWS = [
  { value: "7",   label: "Week",     days: 7 },
  { value: "30",  label: "Month",    days: 30 },
  { value: "90",  label: "3 months", days: 90 },
  { value: "365", label: "Year",     days: 365 },
];
const SRC_LABEL = { local: "Local files", qobuz: "Qobuz", tidal: "TIDAL" };

function libFacetDefs() {
  return [
    { id: "genre",  label: "Genre",
      // LMS gives genre as a native album tag, so unlike the Roon build there
      // is nothing to harvest — it is simply there.
      values: (r) => (r.genre ? [String(r.genre)] : []), sort: "count" },
    { id: "source", label: "Source",
      // "local" is the ABSENCE of an online-library extid, so it needs a
      // sentinel rather than being left empty.
      values: (r) => [String(r.source || "local")],
      order: ["local", "qobuz", "tidal"], labels: (v) => SRC_LABEL[v] || v },
    { id: "decade", label: "Decade",
      // The value is the START YEAR as a string ("1990"), not "1990s". An
      // album with no year is UNKNOWN and matches no decade.
      values: (r) => (r.year != null && r.year >= 1000 ? [String(Math.floor(r.year / 10) * 10)] : []),
      sort: "numeric-desc", labels: (v) => v + "s" },
    { id: "label",  label: "Record label",
      // Derived by the background label scan, so coverage is partial — the
      // sheet says so rather than showing a list that doesn't add up.
      values: (r) => { const n = labelNameForRecord(r); return n ? [n] : []; }, sort: "count" },
    // Format / Sample rate / Bit depth all come off the SAME sweep as the
    // added times (lib/lms.js albumAddedTimes, tags o/r/T/I on the titles
    // rows), so they cost no extra LMS traffic. An album LMS gave no format
    // for simply drops out, exactly like an undated album and the decade.
    // There is deliberately no Channels facet: the titles sweep carries no
    // channel count, and asking per track would be a query per track.
    { id: "format", label: "Format",
      values: (r) => (r.fmtType ? [String(r.fmtType)] : []),
      sort: "count", labels: (v) => QUALITY_TYPE_LABELS[v] || v.toUpperCase() },
    { id: "rate",   label: "Sample rate",
      values: (r) => (r.fmtRate ? [String(r.fmtRate)] : []),
      sort: "numeric-desc", labels: (v) => rateShort(Number(v)) + " kHz" },
    { id: "bits",   label: "Bit depth",
      values: (r) => (r.fmtBits ? [String(r.fmtBits)] : []),
      sort: "numeric-desc", labels: (v) => v + "-bit" },
    { id: "letter", label: "Starts with",
      // Free: sortTitle already files "The Beatles" under B.
      values: (r) => {
        const c = String(r.sortTitle || "").charAt(0).toUpperCase();
        return c ? [/[A-Z]/.test(c) ? c : "#"] : [];
      }, sort: "alpha" },
    { id: "added",  label: "Added in the last",
      // Windows NEST: an album returns every window that contains it, so
      // picking "3 months" can't exclude what arrived this week.
      values: (r) => {
        if (r.addedAt == null) return [];
        // addedAt comes from LMS tag D, which is epoch SECONDS. Comparing it
        // straight against Date.now() (milliseconds) made every album look
        // ~55 years old, so no window ever matched.
        const ms = r.addedAt < 1e11 ? r.addedAt * 1000 : r.addedAt;
        const age = Date.now() - ms;
        return LIB_ADDED_WINDOWS.filter(w => age >= 0 && age <= w.days * 86400000).map(w => w.value);
      },
      order: LIB_ADDED_WINDOWS.map(w => w.value),
      labels: (v) => (LIB_ADDED_WINDOWS.find(w => w.value === v) || {}).label || v },
  ];
}

// Does an album's values satisfy a selection? OR within a facet, and an
// explicit "!value" EXCLUDES. Excludes always win; a selection made only of
// excludes needs no positive hit to match.
function facetMatch(selected, values) {
  if (!selected || !selected.length) return true;
  let wanted = false, sawInclude = false;
  for (const sel of selected) {
    if (sel.charAt(0) === "!") { if (values.includes(sel.slice(1))) return false; }
    else { sawInclude = true; if (values.includes(sel)) wanted = true; }
  }
  return sawInclude ? wanted : true;
}

// The label a record resolves to, if the background label scan has reached it.
// labels.albumKey() is title+SUBTITLE, so the lookup object must use `subtitle`
// — passing `artist` silently resolves nothing.
// Memoised per index build: this runs once per record per facet pass, and the
// lookup normalises two strings each time.
let labelNameCache = { builtAt: -1, map: new Map() };
function labelNameForRecord(r) {
  if (labelNameCache.builtAt !== index.builtAt) {
    labelNameCache = { builtAt: index.builtAt, map: new Map() };
  }
  if (labelNameCache.map.has(r.offset)) return labelNameCache.map.get(r.offset);
  let out = null;
  try {
    if (labels && typeof labels.labelForAlbum === "function") {
      const n = labels.labelForAlbum({ title: r.title, subtitle: r.subtitle });
      out = n ? String(n) : null;
    }
  } catch (e) { out = null; }
  labelNameCache.map.set(r.offset, out);
  return out;
}

function libraryView(q) {
  const sort  = LIB_SORTS.has(String(q.sort || "")) ? String(q.sort) : "album";
  const desc  = String(q.dir || "asc") === "desc";
  const seed  = parseInt(q.seed, 10) || 1;
  // Values stay STRINGS — parsing them here would destroy an "!" exclusion.
  const asList = (v) => (v === undefined ? [] : (Array.isArray(v) ? v : [v])).map(String).filter(Boolean);
  const defs = libFacetDefs();
  const picked = defs.map(d => ({ def: d, sel: asList(q[d.id]) })).filter(x => x.sel.length);
  const played = String(q.played || "any");

  const sig = [index.builtAt, sort, desc, seed, played,
               ...defs.map(d => d.id + ":" + asList(q[d.id]).join(","))].join("|");
  const hit = libraryViewCache.get(sig);
  if (hit) return hit;

  let list = index.records;
  // Sequential filters = AND across facets; facetMatch is OR within one.
  for (const { def, sel } of picked) list = list.filter(r => facetMatch(sel, def.values(r)));
  if (played !== "any") {
    // Titles played within the window (or ever, for "never"). Matching is by
    // title — see lib/plays.js for why, and the caveat that carries.
    const months = parseInt(played, 10);
    const seen = played === "never"
      ? playsLog.getPlayedTitlesSince(0)
      : playsLog.getPlayedTitlesSince(Date.now() - (Number.isFinite(months) && months > 0 ? months : 6) * MONTH_MS);
    list = list.filter(r => !seen.has(String(r.title || "").toLowerCase().trim()));
  }

  const stats = (sort === "plays" || sort === "lastplayed") ? playsLog.getPlayStats() : null;
  const statOf = (r) => stats.get(String(r.title || "").toLowerCase().trim());
  const byTitle = (a, b) => a.sortTitle.localeCompare(b.sortTitle);
  const cmp = {
    album:  (a, b) => byTitle(a, b) || a.sortArtist.localeCompare(b.sortArtist),
    artist: (a, b) => a.sortArtist.localeCompare(b.sortArtist) || byTitle(a, b),
    genre:  (a, b) => String(a.genre || "").toLowerCase().localeCompare(String(b.genre || "").toLowerCase()) || byTitle(a, b),
    year:   (a, b) => (a.year - b.year) || byTitle(a, b),
    added:  (a, b) => (a.addedAt - b.addedAt) || byTitle(a, b),
    plays:      (a, b) => ((statOf(a) || {}).count  || 0) - ((statOf(b) || {}).count  || 0) || byTitle(a, b),
    lastplayed: (a, b) => ((statOf(a) || {}).lastTs || 0) - ((statOf(b) || {}).lastTs || 0) || byTitle(a, b),
    random: (a, b) => seededRank(a.nTitle + a.nArtist, seed) - seededRank(b.nTitle + b.nArtist, seed),
  }[sort];

  let out;
  if (sort === "year" || sort === "added") {
    // An album whose year LMS never supplied is UNKNOWN, not year zero. Hold
    // those out of the ordering entirely and append them, so flipping to
    // newest-first can't float every undated album to the top of the library.
    const known = [], unknown = [];
    const missing = sort === "year" ? (r) => r.year == null : (r) => r.addedAt == null;
    for (const r of list) (missing(r) ? unknown : known).push(r);
    known.sort(cmp);
    if (desc) known.reverse();
    unknown.sort(byTitle);
    out = known.concat(unknown);
  } else {
    out = list.slice().sort(cmp);
    if (desc) out.reverse();
  }

  if (libraryViewCache.size >= LIBRARY_VIEW_CACHE_MAX) {
    libraryViewCache.delete(libraryViewCache.keys().next().value);   // FIFO evict
  }
  libraryViewCache.set(sig, out);
  return out;
}

app.get("/api/library/albums", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    await ensureIndex();
    const view   = libraryView(req.query);
    const total  = view.length;
    // Clamp to `total`, not total-1: asking past the end is legal and returns
    // an empty page, which is how the client's infinite scroll detects the end.
    const offset = Math.max(0, Math.min(total, parseInt(req.query.offset || "0", 10) || 0));
    const count  = Math.max(1, Math.min(200, parseInt(req.query.count || "60", 10) || 60));
    res.json({ albums: view.slice(offset, offset + count).map(albumOut), offset, total });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Facet lists for the Focus sheet. Counts are of the WHOLE library, never
// scoped to the currently-active filters — the chips are a map of what exists,
// so they must not shift underfoot as you tick them.
app.get("/api/library/facets", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    await ensureIndex();
    const defs = libFacetDefs();
    // ONE pass over the library building one Map per facet, from the SAME
    // value functions libraryView filters with — counting and selecting can no
    // longer disagree, which is what the two hand-written copies allowed.
    const counts = defs.map(() => new Map());
    const covered = defs.map(() => 0);
    for (const r of index.records) {
      defs.forEach((d, i) => {
        const vals = d.values(r);
        if (vals.length) covered[i]++;
        for (const v of vals) counts[i].set(v, (counts[i].get(v) || 0) + 1);
      });
    }
    const CHIP_MAX = 40;
    const facets = defs.map((d, i) => {
      let rows = [...counts[i].entries()].map(([value, count]) => ({
        value, label: d.labels ? d.labels(value) : value, count,
      }));
      if (d.order) {
        const rank = new Map(d.order.map((v, n) => [v, n]));
        rows.sort((a, b) => (rank.has(a.value) ? rank.get(a.value) : 999) -
                            (rank.has(b.value) ? rank.get(b.value) : 999));
      } else if (d.sort === "numeric-desc") {
        rows.sort((a, b) => Number(b.value) - Number(a.value));
      } else if (d.sort === "alpha") {
        rows.sort((a, b) => String(a.value).localeCompare(String(b.value)));
      } else {
        rows.sort((a, b) => b.count - a.count || String(a.value).localeCompare(String(b.value)));
      }
      const total_values = rows.length;
      // Truncate the long tails (genres, labels) — the client synthesises a
      // chip for anything selected that falls outside this slice, so a saved
      // filter is never silently unclearable.
      if (rows.length > CHIP_MAX) rows = rows.slice(0, CHIP_MAX);
      return { id: d.id, label: d.label, total_values, values: rows,
               // How many albums hold ANY value here. LMS doesn't always carry
               // a year, a genre or a resolved label, so the sheet can say
               // "N of M albums" rather than showing a list that doesn't add up.
               covered: covered[i] };
    });
    res.json({
      total: index.records.length,
      facets,
      // Whether any play history exists at all — the client hides the
      // Listening facet entirely rather than offering filters that match all.
      hasPlays: playsLog.getPlayedTitlesSince(0).size > 0,
      // A PWA client can be a service-worker version behind the server, so the
      // pre-v1.0.55 keys stay — but as a PROJECTION of the same facet table,
      // never a second hand-built count that could drift from it.
      dated:   (facets.find(f => f.id === "decade") || {}).covered || 0,
      genred:  (facets.find(f => f.id === "genre")  || {}).covered || 0,
      decades: (facets.find(f => f.id === "decade") || { values: [] }).values
                 .map(v => ({ value: Number(v.value), label: v.label, count: v.count })),
      sources: (facets.find(f => f.id === "source") || { values: [] }).values,
      genres:  (facets.find(f => f.id === "genre")  || { values: [] }).values,
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ---------------------------------------------------------------------------
// Live Playlists — saved Library views, re-evaluated on every open.
//
// A Live Playlist stores ONLY the sort+focus query, never a track list, so it
// keeps itself current: albums join and leave as the library grows and as play
// history ages. Expanding one is literally libraryView() with the saved
// parameters, which means it inherits the same ordering rules, the same
// facet semantics and the same memoisation as the Library wall — there is no
// second query engine to keep in step.
// ---------------------------------------------------------------------------
const livePlaylists = makeLivePlaylists({
  dataDir: DATA_DIR,
  // The vocabulary comes from the Library itself so a saved rule can never
  // reference a sort or filter the Library doesn't implement.
  sorts:   [...LIB_SORTS],
  playeds: ["any", "never", "6", "12"],
});

// Every album a saved playlist matches, in the order it asks for. One function
// so the screen that LISTS a playlist and the button that PLAYS it can never
// disagree about what order it is in.
//
// UNSLICED on purpose. The caller applies the playlist's limit, because the
// count of what MATCHED is what makes "100 of 1,179" honest — slicing here
// would make those two numbers the same and the message meaningless. Shuffling
// before the slice is also the right way round: a random playlist of 100 should
// be 100 drawn from the whole match, not the first 100 by title then jumbled.
function livePlaylistAlbums(rec) {
  const view = libraryView(rec.view);
  if ((rec.order || "album") !== "random") return view;
  // Seeded off the view's own seed, never Math.random(): the listing is paged,
  // so a fresh shuffle per request would repeat some albums and skip others as
  // the user scrolls.
  const seed = (rec.view && rec.view.seed) || 1;
  return view.slice().sort((a, b) =>
    seededRank(a.nTitle + a.nArtist, seed) - seededRank(b.nTitle + b.nArtist, seed));
}

// Up to four covers for a playlist's mosaic tile, plus how many albums it
// currently resolves to. Both are computed live — that IS the feature.
function livePlaylistSummary(rec) {
  let albums = [];
  // A rule that somehow can't be evaluated must not take the whole list down
  // — the row just loses its count and mosaic.
  try { albums = livePlaylistAlbums(rec); } catch (e) { albums = []; }
  // Up to four DISTINCT covers, walked in the playlist's own order so the
  // mosaic shows what Play Now would actually start with. Distinct because a
  // rule that resolves to one artist would otherwise show one sleeve x4.
  const art = [];
  for (const a of albums) {
    if (a.image_key && !art.includes(a.image_key)) art.push(a.image_key);
    if (art.length === 4) break;
  }
  // `total` is what this playlist DELIVERS; `matched` is what the rule found.
  // Reporting only the second made every capped playlist read as a failure to
  // play the whole thing.
  return { id: rec.id, name: rec.name, view: rec.view,
           limit: rec.limit, order: rec.order,
           total: Math.min(albums.length, rec.limit), matched: albums.length, art };
}

app.get("/api/live-playlists", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    await ensureIndex();
    res.json({ playlists: livePlaylists.list().map(livePlaylistSummary), max: livePlaylists.MAX });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Create, or update in place when an id is supplied — updating by id is what
// lets a playlist be renamed without forking a duplicate.
app.post("/api/live-playlists", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const { id, name, view, limit, order } = req.body || {};
  if (!String(name || "").trim()) return res.status(400).json({ error: "name required" });
  try {
    await ensureIndex();
    const rec = livePlaylists.put({ id, name, view, limit, order });
    if (!rec) return res.status(409).json({ error: "You can have at most " + livePlaylists.MAX + " Live Playlists" });
    res.json({ ok: true, playlist: livePlaylistSummary(rec) });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post("/api/live-playlists/delete", (req, res) => {
  const id = (req.body || {}).id;
  if (!id) return res.status(400).json({ error: "id required" });
  if (!livePlaylists.remove(id)) return res.status(404).json({ error: "Unknown playlist" });
  res.json({ ok: true });
});

// One playlist's albums, paged. Deliberately album-paged rather than
// track-paged: this app is album-centric everywhere else, and resolving every
// album's tracks up front would mean an LMS round-trip per album just to draw
// a list the user may not scroll.
app.get("/api/live-playlist", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const rec = livePlaylists.get(req.query.id);
  if (!rec) return res.status(404).json({ error: "Unknown playlist" });
  try {
    await ensureIndex();
    const matched = livePlaylistAlbums(rec);
    // Sliced to the playlist's own limit BEFORE paging, so the count the user
    // scrolls through is the count that will actually play. Applied here rather
    // than inside libraryView(), whose memo has no limit in its signature —
    // two playlists sharing a rule set would otherwise share one cache entry
    // and one limit.
    const view   = matched.slice(0, rec.limit);
    const total  = view.length;
    const offset = Math.max(0, Math.min(total, parseInt(req.query.offset || "0", 10) || 0));
    const count  = Math.max(1, Math.min(200, parseInt(req.query.count || "60", 10) || 60));
    res.json({ id: rec.id, name: rec.name, view: rec.view,
      limit: rec.limit, order: rec.order, total, matched: matched.length, offset,
      albums: view.slice(offset, offset + count).map(albumOut) });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Every album offset in a playlist, for Play Now / Queue on the whole thing.
// Capped because "play all" on a 5,000-album rule would otherwise hand LMS a
// playlistcontrol call per album; the client says when it has been truncated.
app.get("/api/live-playlist/albums", async (req, res) => {
  if (!state.connected) return notConnected(res);
  const rec = livePlaylists.get(req.query.id);
  if (!rec) return res.status(404).json({ error: "Unknown playlist" });
  try {
    await ensureIndex();
    // The caller's ask, the play-time ceiling and the playlist's own limit —
    // whichever is smallest wins.
    const asked = Math.max(1, Math.min(500, parseInt(req.query.max || "200", 10) || 200));
    const max   = Math.min(asked, rec.limit);
    const view  = livePlaylistAlbums(rec);
    const total = Math.min(view.length, rec.limit);
    res.json({ total, matched: view.length, truncated: total > max,
      offsets: view.slice(0, max).map(a => a.offset) });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

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
// Artist photos come from the same module (MAI plugin -> Qobuz). Every part
// is best-effort - the page rotates whatever arrived. Cached 6h per album.
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
  // First credited artist via the shared splitter (handles " & ", ", ",
  // " + ", "; ", " / " and feat.) — drives the video search and the
  // "More from <artist>" grid.
  const artistParts = search.splitArtistNames(artist);
  const primaryArtist = (artistParts[0] && artistParts[0].name) || artist;

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
    const [video, review, artistPhotoInfo, ...bioResults] = await Promise.all([
      fetchDisplayVideo(primaryArtist, track, (st && st.duration) || (t && t.duration) || null).catch(() => null),
      withDeadline(albumInfo.albumReview({
        albumId: npRec ? npRec.id : null,
        title:   album,
        artist,
        extid:   npRec ? npRec.extid  : null,
        source:  npRec ? npRec.source : null
      }), 20000).catch(() => null),
      // Artist photos for the rotation (MAI plugin → Qobuz) — display.js
      // takes up to 4 and already knows how to rotate them.
      withDeadline(albumInfo.artistInfo(primaryArtist), 25000).catch(() => null),
      ...creditedArtists.map(name =>
        withDeadline(albumInfo.artistBio(name), 20000).catch(() => null))
    ]);
    const artistPhotos = artistPhotoInfo && artistPhotoInfo.photos ? artistPhotoInfo.photos.slice(0, 4) : [];
    const bios = bioResults.filter(Boolean)
      .map(b => ({ name: b.name, text: b.text, attribution: b.attribution }));
    // More by this artist — from the in-memory album index (no API keys).
    // Matches on the per-record artistNames identity keys, so any credited
    // position ("Panda Bear & Sonic Boom", "X feat. Y") counts and stylized
    // spellings collapse (P!nk == Pink).
    const artistK = search.artistKey(primaryArtist);
    const moreArtist = [];
    if (artistK) {
      for (const al of index.records) {
        if (moreArtist.length >= 12) break;
        if (search.normalize(al.title) === npTitleN) continue;
        if ((al.artistNames || []).some(a => (a.k || a.n) === artistK)) {
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
      artistPhotos,
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

// ---- Global search across external sources (Pitchfork reviews only) ----
// Play/add actions for Qobuz search results are kept SERVER-SIDE, keyed by an
// opaque token the client echoes back to /api/qobuz/play — the client never
// sees or submits a raw LMS command (which would be a command-injection hole).
const qobuzActionStore = new Map();   // token -> { play, add, favItemId, at }
const QOBUZ_ACTION_TTL = 30 * 60 * 1000;
function qobuzActionPut(play, add, favItemId) {
  const token = crypto.randomBytes(9).toString("base64url");
  qobuzActionStore.set(token, { play, add, favItemId, at: Date.now() });
  if (qobuzActionStore.size > 800) {   // opportunistic sweep of expired tokens
    const cut = Date.now() - QOBUZ_ACTION_TTL;
    for (const [k, v] of qobuzActionStore) if (v.at < cut) qobuzActionStore.delete(k);
  }
  return token;
}

// The user's Qobuz favourite album ids, cached briefly (used to fill hearts on
// library + search tiles). Also keeps each favourite's descend action so a
// library album can be UN-favourited by id without re-walking the whole menu.
let qobuzFavCache = { at: 0, keys: new Set(), byKey: new Map() };
const QOBUZ_FAV_TTL = 60 * 1000;
// Favourite album rows have no id, so match library albums by title+artist.
function qobuzFavKey(title, artist) {
  const norm = (s) => String(s || "").toLowerCase().replace(/\s*\(\d{4}\)\s*$/, "").replace(/[^a-z0-9]+/g, " ").trim();
  return norm(title) + "|" + norm(artist);
}
async function qobuzFavorites(force) {
  const player = state.players[0] && state.players[0].id;
  if (!state.connected || !player || !state.lms.qobuzFavoriteAlbums) return qobuzFavCache;
  if (!force && (Date.now() - qobuzFavCache.at) < QOBUZ_FAV_TTL) return qobuzFavCache;
  const list = await state.lms.qobuzFavoriteAlbums(player).catch((e) => { log.debug("qobuz favourites failed:", e.message); return null; });
  if (list) qobuzFavCache = { at: Date.now(),
    keys: new Set(list.map(a => qobuzFavKey(a.title, a.artist))),
    byKey: new Map(list.map(a => [qobuzFavKey(a.title, a.artist), a])) };
  return qobuzFavCache;
}
// Absolute remote cover → the existing "url-…" image_key form so /api/image
// serves it through LMS's imageproxy (same as online-library album art).
function qobuzImageKey(img) {
  let s = String(img || "");
  // Qobuz covers arrive as a RELATIVE LMS imageproxy path:
  //   /imageproxy/<uri-encoded absolute url>/image.jpg
  // Unwrap the embedded absolute URL so it flows through the normal url- key →
  // /api/image → LMS imageproxy resize path (same as online-library art).
  const m = s.match(/^\/imageproxy\/([^/]+)\/image/i);
  if (m) { try { const u = decodeURIComponent(m[1]); if (/^https?:\/\//i.test(u)) s = u; } catch (e) { /* keep s */ } }
  return /^https?:\/\//i.test(s) ? "url-" + Buffer.from(s, "utf8").toString("base64url") : null;
}
async function searchQobuz(q, playerId, limit) {
  if (!state.lms || !state.lms.qobuzSearchAlbums || !playerId) return [];
  const rows = await state.lms.qobuzSearchAlbums(playerId, q, limit);
  return rows.map(r => ({
    token:     qobuzActionPut(r.play, r.add, r.favItemId),
    title:     r.title || "",
    subtitle:  r.artist || "",
    source:    "qobuz",
    image_key: qobuzImageKey(r.image),
    can_queue: !!r.add,
    can_favorite: r.favItemId != null,
    fav_key:   r.favItemId || null,
  }));
}

// ---- streaming-service availability ---------------------------------------
// The owner can log out of Qobuz and remove the plugin from LMS; nothing about
// the app should still offer it. Probed lazily and cached, because it needs two
// round trips per service and the answer only changes when the owner changes a
// server setting. `refresh=1` forces a re-probe (the Settings screen and the
// side menu both offer it).
const SERVICE_TAGS = ["qobuz", "tidal", "deezer", "spotty"];
const SERVICE_LABEL = { qobuz: "Qobuz", tidal: "TIDAL", deezer: "Deezer", spotty: "Spotify" };
const SERVICE_TTL = 5 * 60 * 1000;
let serviceCache = { at: 0, list: null, inflight: null };

async function probeServices() {
  const player = state.players[0] && state.players[0].id;
  // With no player we cannot run the second step at all (every `<tag> items`
  // dispatch is needs-client=1). Reporting "unusable" then would hide a
  // perfectly good Qobuz whenever the server has no players connected, so
  // treat it as UNKNOWN and leave the UI as it was.
  if (!player) return null;
  // `apps` names every ENABLED app plugin, so a service the owner installed
  // that we don't know about still gets a label; it can't see login state, so
  // serviceStatus still decides usability.
  const apps = await state.lms.listApps().catch(() => []);
  const byTag = new Map(apps.map(a => [a.tag, a.name]));
  const tags = [...new Set([...SERVICE_TAGS, ...apps.map(a => a.tag)])];
  // In parallel: each tag costs two round trips, and a server with a dozen app
  // plugins made this a ~25-RPC serial walk in front of a user action.
  const results = await Promise.all(tags.map(tag =>
    state.lms.serviceStatus(tag, player).catch(() => null)));
  const out = [];
  results.forEach((st, i) => {
    if (!st || !st.installed) return;
    const tag = tags[i];
    out.push({ tag, name: SERVICE_LABEL[tag] || byTag.get(tag) || tag,
               installed: true, usable: !!st.usable, notice: st.notice || null });
  });
  return out;
}

async function services(force) {
  if (!state.connected || !state.lms) return serviceCache.list || [];
  if (force && serviceCache.inflight) await serviceCache.inflight.catch(() => {});
  if (!force && serviceCache.list && (Date.now() - serviceCache.at) < SERVICE_TTL) return serviceCache.list;
  // Coalesce concurrent probes — the side menu and a search can ask at once.
  if (!force && serviceCache.inflight) return serviceCache.inflight;
  serviceCache.inflight = probeServices()
    .then((list) => {
      // A null probe means "couldn't tell" (no player). Keep whatever we knew
      // and do NOT stamp the cache, or one blip hides every service for the
      // whole TTL — the failure mode is silent and looks like a bug in the app.
      if (list === null) { serviceCache.inflight = null; return serviceCache.list || []; }
      serviceCache = { at: Date.now(), list, inflight: null };
      return list;
    })
    .catch((e) => { serviceCache.inflight = null; log.debug("service probe failed:", e.message); return serviceCache.list || []; });
  return serviceCache.inflight;
}

// Invalidate on reconnect: a server that just came back may have had plugins
// added, removed or signed in while we were away.
function invalidateServices() { serviceCache = { at: 0, list: serviceCache.list, inflight: null }; }

async function serviceUsable(tag) {
  const list = await services(false);
  const s = list.find(x => x.tag === tag);
  return !!(s && s.usable);
}

// Every /api/qobuz/* route goes through this, so an absent or logged-out plugin
// answers "unavailable" instead of a raw socket-error 500 the UI can't read.
async function requireService(tag, res) {
  if (!state.connected) { notConnected(res); return false; }
  if (await serviceUsable(tag)) return true;
  const list = await services(false);
  const s = list.find(x => x.tag === tag);
  res.status(503).json({
    error: s ? ((SERVICE_LABEL[tag] || tag) + " isn\u2019t signed in on your server")
             : ((SERVICE_LABEL[tag] || tag) + " isn\u2019t installed on your server"),
    unavailable: true, service: tag,
  });
  return false;
}

app.get("/api/services", async (req, res) => {
  try {
    const list = await services(req.query.refresh === "1");
    res.json({ services: list, connected: !!state.connected });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// ---------------------------------------------------------------------------
// Smart Picks — six albums a day by artists NOT in the library.
//
// Five "adjacent" picks from the neighbourhood the library already lives in,
// and one "stretch" pick from a genre it barely touches. The choosing is pure
// and lives in lib/smartpickalgo.js (with its own tests); this section is the
// I/O around it: ListenBrainz + MusicBrainz for the graph, the LMS Qobuz
// plugin to turn an artist name into a real, favouritable album, and
// lib/smartpicks.js to remember the answer.
//
// TWO THINGS THAT DIFFER FROM THE ROON BUILD, both forced by LMS:
//
//  1. A pick CANNOT carry a stored handle. The Roon build stores a Qobuz album
//     id; the LMS Qobuz plugin exposes no ids at all, only menu nodes that
//     expire with the session (see the qobuzActionStore TTL). So a pick stores
//     artist + album TITLE, and both the Play and the Add paths re-resolve by
//     search at the moment they are tapped.
//
//     LMS is the LESS constrained of the two on the thing that matters, and it
//     is worth being clear about which way round: Roon's API can only play what
//     is in the library, so the Roon build MUST add an album before it can be
//     heard. LMS plays a catalogue album directly, so Play is the primary
//     action here and Add is merely "keep this".
//  2. Every `qobuz` dispatch is needs-client=1, so resolving needs a CONNECTED
//     PLAYER. With none, the build is skipped WITHOUT marking the day
//     attempted, so it retries on the next tick rather than writing off the
//     day — a server whose amp is off overnight would otherwise never get
//     picks at all.
// ---------------------------------------------------------------------------
const smartPicks = makeSmartPicks({ dataDir: DATA_DIR });
const smartCache = makeSmartCache({ dataDir: DATA_DIR });
const spLog = log.child("picks");

const smartDayKey = (d) => {
  const t = d || new Date();
  return t.getFullYear() + "-" + String(t.getMonth() + 1).padStart(2, "0") +
         "-" + String(t.getDate()).padStart(2, "0");
};

// The library, as {canon → {canon, name, albums, plays}}. Deliberately NOT
// cached on index.builtAt: play counts move without the index being rebuilt,
// and a profile frozen to the index would stop reflecting listening.
function smartLibraryProfile() {
  const stats = playsLog.getPlayStats();
  return smartAlgo.libraryArtistProfile(
    index.records, search.splitArtistNames,
    (title) => {
      const row = stats.get(String(title || "").toLowerCase().trim());
      return row ? row.count : 0;
    });
}

// Everything the owner ALREADY HAS, as artist keys. Not just the LMS index:
// "already owned" has to mean the whole collection, or a Smart Pick can offer
// back an album that is sitting in the owner's Qobuz favourites — reported,
// and the reason this is no longer a one-liner over index.records.
//
// Three sources, and each covers a gap the others cannot:
//   * the LMS index — local files, plus any online album a scan has imported;
//   * the streaming service's own favourites — an album favourited but never
//     scanned exists ONLY there, so nothing in the index knows about it;
//   * this app's favourites — which can hold a catalogue album that is in
//     neither (that is the whole point of them, see lib/favourites.js).
//
// A service read is best-effort: it must never be able to fail a build. Losing
// it costs precision (a few already-owned artists slip through), where letting
// it throw would cost the day's picks entirely.
async function smartLibraryArtists() {
  const out = new Set();
  const addName = (s) => {
    for (const a of search.splitArtistNames(s || "")) if (a.k) out.add(a.k);
  };
  for (const rec of index.records) addName(rec.subtitle);
  for (const row of favourites.list()) addName(row.artist);
  // The Qobuz row's artist carries a trailing year ("Labradford (1997)") — see
  // qobuzRowArtist. Comparing without stripping it matches nothing at all.
  try {
    if (await serviceUsable("qobuz")) {
      for (const a of (await qobuzFavorites(false)).byKey.values()) addName(qobuzRowArtist(a.artist));
    }
  } catch (e) { spLog.debug("qobuz favourites unavailable for the exclusion set:", e.message); }
  return out;
}

// Genre shares, from the same rec.genre the Library facet uses so the two
// cannot disagree about what the library contains.
function smartGenreWeights() {
  const w = new Map();
  let total = 0;
  for (const rec of index.records) {
    const g = rec.genre ? String(rec.genre).trim() : "";
    if (!g) continue;
    w.set(g, (w.get(g) || 0) + 1);
    total++;
  }
  return { weights: w, total };
}

// The world's most-listened artists. Used twice: never seed from one, and
// never offer one as a discovery. If this is empty the WHOLE BUILD ABORTS —
// with no hub list the seed policy inverts and the feature starts recommending
// Radiohead.
async function smartHubSet() {
  let rows = smartCache.get("hubs");
  if (rows === undefined) {
    rows = await discovery.topArtists({}).catch((e) => {
      spLog.debug("hub chart failed:", e.message);
      return null;
    });
    if (!rows) return null;                 // NOT cached — a failure is not an answer
    smartCache.set("hubs", rows);
  }
  const out = new Set();
  for (const r of rows || []) if (r && r.name) out.add(search.artistKey(r.name));
  out.delete("");
  return out;
}

// Similar artists for a set of seeds, cached per seed.
//
// NEGATIVE CACHING TRAP, inherited from the Roon build: if rows come back but
// none is attributable to a seed we asked about, writing an empty array for
// every seed poisons the cache for 30 days and the feature dies silently. So
// a seed is only cached when the response actually mentioned it.
async function smartSimilarRows(seedMbids) {
  const out = [];
  const ask = [];
  for (const id of seedMbids) {
    const hit = smartCache.get("sim:" + id);
    if (hit === undefined) ask.push(id);
    else for (const r of hit || []) out.push(r);
  }
  if (!ask.length) return out;
  const rows = await discovery.similarArtistsBatched(ask, {
    onError: (e, batch) => spLog.debug("similar batch failed (" + batch.length + " seeds):", e.message),
  });
  const bySeed = new Map();
  for (const r of rows) {
    if (!r.seed) continue;
    if (!bySeed.has(r.seed)) bySeed.set(r.seed, []);
    bySeed.get(r.seed).push(r);
  }
  for (const id of ask) {
    const mine = bySeed.get(id);
    if (mine) smartCache.set("sim:" + id, mine);   // only what was answered
    for (const r of mine || []) out.push(r);
  }
  return out;
}

// An artist's roster for a genre, in MusicBrainz's own relevance order (which
// is what makes a stretch pick defensible — do NOT re-sort it).
//
// A library genre is asked for under SEVERAL tag spellings, first hit wins:
// the file's own GENRE tag is routinely a compound ("Alternative & Punk") and
// MusicBrainz tags are atomic, so the literal string alone matched nothing.
// See genreTagCandidates. A FETCH ERROR is not cached — only a real, empty
// answer is, so a MusicBrainz blip doesn't write the genre off for 30 days.
async function smartTagArtists(genre) {
  for (const tag of smartAlgo.genreTagCandidates(genre)) {
    const key = "tag:" + tag;
    let rows = smartCache.get(key);
    if (rows === undefined) {
      await mbWait();
      rows = await discovery.artistsByTag(tag, {
        limit: smartAlgo.MAX_STRETCH_ROSTER, userAgent: MB_UA,
      }).catch((e) => { spLog.debug("tag roster failed for \"" + tag + "\":", e.message); return null; });
      if (rows) smartCache.set(key, rows);
    }
    if (rows && rows.length) {
      spLog.debug("genre " + JSON.stringify(genre) + " → tag " + JSON.stringify(tag) +
                  " gave " + rows.length + " artists");
      return rows;
    }
  }
  spLog.debug("genre " + JSON.stringify(genre) + ": no MusicBrainz tag matched");
  return [];
}

// Artist name → a real Qobuz album, through the plugin's menu tree.
//
// A MISS IS CACHED. "This artist has nothing findable" is the expensive answer
// and re-asking it every day is what makes a build slow.
// A Qobuz menu row's artist carries a trailing year — the label is
// "Album\nArtist (Year)" and qobuzTitleArtist keeps the whole second line. So
// the identity check has to strip it, exactly as qobuzFavKey does, or NOTHING
// ever matches and every build silently resolves nothing.
const qobuzRowArtist = (s) => String(s || "").replace(/\s*\(\d{4}\)\s*$/, "").trim();

async function smartResolveAlbum(name, playerId) {
  const canon = search.artistKey(name);
  const key = "alb:" + canon;
  const hit = smartCache.get(key);
  if (hit !== undefined) return hit;
  let found = null;
  try {
    const rows = await state.lms.qobuzSearchAlbums(playerId, name, 20);
    // The plugin's search is loose — require the ROW's artist to be the artist
    // we asked for, or a pick ends up credited to whoever happened to rank
    // first for the name.
    const row = (rows || []).find(r => r && r.title && search.artistKey(qobuzRowArtist(r.artist)) === canon);
    if (row) found = { album: row.title, artist: qobuzRowArtist(row.artist) || name, image: row.image || null,
                       favItemId: row.favItemId != null ? row.favItemId : null };
  } catch (e) {
    spLog.debug("resolve failed for " + name + ":", e.message);
    return undefined;    // an ERROR is not a miss — don't cache it
  }
  // The favItemId is a MENU NODE and expires with the session, so it is
  // stripped before caching — a stale one would silently favourite nothing, or
  // worse, something else. It is only usable on the call that produced it.
  smartCache.set(key, found ? { album: found.album, artist: found.artist, image: found.image } : null);
  return found;
}

let smartBuilding = false;

// Build (or rebuild) one day's picks. Never throws — a failure marks the day
// attempted so the next request doesn't start it all over again.
async function buildSmartPicks(day) {
  if (smartBuilding) return null;
  smartBuilding = true;
  const t0 = Date.now();
  try {
    smartCache.prune();
    const playerId = state.players[0] && state.players[0].id;
    // No player → no Qobuz call is possible. Retry later rather than writing
    // off the day (see the header note).
    if (!playerId) { spLog.debug("no connected player — deferring"); return null; }
    if (!index.records.length) { spLog.debug("no library index yet — deferring"); return null; }
    if (!(await serviceUsable("qobuz"))) {
      smartPicks.markAttempt(day, { picks: 0, reason: "no service" });
      spLog.info("no usable Qobuz plugin — nothing to resolve against");
      return [];
    }

    const hubs = await smartHubSet();
    if (!hubs || !hubs.size) {
      // Deliberately NOT marked attempted: this is our failure, not the day's.
      spLog.warn("no hub chart — skipping (without it the seed policy inverts)");
      return null;
    }

    const profile = smartLibraryProfile();
    const sets = {
      library: await smartLibraryArtists(),
      hubs,
      blocked: smartPicks.blockedSet(),
      seen:    smartPicks.seenSet(),
    };

    const autoAdd = smartPicksAutoAdd();
    const seeds = smartAlgo.smartPickSeeds(profile, hubs, smartAlgo.SEED_COUNT);
    const seedNameByMbid = new Map();
    const seedMbids = [];
    for (const s of seeds) {
      const mbid = await albumInfo.artistMbid(s.name);
      if (!mbid) continue;
      seedMbids.push(mbid);
      seedNameByMbid.set(mbid, s.name);
    }
    if (!seedMbids.length) {
      smartPicks.markAttempt(day, { picks: 0, reason: "no seed mbids" });
      spLog.info("no seed artist could be resolved to a MusicBrainz id");
      return [];
    }

    const rows = await smartSimilarRows(seedMbids);
    const cands = smartAlgo.collectSmartCandidates(rows, seedNameByMbid, search.artistKey);
    const pool = smartAlgo.diversifySmartCandidates(
      smartAlgo.rankSmartCandidates(cands.filter(c => !smartAlgo.smartPickExcluded(c.canon, sets))))
      .slice(0, smartAlgo.POOL_COUNT);

    const picks = [];
    let tries = 0;
    for (const c of pool) {
      if (picks.length >= smartAlgo.PICK_COUNT) break;
      if (tries >= smartAlgo.MAX_RESOLVES) break;
      tries++;
      const alb = await smartResolveAlbum(c.name, playerId);
      if (!alb) continue;
      const rec = { kind: "adjacent", mbid: c.mbid, artist: c.name, canon: c.canon,
                    album: alb.album, image: alb.image, genre: "",
                    seedNames: c.seedNames };
      rec.reason = smartAlgo.smartPickReason(rec);
      delete rec.seedNames;
      // Auto-add, when the owner has asked for it. Only possible on the call
      // that resolved the album — a cache hit has no live favItemId, and that
      // is the right outcome: a cache hit means we resolved it recently, so it
      // was already offered (and added) then. The STRETCH pick is never
      // auto-added; that one is always the owner's to accept or reject.
      if (autoAdd && alb.favItemId != null) {
        try {
          await state.lms.qobuzAlbumFavoriteToggle(playerId, alb.favItemId, true);
          qobuzFavCache.at = 0;
        } catch (e) { spLog.debug("auto-add failed for " + c.name + ":", e.message); }
      }
      picks.push(rec);
    }

    // The stretch pick. Its roster comes back in MusicBrainz's relevance order,
    // which is what makes it defensible rather than a random unknown.
    //
    // It can come up empty at any of four stages and used to say so nowhere —
    // the build logged "built 5 picks" whether the sixth had been considered or
    // not, which is how "the stretch selection isn't showing" stayed a mystery.
    // `stretchWhy` names the stage that ran out, is logged, and rides along in
    // the day's attempt record so the reason survives the build.
    const stretchBudget = smartAlgo.MAX_RESOLVES + smartAlgo.MAX_STRETCH_ROSTER;
    const { weights, total } = smartGenreWeights();
    const outside = smartAlgo.smartStretchGenres(weights, total).slice(0, smartAlgo.MAX_STRETCH_GENRES);
    let stretchWhy = outside.length ? "no artist resolved to a Qobuz album" : "no genre outside the library";
    let rosters = 0;
    for (const g of outside) {
      if (picks.some(p => p.kind === "stretch")) break;
      if (tries >= stretchBudget) { stretchWhy = "resolve budget spent on the adjacent picks"; break; }
      const roster = await smartTagArtists(g.genre);
      if (roster.length) rosters++;
      for (const a of roster.slice(0, smartAlgo.MAX_STRETCH_ROSTER)) {
        const canon = search.artistKey(a.name);
        if (smartAlgo.smartPickExcluded(canon, sets)) continue;
        if (tries >= stretchBudget) { stretchWhy = "resolve budget spent on the adjacent picks"; break; }
        tries++;
        const alb = await smartResolveAlbum(a.name, playerId);
        if (!alb) continue;
        const rec = { kind: "stretch", mbid: a.mbid, artist: a.name, canon,
                      album: alb.album, image: alb.image, genre: g.genre };
        rec.reason = smartAlgo.smartPickReason(rec);
        picks.push(rec);
        break;
      }
    }
    if (outside.length && !rosters) stretchWhy = "no MusicBrainz tag matched any candidate genre";
    const gotStretch = picks.some(p => p.kind === "stretch");

    const written = smartPicks.writeDay(day, picks);
    smartPicks.markAttempt(day, gotStretch ? { picks: written.length }
                                           : { picks: written.length, stretch: stretchWhy });
    spLog.info("built " + written.length + " picks for " + day +
               " (" + seedMbids.length + " seeds, " + tries + " resolves, " +
               Math.round((Date.now() - t0) / 1000) + "s)" +
               (gotStretch ? "" : " — no stretch pick: " + stretchWhy +
                 " [genres tried: " + (outside.map(g => g.genre).join(", ") || "none") + "]"));
    return written;
  } catch (e) {
    smartPicks.markAttempt(day, { picks: 0, reason: e.message });
    spLog.warn("build failed:", e.message);
    return [];
  } finally {
    smartBuilding = false;
  }
}

// Kick a build in the background. Never awaited by a request — the first build
// takes a minute or two and no page load should wait for it.
function kickSmartPicks(day, force) {
  const d = day || smartDayKey();
  if (smartBuilding) return false;
  if (!force && smartPicks.attempted(d)) return false;
  buildSmartPicks(d).catch((e) => spLog.debug("kick:", e.message));
  return true;
}

// A pick as the client sees it. `added` and `offset` are DERIVED HERE, never
// stored — the Roon build learned that the hard way: latching "Added" into the
// stored row meant it didn't survive a reload, and a stored offset goes stale
// on the next rescan.
function smartPickJson(p, favKeys) {
  const out = {
    kind: p.kind, artist: p.artist, mbid: p.mbid,
    album: p.album, image: p.image, reason: p.reason, genre: p.genre,
    service: "qobuz",
    added: null, offset: null, id: null,
    library_title: null, library_subtitle: null, image_key: null,
  };
  if (favKeys) out.added = favKeys.has(qobuzFavKey(p.album || "", p.artist || ""));
  const rec = smartLibraryRecord(p.album, p.artist);
  if (rec) {
    // The LIBRARY's own strings travel with the offset: the play routes check
    // identity against the index, and Qobuz's title (with its edition suffix)
    // would be refused as a stale offset.
    out.offset = rec.offset;
    out.id = rec.id;
    out.library_title = rec.title;
    out.library_subtitle = rec.subtitle;
    out.image_key = rec.image_key || null;
  }
  return out;
}

// Has the library got this album yet? Rebuilt only when the index is, because
// it is a full sweep of every record.
//
// Keyed on qobuzFavKey — the same normaliser the Qobuz heart already matches
// library albums with, including its trailing-(YYYY) strip. It is not exact:
// an edition suffix Qobuz carries and the file tag doesn't will miss, and the
// card then keeps saying "waiting for the next scan". That is the safe
// direction — the alternative is a Play button that opens the wrong album.
let smartLibIndex = { builtAt: -1, byKey: new Map() };
function smartLibraryRecord(album, artist) {
  if (!album) return null;
  if (smartLibIndex.builtAt !== index.builtAt) {
    const byKey = new Map();
    for (const rec of index.records) {
      const k = qobuzFavKey(rec.title, rec.subtitle);
      if (!byKey.has(k)) byKey.set(k, rec);
    }
    smartLibIndex = { builtAt: index.builtAt, byKey };
  }
  return smartLibIndex.byKey.get(qobuzFavKey(album, artist)) || null;
}

app.get("/api/smart-picks", async (req, res) => {
  if (!state.connected) return notConnected(res);
  try {
    const day = smartDayKey();
    let rows = smartPicks.readDay(day);
    let building = false;
    if (!rows.length) building = kickSmartPicks(day, false);
    const usable = await serviceUsable("qobuz");
    // Favourite state is best-effort: an unreachable Qobuz leaves `added` null,
    // which the client reads as "couldn't ask" rather than "not added".
    let favKeys = null;
    if (usable && rows.length) {
      try { favKeys = (await qobuzFavorites(false)).keys; } catch (e) { spLog.debug("fav state:", e.message); }
    }
    res.json({
      day, building,
      service_ready: usable,
      picks: rows.map(p => smartPickJson(p, favKeys)),
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// "Not for me" — never suggest this artist again, and take their cards away
// now rather than at the next rebuild.
app.post("/api/smart-picks/block", (req, res) => {
  const artist = String((req.body && req.body.artist) || "").trim();
  if (!artist) return res.status(400).json({ error: "artist required" });
  const canon = search.artistKey(artist);
  if (!canon) return res.status(400).json({ error: "unrecognisable artist name" });
  smartPicks.block(canon, artist);
  const removed = smartPicks.deleteByCanon(canon);
  res.json({ ok: true, artist, removed });
});

// Favourite a pick's album in the owner's Qobuz account.
//
// The album is RE-RESOLVED here rather than addressed by a stored id: the LMS
// Qobuz plugin exposes no album ids, only menu nodes that expire with the
// session, so nothing addable can survive in a JSON file overnight. One search
// per tap is the price of that, and it is paid at the moment the owner asks
// for it rather than six times per page view.
// Find a pick's album in the Qobuz catalogue again, RIGHT NOW.
//
// A pick stores artist + album title and nothing else, because the plugin's
// menu nodes expire with the session — so both Play and Add re-search at the
// moment they are tapped. One resolver for both, or the two buttons on a card
// could end up meaning different albums.
async function smartResolveRow(playerId, artist, album) {
  const rows = await state.lms.qobuzSearchAlbums(playerId, artist, 20);
  const wantArtist = search.artistKey(artist);
  const wantAlbum = search.normalize(album);
  return (rows || []).find(r => r && search.artistKey(qobuzRowArtist(r.artist)) === wantArtist &&
                                search.normalize(r.title) === wantAlbum) || null;
}

// Play (or queue) a pick STRAIGHT FROM THE CATALOGUE — no favourite first.
//
// This is a substantive difference from the Roon build, not a shortcut. Roon's
// API can only play what is already in the library, so the Roon extension must
// add an album before it can be heard. LMS has no such rule: `qobuz playlist
// play item_id:…` resolves the album id to `qobuz://…` track URLs and hands
// them straight to the player (QobuzPlugin.pm QobuzGetTracks → XMLBrowser's
// playlist branch → `playlist loadtracks`). Verified against the plugin
// source: nothing on that path consults the user's favourites, and nothing
// writes to the library — a remote track is an in-memory
// Slim::Schema::RemoteTrack, not a database row. Play is a pure read, and safe
// to offer on an album the owner does not own.
app.post("/api/smart-picks/play", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const body = req.body || {};
  // Resolved with ANY player (the menu walk is server-global), but played on
  // the zone the owner is actually pointed at.
  const player = state.players[0] && state.players[0].id;
  const zone = String(body.zone_or_output_id || "").trim() || player;
  if (!player) return res.status(503).json({ error: "No player available" });
  const artist = String(body.artist || "").trim();
  const album  = String(body.album || "").trim();
  const kind   = body.kind === "queue" ? "queue" : "play_now";
  if (!artist || !album) return res.status(400).json({ error: "artist and album required" });
  try {
    const row = await smartResolveRow(player, artist, album);
    const action = row && (kind === "queue" ? (row.add || row.play) : row.play);
    if (!action) return res.status(404).json({ error: "Couldn\u2019t find that album on Qobuz any more" });
    await state.lms.qobuzRunAction(zone, action);
    res.json({ ok: true, artist, album, kind });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Favourite a pick's album in the owner's Qobuz account.
//
// A KEEP action now, not a prerequisite for listening (see Play above). It
// still earns its place: a favourite survives, and the next library scan brings
// the album in properly.
app.post("/api/smart-picks/add", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const player = state.players[0] && state.players[0].id;
  if (!player) return res.status(503).json({ error: "No player available" });
  const artist = String((req.body && req.body.artist) || "").trim();
  const album  = String((req.body && req.body.album) || "").trim();
  if (!artist || !album) return res.status(400).json({ error: "artist and album required" });
  try {
    const row = await smartResolveRow(player, artist, album);
    if (!row || row.favItemId == null) {
      // The catalogue moved, or the plugin no longer offers a favourite action
      // for it. Say so — silently reporting success would leave a card claiming
      // to be added that never appears.
      return res.status(404).json({ error: "Couldn’t find that album on Qobuz any more" });
    }
    await state.lms.qobuzAlbumFavoriteToggle(player, row.favItemId, true);
    qobuzFavCache.at = 0;                       // the heart state just changed
    res.json({ ok: true, artist, album });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});


// ---- Smart Picks: schedule + settings --------------------------------------
// Defaults chosen for an overnight build: it does 24 MusicBrainz lookups at
// one a second, plus a menu walk per candidate against the owner's own LMS.
const SMART_HOUR_DEFAULT = 4;
const smartPicksHour = () => {
  const h = parseInt(loadSettings().smartPicksHour, 10);
  return Number.isFinite(h) && h >= 0 && h <= 23 ? h : SMART_HOUR_DEFAULT;
};
// DEFAULTS OFF here, where the Roon build defaults it on — and the reason is
// the whole point of the difference. There, auto-adding is what MAKES a pick
// playable: Roon imports it overnight and it works by morning. Here a pick is
// already playable without being added, so auto-add buys nothing towards
// listening and would write five albums a day into the owner's Qobuz account
// unasked. Off is the honest default; the toggle is there for anyone who wants
// the albums kept.
const smartPicksAutoAdd = () => loadSettings().smartPicksAutoAdd === true;

// Ticks every 10 minutes and fires once the hour has been REACHED, not once it
// EQUALS — a server switched off at 04:00 still gets its picks when it comes
// back, and `attempted` stops it running twice.
function startSmartPicksMaintenance() {
  const t = setInterval(() => {
    if (!state.connected || !index.records.length) return;
    // No connected player means no Qobuz call can resolve anything. Skip
    // WITHOUT marking the day attempted, so it retries on the next tick.
    if (!(state.players[0] && state.players[0].id)) return;
    if (new Date().getHours() < smartPicksHour()) return;
    kickSmartPicks(smartDayKey(), false);
  }, 10 * 60 * 1000);
  if (t.unref) t.unref();   // never hold the process open
  return t;
}

app.get("/api/settings/smart-picks", async (req, res) => {
  res.json({
    hour: smartPicksHour(),
    auto_add: smartPicksAutoAdd(),
    service_ready: state.connected ? await serviceUsable("qobuz").catch(() => false) : false,
  });
});

app.post("/api/settings/smart-picks", (req, res) => {
  const body = req.body || {};
  const patch = {};
  if (body.hour !== undefined) {
    const h = parseInt(body.hour, 10);
    if (!Number.isFinite(h) || h < 0 || h > 23) return res.status(400).json({ error: "hour must be 0-23" });
    patch.smartPicksHour = h;
  }
  if (body.auto_add !== undefined) patch.smartPicksAutoAdd = !!body.auto_add;
  saveSettings(patch);
  res.json({ ok: true, hour: smartPicksHour(), auto_add: smartPicksAutoAdd() });
});

// Throw today's set away and choose again. The build is kicked, not awaited —
// it takes a minute or two and the request must not hold a connection open
// for it.
app.post("/api/smart-picks/rebuild", (req, res) => {
  if (!state.connected) return notConnected(res);
  const day = smartDayKey();
  smartPicks.deleteDay(day);
  const building = kickSmartPicks(day, true);
  res.json({ ok: true, building });
});

// Diagnostic: dump the RAW Qobuz-plugin menu responses so the exact live
// menu shapes can be inspected (the parsers were built without a live server).
// Read-only. GET /api/qobuz/debug?q=radiohead
app.get("/api/qobuz/debug", async (req, res) => {
  // Echoes raw plugin responses (and player ids) straight back, so it is a
  // diagnostic, not a feature — off unless diagnostics are switched on.
  if (!log.enabled("debug")) return res.status(404).json({ error: "not found" });
  if (!state.connected || !state.lms) return res.status(503).json({ error: "not connected to LMS" });
  const q = String(req.query.q || "radiohead").trim();
  const player = state.players[0] && state.players[0].id;
  const out = { version: pkg.version, player, players: state.players.map(p => ({ id: p.id, name: p.name })), q };
  const call = async (label, cmd) => { try { out[label] = await state.lms.request(player, cmd); } catch (e) { out[label] = { error: e.message }; } };
  if (!player) { out.error = "no player available (qobuz commands need a player id)"; return res.json(out); }
  await call("root", ["qobuz", "items", 0, 50, "menu:1"]);
  const items0 = (out.root && out.root.item_loop) || [];
  const rootBase = out.root && out.root.base;
  const goParams = (it) => { const go = (it.actions && it.actions.go) || (rootBase && rootBase.actions && rootBase.actions.go); return (go && go.params) || {}; };
  const searchNode = items0.find(it => it.type === "search" || /search/i.test(it.text || it.name || it.title || ""));
  out.searchNodeId = searchNode ? (goParams(searchNode).item_id != null ? String(goParams(searchNode).item_id) : (searchNode.id != null ? String(searchNode.id) : null)) : null;
  if (out.searchNodeId) {
    await call("searchPrompt", ["qobuz", "items", 0, 10, "item_id:" + out.searchNodeId, "menu:1"]);
    // The Search node returns a "New search" input template — run its go action
    // with the query substituted for __TAGGEDINPUT__ to get the real results.
    const items = (out.searchPrompt && out.searchPrompt.item_loop) || [];
    const base = out.searchPrompt && out.searchPrompt.base;
    const inp = items.find(it => {
      const go = (it.actions && it.actions.go) || (base && base.actions && base.actions.go);
      return go && go.params && /TAGGEDINPUT/i.test(String(go.params.search || ""));
    });
    if (inp) {
      const go = inp.actions.go;
      const p = { ...(base && base.actions && base.actions.go ? base.actions.go.params : {}), ...go.params, search: q };
      delete p.menu;
      const args = Object.entries(p).map(([k, v]) => k + ":" + v);
      await call("searchResults", ["qobuz", "items", 0, 10, ...args, "menu:1"]);
      // Text search returns category groups (Releases/Artists/Songs/…). Descend
      // into the albums group ("Releases"/"Albums" / albums icon) to show the
      // actual album rows for parser confirmation.
      const cats = (out.searchResults && out.searchResults.item_loop) || [];
      const albCat = cats.find(it => {
        const lbl = String(it.text || it.name || "");
        return /\b(album|release)/i.test(lbl) || /albums\.png/i.test(String(it.icon || ""));
      });
      const catGo = albCat && albCat.actions && albCat.actions.go;
      const catId = catGo && catGo.params && catGo.params.item_id;
      if (catId != null) {
        await call("albumRows", ["qobuz", "items", 0, 10, "item_id:" + catId, "menu:1"]);
        // Descend into the FIRST album to reveal its track-menu shape.
        const arows = (out.albumRows && out.albumRows.item_loop) || [];
        const a0 = arows.find(it => it.type === "playlist");
        const aGo = a0 && ((a0.actions && a0.actions.go) || (out.albumRows.base && out.albumRows.base.actions && out.albumRows.base.actions.go));
        const aId = a0 && a0.params && a0.params.item_id;
        if (aId != null) {
          await call("albumTracks", ["qobuz", "items", 0, 20, "item_id:" + aId, "menu:1"]);
          try { out.parsedAlbumTracks = await state.lms.qobuzAlbumTracks(player, aId); } catch (e) { out.parsedAlbumTracks = { error: e.message }; }
        }
      }
    }
  }
  try { out.parsedSearch = await state.lms.qobuzSearchAlbums(player, q, 6); } catch (e) { out.parsedSearch = { error: e.message }; }
  try { out.parsedFavorites = await state.lms.qobuzFavoriteAlbums(player); } catch (e) { out.parsedFavorites = { error: e.message }; }
  res.json(out);
});

app.get("/api/search/external", async (req, res) => {
  const q = String(req.query.q || "").trim();
  const LIM = 6, DEADLINE_MS = 10000;
  if (!q) return res.json({ query: q, pitchfork: [], qobuz: [] });
  const player = state.players[0] && state.players[0].id;
  // Resolved BEFORE the array is built: an `await` inside it would let the
  // Pitchfork deadline start ticking while the service probe ran, so a cold
  // probe could eat the whole 10s budget and drop the Pitchfork results.
  const qobuzOk = !!(state.connected && player) && await serviceUsable("qobuz");
  const [pf, qb] = await Promise.all([
    withDeadline(searchPitchforkReviews(q, LIM), DEADLINE_MS).catch(() => []),
    // Skip the round trip entirely when Qobuz isn't usable — the "Available on
    // Qobuz" section then never appears, rather than appearing empty after a
    // 10s deadline.
    qobuzOk
      ? withDeadline(searchQobuz(q, player, LIM), DEADLINE_MS).catch((e) => { log.debug("qobuz search failed:", e.message); return []; })
      : Promise.resolve([]),
  ]);
  res.json({ query: q, pitchfork: pf, qobuz: qb });
});

// Play / queue a Qobuz album from a search result WITHOUT adding it to the
// library. `kind`: play_now (replace + play) or queue (append). The action was
// captured from the plugin's own menu at search time (see searchQobuz).
app.post("/api/qobuz/play", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const { token, zone_or_output_id, kind } = req.body || {};
  if (!token)             return res.status(400).json({ error: "token required" });
  if (!zone_or_output_id) return res.status(400).json({ error: "zone_or_output_id required" });
  const entry = qobuzActionStore.get(token);
  if (!entry || (Date.now() - entry.at) > QOBUZ_ACTION_TTL) {
    qobuzActionStore.delete(token);
    return res.status(410).json({ error: "Search result expired — search again" });
  }
  const action = (kind === "queue") ? (entry.add || entry.play) : entry.play;
  if (!action) return res.status(400).json({ error: "action unavailable" });
  try { await state.lms.qobuzRunAction(zone_or_output_id, action); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// The user's Qobuz favourite album ids — the UI fills a heart on any library
// or search tile whose qobuz_id is in this set.
app.get("/api/qobuz/favorites", async (req, res) => {
  // Not a hard gate: an empty key set just means no hearts get filled.
  if (!await serviceUsable("qobuz")) return res.json({ keys: [], unavailable: true });
  const fav = await qobuzFavorites(req.query.refresh === "1");
  res.json({ keys: [...fav.keys] });
});

// Favourite / un-favourite a Qobuz SEARCH RESULT (heart on an album you don't
// own). Favourite-only per design — no library rescan is triggered. The album's
// menu node was captured at search time (token → go action).
app.post("/api/qobuz/favorite", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const { token, favorite } = req.body || {};
  const player = state.players[0] && state.players[0].id;
  if (!token)  return res.status(400).json({ error: "token required" });
  if (!player) return res.status(503).json({ error: "No player available" });
  const entry = qobuzActionStore.get(token);
  if (!entry || (Date.now() - entry.at) > QOBUZ_ACTION_TTL) {
    qobuzActionStore.delete(token);
    return res.status(410).json({ error: "Search result expired — search again" });
  }
  if (entry.favItemId == null) return res.status(400).json({ error: "favourite unavailable for this result" });
  try {
    const nowFav = await state.lms.qobuzAlbumFavoriteToggle(player, entry.favItemId, !!favorite);
    qobuzFavCache.at = 0;                        // invalidate the cached set
    res.json({ ok: true, favorite: nowFav });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Un-favourite a LIBRARY Qobuz album by its qobuz_id (the heart on an owned
// album). Uses the descend action captured in the favourites listing.
app.post("/api/qobuz/favorite-id", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const { title, artist, favorite } = req.body || {};
  const player = state.players[0] && state.players[0].id;
  if (!title)  return res.status(400).json({ error: "title required" });
  if (!player) return res.status(503).json({ error: "No player available" });
  const fav = await qobuzFavorites(false);
  const entry = fav.byKey.get(qobuzFavKey(title, artist));
  if (!entry || entry.itemId == null) {
    // Not currently in the favourites list — nothing to remove (re-favouriting a
    // library album isn't possible without its catalogue node; search to re-add).
    return res.status(404).json({ error: "not found in your Qobuz favourites" });
  }
  try {
    const nowFav = await state.lms.qobuzAlbumFavoriteToggle(player, entry.itemId, !!favorite);
    qobuzFavCache.at = 0;
    res.json({ ok: true, favorite: nowFav });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Native Qobuz browser: walk the plugin's menu tree (New Releases, Bestsellers,
// Genres, Playlists, …). Returns navigable `node`s and playable `album`s; albums
// carry a token for /api/qobuz/play + /api/qobuz/favorite (same as search).
app.get("/api/qobuz/browse", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const player = state.players[0] && state.players[0].id;
  if (!player) return res.status(503).json({ error: "No player available" });
  const itemId = req.query.item_id != null && req.query.item_id !== "" ? String(req.query.item_id) : null;
  const start = Math.max(0, parseInt(req.query.start || "0", 10) || 0);
  try {
    const r = await state.lms.qobuzBrowse(player, itemId, start, 50);
    const items = r.items.map(it => it.kind === "album"
      ? { kind: "album", token: qobuzActionPut(it.play, it.add, it.favItemId), title: it.title,
          subtitle: it.artist, source: "qobuz", image_key: qobuzImageKey(it.image),
          can_queue: !!it.add, can_favorite: it.favItemId != null, fav_key: it.favItemId || null }
      : { kind: "node", item_id: it.item_id, title: it.title });
    res.json({ title: r.title, total: r.total, start, items, notice: r.notice || null });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Track listing + favourite state for one Qobuz album (token from a browse /
// search result). Per-track rows get their own play token for tap-to-play.
app.get("/api/qobuz/album", async (req, res) => {
  if (!await requireService("qobuz", res)) return;
  const player = state.players[0] && state.players[0].id;
  const token = String(req.query.token || "");
  const entry = qobuzActionStore.get(token);
  if (!player) return res.status(503).json({ error: "No player available" });
  if (!entry || (Date.now() - entry.at) > QOBUZ_ACTION_TTL) return res.status(410).json({ error: "expired — reopen" });
  if (entry.favItemId == null) return res.status(400).json({ error: "album detail unavailable" });
  try {
    const r = await state.lms.qobuzAlbumTracks(player, entry.favItemId);
    const tracks = r.tracks.map(t => ({ title: t.title, artist: t.artist, duration: t.duration,
      token: qobuzActionPut(t.play, t.add, null), can_queue: !!t.add }));
    res.json({ favorite: r.favorite, can_favorite: true, tracks, notice: r.notice || null });
  } catch (e) { res.status(500).json({ error: e.message }); }
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
  log.info("log level:", levelName(),
    "(set DEBUG=1 for diagnostics, DEBUG=trace or LOG_LEVEL=trace for the firehose)");
  refreshConnection();
  const timer = setInterval(refreshConnection, 2500);
  if (timer.unref) timer.unref();
  // 12-hour label auto-rescan. maybeAutoRescan is a cheap no-op until its own
  // interval elapses (and while a scan is running), so a frequent tick is fine
  // and means a long-lived instance refreshes labels without a UI visit.
  const labelTimer = setInterval(() => { if (state.connected) labels.maybeAutoRescan(); }, 60 * 60 * 1000);
  if (labelTimer.unref) labelTimer.unref();
  startSmartPicksMaintenance();
});

module.exports = app;
