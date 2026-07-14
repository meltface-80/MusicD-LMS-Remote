"use strict";

/* Album artwork rescue + store.
 *
 * Local albums whose files carry no embedded/folder art get their covers from
 * external sources, downloaded ONCE and stored in the app's own database
 * (data/artwork/ + data/cache/albumart.json) — the music mount is read-only,
 * so nothing is ever written next to the files.
 *
 * Source order (first hit wins):
 *   1. LMS Music & Artist Information plugin — `musicartistinfo albumcovers`
 *      aggregates Cover Art Archive / Discogs / last.fm on the server.
 *   2. Cover Art Archive by the album's MusicBrainz id (LMS tag M, from the
 *      files' own tags — the strongest identity we have, no guessing).
 *   3. MusicBrainz release-group search by artist + title, then Cover Art
 *      Archive by release-group. Artist matching is stylization-folded via
 *      artistKey() — P!nk == Pink, NO disambiguation (owner decision).
 *   4. Qobuz catalogue search (album image).
 *   5. iTunes Search API (artworkUrl100 upscaled to 600x600).
 *
 * Identity: an album is keyed by normalize(artist)+"||"+normalize(title) (the
 * ORIGINAL LMS strings, so the store survives rescans). Stored images get a
 * content-addressed image_key "art-<sha1(albumKey|sourceUrl)>" — choosing a
 * different cover later mints a NEW key, so the immutable HTTP caches on
 * /api/image can never serve a stale image.
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const MB_UA = process.env.MB_USER_AGENT ||
  "MusicD-LMS-Remote/1.0 (https://github.com/meltface-80/MusicD-LMS-Remote)";
const MISS_TTL_MS = 7 * 24 * 60 * 60 * 1000;   // re-try failed lookups weekly
const MAX_IMAGE_BYTES = 15 * 1024 * 1024;
const CAN_TTL_MS = 10 * 60 * 1000;

function makeAlbumArt(opts) {
  const getLms    = opts.getLms;
  const qobuzCall = opts.qobuzCall;
  const qobuz     = opts.qobuz;
  const dataDir   = opts.dataDir;
  const normalize = opts.normalize;
  const artistKey = opts.artistKey;
  const debug     = !!opts.debug;

  const ART_DIR  = path.join(dataDir, "artwork");
  const IDX_FILE = path.join(dataDir, "cache", "albumart.json");

  // idx.entries: artKey → { file, type, source, at }
  // idx.byAlbum: albumKey → artKey     (the album's current stored cover)
  // idx.misses:  albumKey → at         (full lookup failed; retry after TTL)
  let idx = null;
  let dirty = false, flushTimer = null;

  const albumKey = (title, artist) => normalize(artist || "") + "||" + normalize(title || "");

  function load() {
    if (idx) return idx;
    idx = { entries: {}, byAlbum: {}, misses: {} };
    try {
      const j = JSON.parse(fs.readFileSync(IDX_FILE, "utf8"));
      if (j && j.entries) idx = { entries: j.entries || {}, byAlbum: j.byAlbum || {}, misses: j.misses || {} };
    } catch (e) { /* first run */ }
    return idx;
  }
  function flushNow() {
    if (!dirty) return;
    dirty = false;
    try {
      fs.mkdirSync(path.dirname(IDX_FILE), { recursive: true });
      fs.writeFileSync(IDX_FILE, JSON.stringify(load()));
    } catch (e) { if (debug) console.error("[albumart] save:", e.message); }
  }
  function scheduleFlush() {
    dirty = true;
    if (flushTimer) return;
    flushTimer = setTimeout(() => { flushTimer = null; flushNow(); }, 2000);
    if (flushTimer.unref) flushTimer.unref();
  }

  // ---- plumbing -------------------------------------------------------------

  async function httpGet(url, headers, timeoutMs = 15000) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      return await fetch(url, { headers, redirect: "follow", signal: ctl.signal });
    } finally { clearTimeout(timer); }
  }
  async function httpJson(url, headers, timeoutMs = 15000) {
    const res = await httpGet(url, headers, timeoutMs);
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  // MusicBrainz etiquette: 1 req/second, identified.
  let mbLast = 0;
  async function mbWait() {
    const gap = Date.now() - mbLast;
    if (gap < 1100) await new Promise(r => setTimeout(r, 1100 - gap));
    mbLast = Date.now();
  }
  const mbQuote = (s) => String(s).replace(/([+\-!(){}\[\]^"~*?:\\\/])/g, "\\$1");

  // MAI plugin availability (same probe albuminfo.js uses, cached).
  let canAt = 0, canVal = false;
  async function maiAvailable() {
    const lms = getLms();
    if (!lms) return false;
    if (Date.now() - canAt < CAN_TTL_MS) return canVal;
    try {
      const r = await lms.request("", ["can", "musicartistinfo", "albumcovers", "?"]);
      canVal = r && (r._can === 1 || r._can === "1");
    } catch (e) { canVal = false; }
    canAt = Date.now();
    return canVal;
  }

  // LMS-relative plugin URLs ("imageproxy/…", no leading slash) become
  // absolute LMS URLs — both the thumb proxy and the downloader run
  // server-side, so they need a real host, not the app-relative rewrite the
  // artist-photo path uses for clients.
  function absoluteLmsUrl(u) {
    if (!u || typeof u !== "string") return null;
    if (/^https?:\/\//i.test(u)) return u;
    const lms = getLms();
    if (!lms || !lms.cfg) return null;
    const rel = u.replace(/^\//, "");
    if (!/^(imageproxy|plugins|music|html)\//i.test(rel)) return null;
    return "http://" + lms.cfg.host + ":" + lms.cfg.port + "/" + rel;
  }

  // ---- candidate sources ------------------------------------------------------

  async function maiCandidates(title, artist) {
    const lms = getLms();
    if (!lms || !(await maiAvailable())) return [];
    try {
      const r = await lms.request("", ["musicartistinfo", "albumcovers", "artist:" + artist, "album:" + title]);
      const out = [];
      for (const it of (r && r.item_loop) || []) {
        const u = absoluteLmsUrl(it && it.url);
        if (u && !out.some(c => c.url === u)) {
          out.push({ url: u, source: (it.credits ? String(it.credits) : "Music & Artist Information") });
        }
      }
      return out;
    } catch (e) { if (debug) console.error("[albumart] mai albumcovers:", e.message); }
    return [];
  }

  // Cover Art Archive convenience "front" endpoints redirect to the image (or
  // 404 when the release has no art) — the URL itself is the candidate; the
  // downloader/thumb proxy discovers emptiness by the 404.
  function caaReleaseUrl(mbid)      { return "https://coverartarchive.org/release/" + encodeURIComponent(mbid) + "/front-1200"; }
  function caaReleaseGroupUrl(rgid) { return "https://coverartarchive.org/release-group/" + encodeURIComponent(rgid) + "/front-1200"; }

  // MusicBrainz release-group search by artist + title. Stylization-folded
  // artist comparison (artistKey) — the top scored hit whose artist credit
  // folds to ours wins; no disambiguation prompts.
  async function mbReleaseGroup(title, artist) {
    try {
      await mbWait();
      const q = 'releasegroup:"' + mbQuote(title) + '" AND artist:"' + mbQuote(artist) + '"';
      const j = await httpJson(
        "https://musicbrainz.org/ws/2/release-group/?query=" + encodeURIComponent(q) + "&fmt=json&limit=5",
        { "User-Agent": MB_UA }, 20000);
      const ak = artistKey(artist), nt = normalize(title);
      for (const rg of (j["release-groups"] || [])) {
        if (!rg || Number(rg.score) < 85 || !rg.id) continue;
        if (normalize(rg.title || "") !== nt) continue;
        const credit = (rg["artist-credit"] || []).map(c => (c && (c.name || (c.artist && c.artist.name))) || "").join(" ");
        const ck = artistKey(credit);
        if (ck && ak && (ck === ak || ck.includes(ak) || ak.includes(ck))) return rg.id;
      }
    } catch (e) { if (debug) console.error("[albumart] mb release-group:", e.message); }
    return null;
  }

  async function qobuzCandidate(title, artist) {
    if (!qobuzCall || !qobuz) return null;
    try {
      const r = await qobuzCall(t => qobuz.searchCatalog(t, artist + " " + title, 10));
      const nt = normalize(title), ak = artistKey(artist);
      const hit = ((r && r.albums && r.albums.items) || []).find(a => {
        if (!a || normalize(a.title || "") !== nt) return false;
        const k = artistKey((a.artist && a.artist.name) || "");
        return k && ak && (k === ak || k.includes(ak) || ak.includes(k));
      });
      const img = hit ? qobuz.pickImage(hit) : null;
      return img ? { url: img, source: "Qobuz" } : null;
    } catch (e) { if (debug) console.error("[albumart] qobuz:", e.message); }
    return null;
  }

  async function itunesCandidate(title, artist) {
    try {
      const j = await httpJson(
        "https://itunes.apple.com/search?term=" + encodeURIComponent(artist + " " + title) +
        "&entity=album&limit=5", {}, 12000);
      const nt = normalize(title), ak = artistKey(artist);
      const hit = (j.results || []).find(r => {
        if (!r || !r.artworkUrl100) return false;
        if (normalize(r.collectionName || "") !== nt) return false;
        const k = artistKey(r.artistName || "");
        return k && ak && (k === ak || k.includes(ak) || ak.includes(k));
      });
      return hit ? { url: hit.artworkUrl100.replace("100x100", "600x600"), source: "iTunes" } : null;
    } catch (e) { if (debug) console.error("[albumart] itunes:", e.message); }
    return null;
  }

  // Ordered candidate producers — each yields zero or more { url, source }.
  // Split into steps so resolve() can stop at the first that actually
  // downloads (never paying for the slow MusicBrainz search once MAI or a
  // tagged MBID already produced art), while candidates() runs them all for
  // the editor's grid.
  function producers(title, artist, mbid) {
    return [
      async () => maiCandidates(title, artist),
      async () => (mbid ? [{ url: caaReleaseUrl(mbid), source: "Cover Art Archive" }] : []),
      async () => {
        if (!artist) return [];
        const rgid = await mbReleaseGroup(title, artist);
        return rgid ? [{ url: caaReleaseGroupUrl(rgid), source: "Cover Art Archive" }] : [];
      },
      async () => { const c = artist ? await qobuzCandidate(title, artist) : null; return c ? [c] : []; },
      async () => { const c = artist ? await itunesCandidate(title, artist) : null; return c ? [c] : []; }
    ];
  }

  // All candidate URLs for an album, best sources first, deduped — for the
  // editor's "Find artwork" grid. Runs every source. `mbid` is the album
  // MusicBrainz id from the files' tags when LMS carries one.
  async function candidates({ title, artist, mbid }) {
    title = String(title || "").trim();
    artist = String(artist || "").trim();
    if (!title) return [];
    const out = [];
    const push = (c) => { if (c && c.url && !out.some(x => x.url === c.url)) out.push(c); };
    for (const step of producers(title, artist, mbid)) {
      try { for (const c of await step()) push(c); } catch (e) { /* one source down */ }
    }
    return out.slice(0, 12);
  }

  // ---- store ------------------------------------------------------------------

  function artKeyFor(aKey, srcUrl) {
    return "art-" + crypto.createHash("sha1").update(aKey + "|" + srcUrl).digest("hex").slice(0, 20);
  }
  const EXT_BY_TYPE = { "image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif" };

  async function download(url) {
    const res = await httpGet(url, { "User-Agent": MB_UA }, 20000);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const type = String(res.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
    if (!type.startsWith("image/")) throw new Error("not an image: " + (type || "unknown"));
    const buf = Buffer.from(await res.arrayBuffer());
    if (!buf.length) throw new Error("empty image");
    if (buf.length > MAX_IMAGE_BYTES) throw new Error("image too large");
    return { body: buf, type };
  }

  // Download `url` and store it as the album's cover. Returns the image_key.
  async function saveFromUrl(title, artist, url, source) {
    const aKey = albumKey(title, artist);
    const { body, type } = await download(url);
    const key = artKeyFor(aKey, url);
    const file = key + (EXT_BY_TYPE[type] || ".img");
    fs.mkdirSync(ART_DIR, { recursive: true });
    fs.writeFileSync(path.join(ART_DIR, file), body);
    const s = load();
    // Drop a previously stored cover for this album (superseded file).
    const prev = s.byAlbum[aKey];
    if (prev && prev !== key && s.entries[prev]) {
      try { fs.unlinkSync(path.join(ART_DIR, s.entries[prev].file)); } catch (e) { /* already gone */ }
      delete s.entries[prev];
    }
    s.entries[key] = { file, type, source: source || null, at: Date.now() };
    s.byAlbum[aKey] = key;
    delete s.misses[aKey];
    scheduleFlush();
    if (debug) console.log("[albumart] stored", title, "—", artist, "from", source || url);
    return key;
  }

  // The stored image_key for an album, or null.
  function storedFor(title, artist) {
    return load().byAlbum[albumKey(title, artist)] || null;
  }

  // Bytes for an "art-…" image_key (served by /api/image). null when unknown.
  function read(key) {
    const e = load().entries[key];
    if (!e) return null;
    try {
      return { body: fs.readFileSync(path.join(ART_DIR, e.file)), type: e.type || "image/jpeg" };
    } catch (err) { return null; }
  }

  // Full lookup for one album: try sources in order, stopping at the first
  // candidate that actually downloads (so a MAI/MBID hit never pays for the
  // slower MusicBrainz search or online-service lookups).
  async function resolve({ title, artist, mbid }) {
    title = String(title || "").trim();
    artist = String(artist || "").trim();
    if (!title) return null;
    const aKey = albumKey(title, artist);
    const s = load();
    if (s.byAlbum[aKey]) return s.byAlbum[aKey];
    const missAt = s.misses[aKey];
    if (missAt && (Date.now() - missAt) < MISS_TTL_MS) return null;
    for (const step of producers(title, artist, mbid)) {
      let cands = [];
      try { cands = await step(); } catch (e) { continue; }
      for (const c of cands) {
        try { return await saveFromUrl(title, artist, c.url, c.source); }
        catch (e) { if (debug) console.error("[albumart] fetch", c.source, "failed:", e.message); }
      }
    }
    s.misses[aKey] = Date.now();
    scheduleFlush();
    return null;
  }

  // Background sweep over the index records: fill in covers for every album
  // that still has none. Sequential (MusicBrainz is rate-limited anyway);
  // records are mutated in place via onFound so the UI picks new art up on
  // its next fetch. Only one sweep runs at a time.
  let sweeping = false;
  async function sweep(records, onFound) {
    if (sweeping) return { scanned: 0, found: 0, running: true };
    sweeping = true;
    let scanned = 0, found = 0;
    try {
      for (const rec of records) {
        if (rec.image_key) continue;
        // Identity for the store is the ORIGINAL LMS strings when the album
        // has been renamed by an edit.
        const title = rec.origTitle || rec.title;
        const artist = rec.origArtist || rec.subtitle;
        scanned++;
        try {
          const key = await resolve({ title, artist, mbid: rec.mbid });
          if (key) { found++; if (onFound) onFound(rec, key); }
        } catch (e) { if (debug) console.error("[albumart] sweep:", e.message); }
      }
      flushNow();
      if (debug && scanned) console.log("[albumart] sweep done:", found + "/" + scanned, "covers found");
    } finally { sweeping = false; }
    return { scanned, found, running: false };
  }

  // Server-side preview proxy for the editor's candidate grid (remote covers
  // are often CORS-less / hotlink-blocked in the browser).
  async function thumb(url) {
    if (!/^https?:\/\//i.test(String(url || ""))) throw new Error("http(s) URL required");
    return download(url);
  }

  return { candidates, saveFromUrl, storedFor, read, resolve, sweep, thumb, flushNow, _albumKey: albumKey };
}

module.exports = { makeAlbumArt };
