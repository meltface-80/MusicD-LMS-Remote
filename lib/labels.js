// ---------------------------------------------------------------------------
// Record-label index + background scanner.
//
// LMS has no first-class "label" facet the way it has genres or years, so —
// exactly like the sibling Roon build of MusicD Remote — labels are DERIVED
// per album from a cascade of sources and cached. Each album is looked up once;
// results persist so a rescan only touches new albums.
//
// Source cascade (highest priority first):
//   1. data/labels-override.json  — hand-curated user overrides (read-only).
//   2. File tags (LABEL / ORGANIZATION) read from a mounted music directory,
//      with an optional "label folder depth" override for libraries organised
//      into per-label folders. Requires -v /music:ro; skipped otherwise.
//   3. External metadata APIs, walked in order and stopping at the first hit:
//      iTunes Search → Qobuz (web) → TheAudioDB → MusicBrainz → Discogs.
//   Label logos come from Fan Art TV (needs a MusicBrainz label MBID + a free
//   key) with a Discogs fallback (needs a personal token).
//
// HOW THIS DEVIATES FROM THE ROON BUILD:
//   - The sibling stores caches in a better-sqlite3 database. This repo has NO
//     native dependencies by design (see the Dockerfile), so persistence here
//     is plain JSON files under dataDir/cache/, following lib/plays.js's
//     JSON-instead-of-SQLite pattern. Writes are batched/debounced during a
//     scan so a library of thousands of albums doesn't rewrite the file on
//     every single lookup.
//   - Everything the module needs from the rest of the app (the album list,
//     the settings tokens, the normaliser) is INJECTED via makeLabels(deps),
//     so this file has no dependency on index.js and is unit-testable offline.
//
// The rate limits, batch sizes, User-Agent and abort-on-429/403 circuit
// breakers in the network passes are deliberately conservative: they protect
// users from being IP-blocked by the free APIs. Do not loosen them.
// ---------------------------------------------------------------------------
"use strict";

const fs = require("fs");
const path = require("path");

let _qobuzAppId = "942852567";
try { _qobuzAppId = require("./qobuz").APP_ID || _qobuzAppId; } catch (e) { /* qobuz module optional here */ }

const MB_USER_AGENT = process.env.MB_USER_AGENT ||
  "MusicD-LMS-Remote/1.0 (https://github.com/meltface-80/MusicD-LMS-Remote)";
const BROWSER_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

// Names that look like a management/booking/agency credit rather than a record
// label — dropped from every source so they never pollute the index.
const NON_LABEL_RE = /\b(management|agency|agencies|booking|touring|representation|ministry|foundation|fund)\b/i;

// Strip common corporate suffixes so "ACT Music" and "ACT", "Blue Note Records"
// and "Blue Note" all map to the same group key. Applied twice to catch
// "XYZ Music Records".
const LABEL_SUFFIX_RE = /\s+(Records?|Recordings?|Music|Label|Labels|Group|Entertainment|Productions?|Publishing|Inc\.?|Ltd\.?|LLC|GmbH|S\.A\.?|s\.r\.l\.?|Verlag|Editions?|Edition)\.?\s*$/i;

// Strip country / regional qualifiers so "[PIAS] America" and "[PIAS] Belgium"
// both group under "[PIAS]", and "Universal Music Canada" groups with
// "Universal Music France". Multi-word countries come first so "United States"
// is stripped before "States".
const COUNTRY_REGION_SUFFIX_RE = /\s+(United\s+States|United\s+Kingdom|New\s+Zealand|South\s+Africa|Latin\s+America|North\s+America|Group\s+International|US|USA|UK|America|Canada|France|Germany|Belgium|Russia|Australia|Japan|Italy|Spain|Netherlands|Holland|Ireland|Sweden|Norway|Denmark|Finland|Poland|Brazil|Mexico|Argentina|Chile|China|Korea|India|Portugal|Switzerland|Austria|Romania|Greece|Hungary|Turkey|International|Classics?|Cooperative|Global|Worldwide|Latino|Nordic|Iberian|Benelux|Scandinavia|Asia|Europe|Africa|Pacific|APAC)\b\s*$/i;

function isLikelyNotALabel(name) {
  return !name || NON_LABEL_RE.test(name);
}

// The grouping key: strip qualifiers, then reduce to a bare alphanumeric slug.
function labelGroupKey(name) {
  if (!name) return "";
  const s = name.trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(COUNTRY_REGION_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(LABEL_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(LABEL_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(COUNTRY_REGION_SUFFIX_RE, "").trim();
  return s.toLowerCase().replace(/[^a-z0-9]/g, "");
}

// The display name the group is shown under: the same stripping, but keeping
// the original casing and punctuation (so "[PIAS]" stays "[PIAS]").
function canonicalLabelName(name) {
  if (!name) return name;
  return name.trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(COUNTRY_REGION_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(LABEL_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(LABEL_SUFFIX_RE, "").trim()
    .replace(/[,;:]+$/, "").trim()
    .replace(COUNTRY_REGION_SUFFIX_RE, "").trim();
}

function sanitizeDiscogsSearchTerm(name) {
  return name.replace(/^[^a-z0-9]+|[^a-z0-9]+$/gi, "").trim() || name;
}

// ---------------------------------------------------------------------------
// HTTP helpers (module-local). Node's global fetch; every call is deadlined so
// a wedged endpoint can't hang a scan pass forever. A non-2xx throws with the
// status in the message so callers can pattern-match 429/403 for abort.
// ---------------------------------------------------------------------------
async function httpJson(url, headers, timeoutMs = 8000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctl.signal });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return await res.json();
  } finally { clearTimeout(timer); }
}
async function httpText(url, headers, timeoutMs = 12000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctl.signal, redirect: "follow" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return await res.text();
  } finally { clearTimeout(timer); }
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------
function makeLabels(deps = {}) {
  const {
    dataDir,
    getAlbums = () => [],
    normalize = (s) => String(s || "").toLowerCase().trim(),
    getDiscogsToken = () => "",
    getFanartKey = () => "",
    getLabelFolderDepth = () => 0,
    musicDir = process.env.MUSIC_DIR || "/music",
    debug = false,
  } = deps;

  const CACHE_DIR = path.join(dataDir, "cache");
  const LOGOS_DIR = path.join(CACHE_DIR, "logos");
  const FILES = {
    names:  path.join(CACHE_DIR, "labels-cache.json"),
    mbid:   path.join(CACHE_DIR, "labels-mbid.json"),
    logo:   path.join(CACHE_DIR, "labels-logo.json"),
    merges: path.join(CACHE_DIR, "labels-merges.json"),
    lastScan: path.join(CACHE_DIR, "last-labels-scan.txt"),
  };
  const LOG_FILE = path.join(dataDir, "labels-scan.log");
  const OVERRIDE_FILE = path.join(dataDir, "labels-override.json");
  const LOG_MAX = 100 * 1024; // rotate at ~100KB

  // In-memory caches — the primary lookup path (disk is only for persistence).
  const labelDiskCache = new Map(); // album key → label name
  const labelMbidCache = new Map(); // group key → MusicBrainz MBID | null(tried)
  const labelLogoCache = new Map(); // group key → logo URL | null(tried, none)
  const labelMerges    = new Map(); // source groupKey → { targetKey, targetDisplay, sourceDisplay }
  const labelsOverride = new Map(); // album key → label (read-only override file)
  const discogsLogoTried = new Set(); // per-session dedup, resets on restart

  const labelsIndex = {
    map:      new Map(), // groupKey → { display, image_key, mbid, logo_url, albums:[{offset,title,subtitle,image_key}] }
    count:    0,
    builtAt:  0,
    progress: 0,
    building: false,
  };

  function log(msg) { if (debug) console.log(msg); }

  function appendScanLog(message) {
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      const line = new Date().toISOString() + " " + message + "\n";
      try {
        const stat = fs.statSync(LOG_FILE);
        if (stat.size >= LOG_MAX) { fs.writeFileSync(LOG_FILE, line); return; }
      } catch (e) { /* no log file yet */ }
      fs.appendFileSync(LOG_FILE, line);
    } catch (e) { /* never throw from the log helper */ }
  }

  // -------------------------------------------------------------------------
  // Persistence — JSON files, batched. Each cache marks itself dirty on write
  // and a shared debounce flushes them a couple of seconds later, so a scan
  // that resolves thousands of albums doesn't rewrite the file thousands of
  // times. flushNow() forces an immediate write at pass boundaries / shutdown.
  // -------------------------------------------------------------------------
  const dirty = { names: false, mbid: false, logo: false, merges: false };
  let flushTimer = null;

  function writeJson(file, obj) {
    try {
      fs.mkdirSync(CACHE_DIR, { recursive: true });
      fs.writeFileSync(file, JSON.stringify(obj));
    } catch (e) { if (debug) console.error("[labels] write failed:", file, e.message); }
  }
  function readJson(file) {
    try { return JSON.parse(fs.readFileSync(file, "utf8")); }
    catch (e) { return null; }
  }

  function flushNow() {
    if (dirty.names) {
      writeJson(FILES.names, { entries: [...labelDiskCache].map(([key, label]) => ({ key, label })) });
      dirty.names = false;
    }
    if (dirty.mbid) {
      // Persist only real MBIDs — the null "tried, not found" markers are
      // in-memory only (retried on restart), matching the sibling build.
      writeJson(FILES.mbid, { entries: [...labelMbidCache].filter(([, v]) => v).map(([groupKey, mbid]) => ({ groupKey, mbid })) });
      dirty.mbid = false;
    }
    if (dirty.logo) {
      writeJson(FILES.logo, { entries: [...labelLogoCache].map(([groupKey, logoUrl]) => ({ groupKey, logoUrl: logoUrl || null })) });
      dirty.logo = false;
    }
    if (dirty.merges) {
      writeJson(FILES.merges, { entries: [...labelMerges].map(([sourceKey, m]) => ({
        sourceKey, sourceDisplay: m.sourceDisplay, targetKey: m.targetKey, targetDisplay: m.targetDisplay
      })) });
      dirty.merges = false;
    }
  }
  function scheduleFlush() {
    if (flushTimer) return;
    flushTimer = setTimeout(() => { flushTimer = null; flushNow(); }, 2000);
    if (flushTimer.unref) flushTimer.unref();
  }

  // Write helpers — update the in-memory Map and mark the file dirty.
  function setLabelName(key, label) { labelDiskCache.set(key, label); dirty.names = true; scheduleFlush(); }
  function setLabelMbid(groupKey, mbid) { labelMbidCache.set(groupKey, mbid); if (mbid) { dirty.mbid = true; scheduleFlush(); } }
  function setLabelLogo(groupKey, url) { labelLogoCache.set(groupKey, url); dirty.logo = true; scheduleFlush(); }

  function saveLastScanTime() {
    try { fs.mkdirSync(CACHE_DIR, { recursive: true }); fs.writeFileSync(FILES.lastScan, String(Date.now())); }
    catch (e) { /* best-effort */ }
  }
  function loadLastScanTime() {
    const raw = (() => { try { return fs.readFileSync(FILES.lastScan, "utf8").trim(); } catch (e) { return ""; } })();
    const ts = parseInt(raw, 10);
    if (Number.isFinite(ts) && ts > 0) labelsIndex.builtAt = ts;
  }

  // Load every persisted cache into memory at startup. Bad label entries (that
  // slipped in before the NON_LABEL_RE filter existed) are evicted on load.
  function loadCaches() {
    const names = readJson(FILES.names);
    if (names && Array.isArray(names.entries)) {
      for (const e of names.entries) {
        if (!e || !e.key || !e.label) continue;
        if (isLikelyNotALabel(e.label)) { dirty.names = true; continue; } // drop, don't re-add
        labelDiskCache.set(e.key, e.label);
      }
    }
    const mbid = readJson(FILES.mbid);
    if (mbid && Array.isArray(mbid.entries)) {
      for (const e of mbid.entries) if (e && e.groupKey && e.mbid) labelMbidCache.set(e.groupKey, e.mbid);
    }
    const logo = readJson(FILES.logo);
    if (logo && Array.isArray(logo.entries)) {
      for (const e of logo.entries) if (e && typeof e.groupKey === "string") labelLogoCache.set(e.groupKey, e.logoUrl || null);
    }
    const merges = readJson(FILES.merges);
    if (merges && Array.isArray(merges.entries)) {
      for (const e of merges.entries) {
        if (e && e.sourceKey && e.targetKey) {
          labelMerges.set(e.sourceKey, { targetKey: e.targetKey, targetDisplay: e.targetDisplay || e.targetKey, sourceDisplay: e.sourceDisplay || e.sourceKey });
        }
      }
    }
    loadLastScanTime();
    loadOverride();
    log("[labels] caches loaded: " + labelDiskCache.size + " names, " + labelMbidCache.size + " mbids, " +
        labelLogoCache.size + " logos, " + labelMerges.size + " merges, " + labelsOverride.size + " overrides");
  }

  function loadOverride() {
    labelsOverride.clear();
    const data = readJson(OVERRIDE_FILE);
    if (!data) return;
    const albums = Array.isArray(data) ? data : (data && data.albums ? data.albums : []);
    for (const e of albums) {
      if (e && e.label) {
        const key = normalize(e.title || "") + "||" + normalize(e.artist || "");
        labelsOverride.set(key, e.label);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Index construction
  // -------------------------------------------------------------------------
  function albumKey(al) { return normalize(al.title) + "||" + normalize(al.subtitle); }

  function labelsIndexAddAlbum(labelName, album) {
    if (!labelName || !album) return;
    let groupKey = labelGroupKey(labelName);
    if (!groupKey) return;
    // Redirect manually merged source labels to their canonical target.
    const merge = labelMerges.get(groupKey);
    let displayName = canonicalLabelName(labelName);
    if (merge) { groupKey = merge.targetKey; displayName = merge.targetDisplay; }
    let entry = labelsIndex.map.get(groupKey);
    if (!entry) {
      entry = {
        display:   displayName,
        image_key: album.image_key || null,
        mbid:      labelMbidCache.get(groupKey) || null,
        logo_url:  labelLogoCache.has(groupKey) ? (labelLogoCache.get(groupKey) || null) : null,
        albums:    [],
      };
      labelsIndex.map.set(groupKey, entry);
      labelsIndex.count = labelsIndex.map.size;
    }
    if (!entry.mbid && labelMbidCache.has(groupKey)) entry.mbid = labelMbidCache.get(groupKey);
    if (!entry.logo_url && labelLogoCache.has(groupKey)) entry.logo_url = labelLogoCache.get(groupKey) || null;
    if (!entry.image_key && album.image_key) entry.image_key = album.image_key;
    if (!entry.albums.some(a => a.offset === album.offset)) {
      entry.albums.push({ offset: album.offset, title: album.title, subtitle: album.subtitle, image_key: album.image_key, source: album.source || null });
    }
  }

  // Seed the in-memory index from persisted caches + the override file — no
  // network. Priority per album: override file → disk cache. (The API sources
  // fill in everything else during runScan.)
  function seedFromCache() {
    for (const al of getAlbums()) {
      const key = albumKey(al);
      const override = labelsOverride.get(key);
      if (override) { labelsIndexAddAlbum(override, al); continue; }
      const diskLabel = labelDiskCache.get(key);
      if (diskLabel && !isLikelyNotALabel(diskLabel)) labelsIndexAddAlbum(diskLabel, al);
    }
    labelsIndex.count = labelsIndex.map.size;
    log("[labels] seeded: " + labelsIndex.count + " labels");
  }

  // Rebuild the map from caches after a merge/unmerge or an album-index rebuild.
  // Album offsets are a snapshot; a library edit shifts them, so re-projecting
  // the fresh album list keeps the labels browser + Home rows pointing at the
  // right albums.
  function rebuildLabelsMap() {
    labelsIndex.map.clear();
    labelsIndex.count = 0;
    for (const al of getAlbums()) {
      const key = albumKey(al);
      const override = labelsOverride.get(key);
      if (override) { labelsIndexAddAlbum(override, al); continue; }
      const diskLabel = labelDiskCache.get(key);
      if (diskLabel && !isLikelyNotALabel(diskLabel)) labelsIndexAddAlbum(diskLabel, al);
    }
    labelsIndex.count = labelsIndex.map.size;
  }

  // Read-only per-album label lookup (override → disk cache), used e.g. by the
  // wall display to project the live album list onto a label without depending
  // on the labels-index snapshot's stored offsets.
  function labelForAlbum(album) {
    const key = albumKey(album);
    return labelsOverride.get(key) || labelDiskCache.get(key) || null;
  }

  // -------------------------------------------------------------------------
  // Network passes — per-source label fetchers with their own rate limiters.
  // -------------------------------------------------------------------------
  const ITUNES_BLOCKED = Symbol("itunes_blocked");
  let itunesLastBatch = 0, mbLastReq = 0, tadbLastReq = 0, discogsLastReq = 0, qobuzLastReq = 0;
  const wait = (last, gapMs) => {
    const elapsed = Date.now() - last;
    return elapsed < gapMs ? new Promise(r => setTimeout(r, gapMs - elapsed)) : Promise.resolve();
  };
  async function itunesBatchWait() { await wait(itunesLastBatch, 500); itunesLastBatch = Date.now(); }
  async function mbWait()          { await wait(mbLastReq, 1100);      mbLastReq = Date.now(); }
  async function tadbWait()        { await wait(tadbLastReq, 1100);    tadbLastReq = Date.now(); }
  async function discogsWait()     { await wait(discogsLastReq, 1100); discogsLastReq = Date.now(); }
  async function qobuzWait()       { await wait(qobuzLastReq, 700);    qobuzLastReq = Date.now(); }

  function mbQuote(s) { return String(s).replace(/[+\-&|!(){}\[\]^"~*?:\\\/]/g, "\\$&"); }

  async function fetchLabelFromiTunes(title, artist) {
    if (!title) return null;
    const term = [title, artist].filter(Boolean).join(" ");
    const url = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&entity=album&media=music&limit=5`;
    try {
      const json = await httpJson(url, { "User-Agent": MB_USER_AGENT }, 10000);
      const results = json && json.results;
      if (!Array.isArray(results) || !results.length) return null;
      const normTitle = normalize(title);
      let match = results.find(r => normalize(r.collectionName || "") === normTitle);
      if (!match && artist) {
        const normArtist = normalize(artist);
        match = results.find(r => normalize(r.artistName || "") === normArtist);
      }
      if (!match) match = results[0];
      const label = match && match.recordLabel;
      if (!label || isLikelyNotALabel(label)) return null;
      return label;
    } catch (e) {
      if (e.message && /429|403/.test(e.message)) return ITUNES_BLOCKED;
      if (debug) console.error("[labels:itunes]", e.message);
      return null;
    }
  }

  async function fetchLabelFromMusicBrainz(title, artist) {
    if (!title) return null;
    await mbWait();
    let q = `release:"${mbQuote(title)}"`;
    if (artist) q += ` AND artist:"${mbQuote(artist)}"`;
    const url = `https://musicbrainz.org/ws/2/release/?query=${encodeURIComponent(q)}&fmt=json&limit=5`;
    try {
      const json = await httpJson(url, { "User-Agent": MB_USER_AGENT }, 20000);
      for (const r of json.releases || []) {
        const li = (r["label-info"] || [])[0];
        const labelObj = li && li.label;
        if (labelObj && labelObj.name) {
          const year = (r.date && /^\d{4}/.test(r.date)) ? r.date.slice(0, 4) : null;
          return { label: labelObj.name, mbid: labelObj.id || null, year };
        }
      }
    } catch (e) { if (debug) console.error("[labels:mb]", e.message); }
    return null;
  }

  async function fetchLabelMbidFromMusicBrainz(labelName) {
    if (!labelName) return null;
    await mbWait();
    const q = `label:"${mbQuote(labelName)}"`;
    const url = `https://musicbrainz.org/ws/2/label/?query=${encodeURIComponent(q)}&fmt=json&limit=1`;
    try {
      const json = await httpJson(url, { "User-Agent": MB_USER_AGENT });
      const labels = json && json.labels;
      if (Array.isArray(labels) && labels.length) return labels[0].id || null;
    } catch (e) { if (debug) console.error("[labels:mb:label]", e.message); }
    return null;
  }

  async function fetchLabelFromTheAudioDB(title, artist) {
    if (!title || !artist) return null;
    await tadbWait();
    const url = `https://www.theaudiodb.com/api/v1/json/2/searchalbum.php?s=${encodeURIComponent(artist)}&a=${encodeURIComponent(title)}`;
    try {
      const json = await httpJson(url, { "User-Agent": MB_USER_AGENT }, 6000);
      const albums = json && json.album;
      if (!Array.isArray(albums) || !albums.length) return null;
      const normTitle = normalize(title);
      const match = albums.find(a => normalize(a.strAlbum || "") === normTitle) || albums[0];
      const label = match && match.strLabel;
      if (!label || isLikelyNotALabel(label)) return null;
      return label;
    } catch (e) { if (debug) console.error("[labels:theaudiodb]", e.message); return null; }
  }

  async function fetchLabelFromDiscogs(title, artist) {
    const discogsToken = getDiscogsToken();
    if (!title || !discogsToken) return null;
    await discogsWait();
    const params = new URLSearchParams({ type: "release", release_title: title });
    if (artist) params.set("artist", artist);
    const url = `https://api.discogs.com/database/search?${params}`;
    try {
      const json = await httpJson(url, { "Authorization": `Discogs token=${discogsToken}`, "User-Agent": MB_USER_AGENT });
      const results = json && json.results;
      if (!Array.isArray(results) || !results.length) return null;
      const normTitle = normalize(title);
      let match = results.find(r => normalize(r.title || "").includes(normTitle));
      if (!match) match = results[0];
      const label = match && Array.isArray(match.label) && match.label[0];
      if (!label || isLikelyNotALabel(label)) return null;
      return label;
    } catch (e) { if (debug) console.error("[labels:discogs]", e.message); return null; }
  }

  // Qobuz web scrape — label + year off the public album page. Used for
  // streaming-only libraries (no /music mount) where Qobuz is the likely
  // source, so it resolves many iTunes-misses before the slow TADB→MB→Discogs
  // cascade. No token needed (public pages); the unofficial APP_ID is only used
  // if a JSON path is ever added.
  async function fetchLabelFromQobuz(title, artist) {
    if (!title) return null;
    try {
      const q = `${title} ${artist || ""}`.trim();
      await qobuzWait();
      const searchHtml = await httpText(
        `https://www.qobuz.com/us-en/search?q=${encodeURIComponent(q)}`,
        { "User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9" }
      );
      const linkRe = /\/(?:us-en\/)?album\/([^"'\/\s]+)\/([a-z0-9]+)/g;
      const seen = new Map();
      let m;
      while ((m = linkRe.exec(searchHtml)) !== null) { if (!seen.has(m[2])) seen.set(m[2], m[1]); }
      if (seen.size === 0) return null;
      const artistToks = normalize(artist || "").split(" ").filter(Boolean);
      const artistFirst = artistToks.length > 1 && /^(the|a|an)$/.test(artistToks[0]) ? artistToks[1] : (artistToks[0] || "");
      const titleTokens = normalize(title).split(" ").filter(w => w.length > 3);
      const titleCheck = titleTokens.length > 0 ? titleTokens : normalize(title).split(" ").filter(Boolean).slice(0, 1);
      let bestScore = -1, chosenSlug = null, chosenId = null;
      for (const [id, slug] of seen) {
        const sn = slug.toLowerCase();
        if (artistFirst && !sn.includes(artistFirst)) continue;
        const score = titleCheck.filter(tok => sn.includes(tok)).length;
        if (score > bestScore) { bestScore = score; chosenSlug = slug; chosenId = id; }
      }
      const minScore = Math.max(1, Math.min(titleCheck.length, 2));
      if (!chosenSlug || bestScore < minScore) return null;
      await qobuzWait();
      const albumHtml = await httpText(
        `https://www.qobuz.com/us-en/album/${chosenSlug}/${chosenId}`,
        { "User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9" }
      );
      const rel = albumHtml.match(/Released\s+on\s+([\d\/]+)\s*by\s*<[^>]*>([^<]+)</i);
      if (!rel) return null;
      const parts = rel[1].split("/");
      const yp = parts[parts.length - 1];
      let year = null;
      if (yp.length === 2) {
        const n = parseInt(yp, 10);
        const cur2 = new Date().getFullYear() % 100;
        year = String(n <= cur2 ? 2000 + n : 1900 + n);
      } else if (yp.length === 4) { year = yp; }
      const label = rel[2].trim();
      if (isLikelyNotALabel(label)) return { label: null, year };
      return { label, year };
    } catch (e) { if (debug) console.error("[labels:qobuz]", e.message); return null; }
  }

  // -------------------------------------------------------------------------
  // File-tag pass — read LABEL / ORGANIZATION from a mounted music directory.
  // music-metadata is loaded via dynamic import with a graceful fallback: the
  // package is an optional dependency, so the whole app still runs (labels just
  // fall back to the API cascade) if it isn't installed.
  // -------------------------------------------------------------------------
  function musicDirMounted() {
    try { return fs.statSync(musicDir).isDirectory(); } catch (e) { return false; }
  }

  async function buildFileLabelMap(onProgress) {
    const map = new Map();
    if (!musicDirMounted()) return map;
    let mm;
    try { mm = await import("music-metadata"); }
    catch (e) { if (debug) console.error("[labels:files] music-metadata not available:", e.message); return map; }
    const parseFile = mm.parseFile || (mm.default && mm.default.parseFile);
    if (!parseFile) return map;

    const folderDepth = getLabelFolderDepth();
    const AUDIO_RE = /\.(flac|mp3|m4a|aac|ogg|opus|wv|ape|wav|aiff?)$/i;
    const MAX_DEPTH = 3;
    let processed = 0;

    async function scanDir(dirPath, depth) {
      if (depth > MAX_DEPTH) return;
      let entries;
      try { entries = fs.readdirSync(dirPath, { withFileTypes: true }); }
      catch (e) { return; /* permission denied / vanished — skip */ }
      const audioFile = entries.find(e => e.isFile() && AUDIO_RE.test(e.name));
      if (audioFile) {
        processed++;
        if (onProgress && processed % 50 === 0) onProgress(processed);
        try {
          const meta = await parseFile(path.join(dirPath, audioFile.name), { duration: false, skipCovers: true });
          let label = (meta.common.label && meta.common.label[0]) || meta.common.organization || null;
          // Label-folder organisation: take the label from the folder at the
          // configured depth under the music root, overriding the per-file tag
          // (often the granular pressing label, not the parent one filed under).
          if (folderDepth > 0) {
            const rel = path.relative(musicDir, dirPath).split(path.sep).filter(Boolean);
            const folderLabel = rel[folderDepth - 1];
            if (folderLabel) label = folderLabel;
          }
          const album = meta.common.album;
          const albumartist = meta.common.albumartist
            || (meta.common.artists && meta.common.artists[0]) || meta.common.artist || null;
          if (label && !isLikelyNotALabel(label) && album) {
            const key = normalize(album) + "||" + normalize(albumartist || "");
            if (!map.has(key)) map.set(key, label);
          }
        } catch (e) { /* unreadable — skip */ }
      }
      for (const entry of entries) {
        if (entry.isDirectory()) await scanDir(path.join(dirPath, entry.name), depth + 1);
      }
    }
    try { await scanDir(musicDir, 0); }
    catch (e) { if (debug) console.error("[labels:files] scan error:", e.message); }
    log("[labels:files] file scan found " + map.size + " labels");
    return map;
  }

  // -------------------------------------------------------------------------
  // The scan orchestration — multi-pass, persisted, resumable.
  // -------------------------------------------------------------------------
  async function saveLabelEntry(key, label, knownMbid, al) {
    if (isLikelyNotALabel(label)) return;
    setLabelName(key, label);
    labelsIndexAddAlbum(label, al);
    const gk = labelGroupKey(label);
    if (gk && !labelMbidCache.has(gk)) {
      const resolvedMbid = knownMbid || await fetchLabelMbidFromMusicBrainz(label);
      if (resolvedMbid) {
        setLabelMbid(gk, resolvedMbid);
        const entry = labelsIndex.map.get(gk);
        if (entry && !entry.mbid) entry.mbid = resolvedMbid;
      } else {
        labelMbidCache.set(gk, null); // in-memory only; retried next restart
      }
    }
  }

  async function runScan() {
    if (labelsIndex.building) return;
    const albums = getAlbums();
    if (!albums.length) return;
    labelsIndex.building = true;
    labelsIndex.progress = 0;
    try {
      seedFromCache();

      // Pass 0: file tags (unconditional so corrected tags override stale API
      // cache entries even on an all-cached rescan).
      const estimate = albums.length || 1000;
      const fileLabelMap = musicDirMounted()
        ? await buildFileLabelMap((n) => { labelsIndex.progress = Math.min(0.15, n / estimate); })
        : new Map();
      if (fileLabelMap.size) {
        let overrideCount = 0;
        for (const [key, fileLabel] of fileLabelMap) {
          const cached = labelDiskCache.get(key);
          if (cached && labelGroupKey(cached) !== labelGroupKey(fileLabel)) { setLabelName(key, fileLabel); overrideCount++; }
        }
        if (overrideCount) { rebuildLabelsMap(); appendScanLog("[labels:files] corrected " + overrideCount + " stale cache entries from file tags"); }
      }

      const toScan = albums.filter(al => {
        const key = albumKey(al);
        return !labelsOverride.has(key) && !labelDiskCache.has(key);
      });
      if (!toScan.length) {
        finishScan();
        appendScanLog("[labels] scan: all albums already cached (" + labelsIndex.count + " labels)");
        return;
      }

      const total = albums.length;
      const alreadyDone = total - toScan.length;
      const scanCount = toScan.length;
      const streamingOnly = !musicDirMounted();
      // Pass-index map + cumulative weights so the progress bar advances across
      // the whole scan. Streaming-only libraries get a Qobuz pass; local ones
      // spend that slice on TADB instead.
      const PASS = streamingOnly
        ? { files: 0, itunes: 1, qobuz: 2, tadb: 3, mb: 4, discogs: 5 }
        : { files: 0, itunes: 1, tadb: 3, mb: 4, discogs: 5 };
      const PASS_ENDS = streamingOnly
        ? [0.05, 0.15, 0.45, 0.60, 0.85, 1.00]
        : [0.10, 0.20, 0.30, 0.55, 0.80, 1.00];
      const basePct = total > 0 ? alreadyDone / total : 0;
      const scanPct = 1 - basePct;
      function passProgress(passIdx, pos, passTotal) {
        const start = passIdx > 0 ? PASS_ENDS[passIdx - 1] : 0;
        const end = PASS_ENDS[passIdx];
        const frac = passTotal > 0 ? pos / passTotal : 1;
        return Math.min(1, basePct + scanPct * (start + (end - start) * frac));
      }
      let done = 0;

      appendScanLog("[labels] scan started: " + toScan.length + " albums to look up (" + alreadyDone + " already cached)");

      // Fill file-tag hits first; the rest go to the API cascade.
      const needsApiScan = [];
      for (const al of toScan) {
        const key = albumKey(al);
        const fileLabel = fileLabelMap.get(key);
        if (fileLabel) { await saveLabelEntry(key, fileLabel, null, al); done++; labelsIndex.progress = passProgress(PASS.files, done, scanCount); }
        else needsApiScan.push(al);
      }
      flushNow();

      // Pass 1: iTunes — 3 concurrent, 500ms between batches, abort on 429/403.
      const needsAudioDB = [];
      const ITUNES_BATCH = 3;
      let itunesAborted = false;
      const itunesCheck = async (al) => {
        if (itunesAborted) { needsAudioDB.push(al); return; }
        const key = albumKey(al);
        try {
          const label = await fetchLabelFromiTunes(al.title, al.subtitle);
          if (label === ITUNES_BLOCKED) { itunesAborted = true; appendScanLog("[labels] pass 1 (iTunes): rate-limited — aborting"); needsAudioDB.push(al); }
          else if (label && !isLikelyNotALabel(label)) { await saveLabelEntry(key, label, null, al); }
          else needsAudioDB.push(al);
        } catch (e) { needsAudioDB.push(al); }
        done++;
        labelsIndex.progress = passProgress(PASS.itunes, done, scanCount);
      };
      for (let i = 0; i < needsApiScan.length; i += ITUNES_BATCH) {
        if (itunesAborted) { needsAudioDB.push(...needsApiScan.slice(i)); break; }
        await itunesBatchWait();
        await Promise.allSettled(needsApiScan.slice(i, i + ITUNES_BATCH).map(itunesCheck));
      }
      flushNow();
      appendScanLog("[labels] pass 1 (iTunes): done, " + needsAudioDB.length + " forwarded" + (itunesAborted ? " (aborted)" : ""));

      // Pass Q: Qobuz (streaming-only). Serial, 10-consecutive-error breaker.
      let needsTadb = needsAudioDB;
      if (streamingOnly && needsAudioDB.length) {
        needsTadb = [];
        appendScanLog("[labels] pass Q (Qobuz): " + needsAudioDB.length + " albums");
        let consec = 0, aborted = false;
        for (let qi = 0; qi < needsAudioDB.length; qi++) {
          if (aborted) { needsTadb.push(...needsAudioDB.slice(qi)); labelsIndex.progress = passProgress(PASS.qobuz, needsAudioDB.length, needsAudioDB.length); break; }
          const al = needsAudioDB[qi];
          const key = albumKey(al);
          try {
            const q = await fetchLabelFromQobuz(al.title, al.subtitle);
            if (q && q.label && !isLikelyNotALabel(q.label)) { await saveLabelEntry(key, q.label, null, al); consec = 0; }
            else { needsTadb.push(al); consec = 0; }
          } catch (e) { consec++; needsTadb.push(al); if (consec >= 10) { aborted = true; appendScanLog("[labels] pass Q (Qobuz): 10 consecutive errors — aborting"); } }
          labelsIndex.progress = passProgress(PASS.qobuz, qi + 1, needsAudioDB.length);
        }
        flushNow();
        appendScanLog("[labels] pass Q (Qobuz): complete, " + needsTadb.length + " forwarded" + (aborted ? " (aborted)" : ""));
      }

      // Pass 2: TheAudioDB — serial, 1 req/sec, 10-consecutive-error breaker.
      const needsMB = [];
      { let consec = 0, aborted = false;
        if (needsTadb.length) appendScanLog("[labels] pass 2 (TheAudioDB): " + needsTadb.length + " albums");
        for (let ti = 0; ti < needsTadb.length; ti++) {
          if (aborted) { needsMB.push(...needsTadb.slice(ti)); labelsIndex.progress = passProgress(PASS.tadb, needsTadb.length, needsTadb.length); break; }
          const al = needsTadb[ti];
          const key = albumKey(al);
          try {
            const label = await fetchLabelFromTheAudioDB(al.title, al.subtitle);
            if (label) { await saveLabelEntry(key, label, null, al); consec = 0; }
            else { needsMB.push(al); consec = 0; }
          } catch (e) { consec++; needsMB.push(al); if (consec >= 10) { aborted = true; appendScanLog("[labels] pass 2 (TheAudioDB): 10 consecutive errors — aborting"); } }
          labelsIndex.progress = passProgress(PASS.tadb, ti + 1, needsTadb.length);
        }
        flushNow();
      }

      // Pass 3: MusicBrainz — serial, rate-limited, 10-consecutive-error breaker.
      const needsDiscogs = [];
      { let consec = 0, aborted = false;
        if (needsMB.length) appendScanLog("[labels] pass 3 (MusicBrainz): " + needsMB.length + " albums");
        for (let mi = 0; mi < needsMB.length; mi++) {
          if (aborted) { needsDiscogs.push(...needsMB.slice(mi)); labelsIndex.progress = passProgress(PASS.mb, needsMB.length, needsMB.length); break; }
          const al = needsMB[mi];
          const key = albumKey(al);
          try {
            const mb = await fetchLabelFromMusicBrainz(al.title, al.subtitle);
            if (mb) { await saveLabelEntry(key, mb.label, mb.mbid, al); consec = 0; }
            else { needsDiscogs.push(al); consec = 0; }
          } catch (e) { consec++; needsDiscogs.push(al); if (consec >= 10) { aborted = true; appendScanLog("[labels] pass 3 (MusicBrainz): 10 consecutive errors — aborting"); } }
          labelsIndex.progress = passProgress(PASS.mb, mi + 1, needsMB.length);
        }
        flushNow();
      }

      // Pass 4: Discogs — serial, last resort, 5-minute cap + error breaker.
      { let consec = 0, aborted = false;
        const deadline = Date.now() + 5 * 60 * 1000;
        if (needsDiscogs.length) appendScanLog("[labels] pass 4 (Discogs): " + needsDiscogs.length + " albums");
        for (let di = 0; di < needsDiscogs.length; di++) {
          if (aborted) { labelsIndex.progress = passProgress(PASS.discogs, needsDiscogs.length, needsDiscogs.length); break; }
          const al = needsDiscogs[di];
          const key = albumKey(al);
          try {
            const label = await fetchLabelFromDiscogs(al.title, al.subtitle);
            if (label) { await saveLabelEntry(key, label, null, al); consec = 0; }
            else consec = 0;
          } catch (e) { consec++; if (consec >= 10) { aborted = true; appendScanLog("[labels] pass 4 (Discogs): 10 consecutive errors — aborting"); } }
          labelsIndex.progress = passProgress(PASS.discogs, di + 1, needsDiscogs.length);
          if (!aborted && Date.now() > deadline) { aborted = true; appendScanLog("[labels] pass 4 (Discogs): 5-minute cap reached"); }
        }
        flushNow();
      }

      finishScan();
      appendScanLog("[labels] scan complete: " + labelsIndex.count + " labels found");
      kickFanArtFetches().then(() => kickDiscogsLogoFetches()).catch(e => { if (debug) console.error("[labels] logo fetch:", e.message); });
    } catch (e) {
      // Any unexpected error must still reset state so future scans aren't blocked.
      finishScan();
      appendScanLog("[labels] scan aborted by unexpected error: " + e.message);
      if (debug) console.error("[labels] scan error:", e);
    }
  }

  function finishScan() {
    labelsIndex.building = false;
    labelsIndex.builtAt = Date.now();
    labelsIndex.count = labelsIndex.map.size;
    saveLastScanTime();
    flushNow();
  }

  // -------------------------------------------------------------------------
  // Logo fetches — Fan Art TV (needs MBID + key) then Discogs (needs token).
  // -------------------------------------------------------------------------
  async function fetchFanArtLogo(groupKey, mbid) {
    const fanartKey = getFanartKey();
    if (!mbid || !fanartKey) return "skip";
    if (labelLogoCache.has(groupKey)) return "skip";
    const url = `https://webservice.fanart.tv/v3/music/labels/${encodeURIComponent(mbid)}?api_key=${fanartKey}`;
    try {
      const json = await httpJson(url);
      const logos = json && json.musiclabel;
      const logoUrl = Array.isArray(logos) && logos.length ? logos[0].url : null;
      const merge = labelMerges.get(groupKey);
      const canonKey = merge ? merge.targetKey : groupKey;
      setLabelLogo(canonKey, logoUrl);
      const entry = labelsIndex.map.get(canonKey);
      if (entry) entry.logo_url = logoUrl;
      return logoUrl ? "found" : "none";
    } catch (e) {
      if (e.message && e.message.includes("404")) {
        const merge = labelMerges.get(groupKey);
        setLabelLogo(merge ? merge.targetKey : groupKey, null);
        return "none";
      }
      return "error"; // network error — don't cache, retry next restart
    }
  }

  async function kickFanArtFetches() {
    if (!getFanartKey()) return;
    const pending = [];
    for (const [groupKey, entry] of labelsIndex.map) {
      if (!entry.mbid || labelLogoCache.has(groupKey)) continue;
      pending.push({ groupKey, mbid: entry.mbid });
    }
    if (!pending.length) return;
    appendScanLog("[labels:fanart] fetching logos for " + pending.length + " labels");
    const BATCH = 5;
    for (let i = 0; i < pending.length; i += BATCH) {
      await Promise.allSettled(pending.slice(i, i + BATCH).map(({ groupKey, mbid }) => fetchFanArtLogo(groupKey, mbid)));
    }
    flushNow();
  }

  async function fetchLogoFromDiscogs(labelName) {
    const discogsToken = getDiscogsToken();
    if (!discogsToken) return { logo: null, reason: "no-token" };
    await discogsWait();
    const url = `https://api.discogs.com/database/search?type=label&q=${encodeURIComponent(sanitizeDiscogsSearchTerm(labelName))}&per_page=5`;
    try {
      const json = await httpJson(url, { "Authorization": `Discogs token=${discogsToken}`, "User-Agent": MB_USER_AGENT }, 10000);
      const results = json && json.results;
      if (!Array.isArray(results) || !results.length) return { logo: null, reason: "empty" };
      const normTarget = labelGroupKey(labelName);
      let match = results.find(r => labelGroupKey(r.title || "") === normTarget)
        || results.find(r => labelGroupKey(r.title || "").startsWith(normTarget)) || results[0];
      const img = match.cover_image || match.thumb || null;
      if (!img || img.endsWith(".gif") || /no[-_]image|no[-_]label|spacer|avatar|default[-_]label/i.test(img)) return { logo: null, reason: "filtered" };
      return { logo: img, reason: "ok" };
    } catch (e) { return { logo: null, reason: "error" }; }
  }

  async function kickDiscogsLogoFetches() {
    if (!getDiscogsToken()) return;
    const pending = [];
    for (const [groupKey, entry] of labelsIndex.map) {
      if (discogsLogoTried.has(groupKey) || labelLogoCache.has(groupKey) || !entry.display) continue;
      pending.push({ groupKey, display: entry.display });
    }
    if (!pending.length) return;
    appendScanLog("[labels:discogs:logos] fetching logos for " + pending.length + " labels");
    for (const { groupKey, display } of pending) {
      const { logo, reason } = await fetchLogoFromDiscogs(display);
      if (reason !== "error") discogsLogoTried.add(groupKey);
      if (logo) {
        const merge = labelMerges.get(groupKey);
        const canonKey = merge ? merge.targetKey : groupKey;
        setLabelLogo(canonKey, logo);
        const entry = labelsIndex.map.get(canonKey);
        if (entry) entry.logo_url = logo;
      }
    }
    flushNow();
  }

  // -------------------------------------------------------------------------
  // Public read API (backs the HTTP routes in index.js)
  // -------------------------------------------------------------------------
  function status() {
    return { scanning: labelsIndex.building, progress: labelsIndex.progress, count: labelsIndex.count, builtAt: labelsIndex.builtAt };
  }

  function listLabels() {
    const mergesByTarget = new Map();
    for (const [sk, m] of labelMerges) {
      if (!mergesByTarget.has(m.targetKey)) mergesByTarget.set(m.targetKey, []);
      mergesByTarget.get(m.targetKey).push({ key: sk, display: m.sourceDisplay });
    }
    const out = [];
    for (const [groupKey, entry] of labelsIndex.map) {
      out.push({
        key:        groupKey,
        title:      entry.display,
        subtitle:   entry.albums.length + " album" + (entry.albums.length === 1 ? "" : "s"),
        albumCount: entry.albums.length,
        image_key:  entry.image_key || null,
        logo_url:   entry.logo_url || null,
        mergedFrom: mergesByTarget.get(groupKey) || [],
      });
    }
    out.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: "base" }));
    return out;
  }

  function labelAlbums(name, order) {
    const ord = order === "random" ? "random" : "alpha";
    const gk = labelGroupKey(name);
    const entry = labelsIndex.map.get(gk);
    if (!entry) return { albums: [], total: 0, label: name, order: ord, groupKey: gk, logo_url: labelLogoCache.get(gk) || null, scanning: labelsIndex.building };
    let albums = entry.albums.slice();
    if (ord === "random") {
      for (let i = albums.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [albums[i], albums[j]] = [albums[j], albums[i]]; }
    } else {
      albums.sort((a, b) => (a.title || "").localeCompare(b.title || "", undefined, { sensitivity: "base" }));
    }
    return { albums, total: albums.length, label: name, order: ord, groupKey: gk, logo_url: labelLogoCache.get(gk) || null };
  }

  function searchLabels(nq) {
    if (!nq || !labelsIndex.map.size) return [];
    const out = [];
    for (const [, entry] of labelsIndex.map) {
      if (!entry.display) continue;
      const norm = normalize(entry.display);
      if (!norm.includes(nq)) continue;
      out.push({ display: entry.display, albumCount: entry.albums ? entry.albums.length : 0, logo_url: entry.logo_url || null });
    }
    out.sort((a, b) => {
      const aq = normalize(a.display).startsWith(nq) ? 0 : 1;
      const bq = normalize(b.display).startsWith(nq) ? 0 : 1;
      return aq - bq || b.albumCount - a.albumCount;
    });
    return out.slice(0, 10);
  }

  // Candidate labels for the "label of the week" pick: those with a fuller
  // catalogue (>= minAlbums), keys sorted for a stable, insertion-order-
  // independent pick. Returns { keys, count, get(key) } so the caller applies
  // its own deterministic weekly hash over `keys`.
  function weekCandidates(minAlbums = 6) {
    const keys = [...labelsIndex.map.entries()]
      .filter(([, e]) => e.albums && e.albums.length >= minAlbums)
      .map(([k]) => k)
      .sort();
    return { keys, count: labelsIndex.map.size, get: (k) => labelsIndex.map.get(k) };
  }

  function readScanLog() {
    try { return fs.readFileSync(LOG_FILE, "utf8"); }
    catch (e) { return null; }
  }

  function logoImagePath(filename) {
    const p = path.join(LOGOS_DIR, path.basename(filename));
    return fs.existsSync(p) ? p : null;
  }

  // -------------------------------------------------------------------------
  // Mutations (rescan / merges / logos) backing the settings UI routes.
  // -------------------------------------------------------------------------
  function requestRescan() {
    if (labelsIndex.building) return { ok: false, reason: "scan already running" };
    labelsIndex.builtAt = 0;
    appendScanLog("[labels] manual rescan requested");
    runScan().catch(e => appendScanLog("[labels] rescan error: " + e.message));
    return { ok: true };
  }

  // Force a FULL rescan: wipe the label-name cache so every album is re-queried,
  // but preserve MBIDs, logos and merges (expensive to re-fetch). Also clears
  // the per-session Discogs-logo dedup set so logos are retried.
  function forceRescan() {
    if (labelsIndex.building) return { ok: false, reason: "scan already running" };
    labelDiskCache.clear();
    dirty.names = true;
    labelsIndex.map.clear();
    labelsIndex.count = 0;
    labelsIndex.builtAt = 0;
    discogsLogoTried.clear();
    flushNow();
    appendScanLog("[labels] FORCE rescan requested — cleared name cache, starting full scan");
    runScan().catch(e => appendScanLog("[labels] force-rescan error: " + e.message));
    return { ok: true };
  }

  function onAlbumIndexRebuilt() { rebuildLabelsMap(); }

  // items: [target, ...sources]; sources' albums redirect to the target group.
  function mergeLabels(items) {
    if (!Array.isArray(items) || items.length < 2) return { ok: false, error: "Need at least 2 labels" };
    const [target, ...sources] = items;
    if (!target.key || !target.display) return { ok: false, error: "Invalid target" };
    for (const src of sources) {
      if (!src.key || src.key === target.key) continue;
      labelMerges.set(src.key, { targetKey: target.key, targetDisplay: target.display, sourceDisplay: src.display || src.key });
    }
    dirty.merges = true;
    rebuildLabelsMap();
    flushNow();
    appendScanLog("[labels] merged " + sources.length + " label(s) into '" + target.display + "'");
    return { ok: true };
  }

  function unmerge(sourceKey) {
    labelMerges.delete(sourceKey);
    dirty.merges = true;
    rebuildLabelsMap();
    flushNow();
    appendScanLog("[labels] unmerged key '" + sourceKey + "'");
    return { ok: true };
  }

  async function logoCandidates(name) {
    const discogsToken = getDiscogsToken();
    if (!discogsToken) throw new Error("Discogs token not configured — add it in Settings");
    const headers = { "Authorization": `Discogs token=${discogsToken}`, "User-Agent": MB_USER_AGENT };
    const BAD = /no[-_]image|no[-_]label|spacer|avatar|default[-_]label/i;
    const normTarget = labelGroupKey(name);
    await discogsWait();
    const json = await httpJson(
      `https://api.discogs.com/database/search?type=label&q=${encodeURIComponent(sanitizeDiscogsSearchTerm(name))}&per_page=25`,
      headers, 10000
    );
    const results = (json && json.results) || [];
    const withImages = results
      .map(r => ({ id: r.id, title: r.title || "", img: r.cover_image || r.thumb || null }))
      .filter(c => c.img && !c.img.endsWith(".gif") && !BAD.test(c.img));
    if (withImages.length) return withImages.slice(0, 6);
    const best = results.find(r => labelGroupKey(r.title || "") === normTarget)
      || results.find(r => labelGroupKey(r.title || "").includes(normTarget)) || results[0];
    if (best && best.id) {
      await discogsWait();
      const labelData = await httpJson(`https://api.discogs.com/labels/${best.id}`, headers, 10000);
      const images = Array.isArray(labelData && labelData.images) ? labelData.images : [];
      return images.filter(i => i.uri && !i.uri.endsWith(".gif") && !BAD.test(i.uri)).slice(0, 6).map(i => ({ title: best.title, img: i.uri }));
    }
    return [];
  }

  // Download and locally cache a chosen logo image so any URL works reliably on
  // mobile. Discogs page URLs are resolved to a CDN image first.
  async function setLogo(label, url) {
    if (!label) throw new Error("label required");
    if (!url) throw new Error("url required");
    const groupKey = labelGroupKey(label);
    if (!groupKey) throw new Error("invalid label name");
    let imageUrl = url;
    const discogsIdMatch = url.match(/discogs\.com\/label\/(\d+)/i);
    if (discogsIdMatch && getDiscogsToken()) {
      try {
        await discogsWait();
        const BAD = /no[-_]image|no[-_]label|spacer|avatar|default[-_]label/i;
        const labelData = await httpJson(`https://api.discogs.com/labels/${discogsIdMatch[1]}`,
          { "Authorization": `Discogs token=${getDiscogsToken()}`, "User-Agent": MB_USER_AGENT }, 10000);
        const images = Array.isArray(labelData && labelData.images) ? labelData.images : [];
        const img = images.find(i => i.uri && !i.uri.endsWith(".gif") && !BAD.test(i.uri));
        if (img && img.uri) imageUrl = img.uri;
      } catch (e) { /* fall through to download the URL directly */ }
    }
    let storedUrl = imageUrl;
    try {
      const ctl = new AbortController();
      const tid = setTimeout(() => ctl.abort(), 15000);
      const resp = await fetch(imageUrl, { redirect: "follow", signal: ctl.signal, headers: { "User-Agent": MB_USER_AGENT, "Accept": "image/*,*/*;q=0.8" } });
      clearTimeout(tid);
      const ct = (resp.headers.get("content-type") || "").toLowerCase();
      if (ct.startsWith("image/")) {
        const ext = ct.includes("png") ? "png" : ct.includes("gif") ? "gif" : ct.includes("webp") ? "webp" : "jpg";
        fs.mkdirSync(LOGOS_DIR, { recursive: true });
        fs.writeFileSync(path.join(LOGOS_DIR, groupKey + "." + ext), Buffer.from(await resp.arrayBuffer()));
        storedUrl = `/api/labels/logo-image/${groupKey}.${ext}`;
      }
    } catch (e) { /* timeout / network error — keep the remote URL, tile degrades gracefully */ }
    setLabelLogo(groupKey, storedUrl);
    const entry = labelsIndex.map.get(groupKey);
    if (entry) entry.logo_url = storedUrl;
    discogsLogoTried.delete(groupKey);
    return storedUrl;
  }

  // 12-hour auto-rescan check — the caller owns the interval timer and calls
  // this on each tick (keeps timer lifecycle/`unref` policy in index.js).
  const RESCAN_MS = 12 * 60 * 60 * 1000;
  function maybeAutoRescan() {
    if (labelsIndex.building) return;
    if (labelsIndex.builtAt !== 0 && (Date.now() - labelsIndex.builtAt) < RESCAN_MS) return;
    appendScanLog("[labels] auto-rescan triggered");
    runScan().catch(e => appendScanLog("[labels] auto-rescan error: " + e.message));
  }

  loadCaches();

  return {
    // reads
    status, listLabels, labelAlbums, searchLabels, weekCandidates, labelForAlbum,
    readScanLog, logoImagePath,
    // lifecycle
    seedFromCache, runScan, onAlbumIndexRebuilt, maybeAutoRescan,
    requestRescan, forceRescan,
    // mutations
    mergeLabels, unmerge, logoCandidates, setLogo,
    // pure helpers (exposed for callers/tests)
    groupKey: labelGroupKey, canonicalName: canonicalLabelName, isLikelyNotALabel,
    RESCAN_MS,
    // internals (tests)
    _index: labelsIndex, _caches: { labelDiskCache, labelMbidCache, labelLogoCache, labelMerges, labelsOverride },
    _files: FILES, _flushNow: flushNow,
    _setName: setLabelName, _setMbid: setLabelMbid, _setLogo: setLabelLogo,
  };
}

module.exports = {
  makeLabels,
  // Pure helpers usable without a factory instance (unit tests, callers).
  labelGroupKey, canonicalLabelName, isLikelyNotALabel, sanitizeDiscogsSearchTerm,
};
