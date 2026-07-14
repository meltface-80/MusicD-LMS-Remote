/*
 * Album reviews + artist biographies.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License.
 *
 * Source order (per lookup):
 *   1. The LMS "Music and Artist Information" plugin (musicartistinfo) via
 *      JSON-RPC, when it is installed — probed with a `can` query and cached.
 *      Lookups are done by LMS album_id/artist_id where we have one, so the
 *      plugin can use the MusicBrainz ids it stores for tagged local files;
 *      name-based lookup is the fallback for streaming/unmatched albums.
 *   2. Qobuz (the same unofficial-API client the Qobuz tab uses): the album
 *      `description` (Qobuz's wiki-style editorial review) and the artist
 *      `biography`. This is what makes reviews/bios available even when the
 *      LMS plugin is NOT installed, and it also serves TIDAL albums (which
 *      rarely carry reviews) by matching the same album on Qobuz by name.
 *
 * Pitchfork is deliberately NOT a text source here — Pitchfork reviews are
 * only ever surfaced as a score + a LINK to pitchfork.com (see
 * /api/album/extras), never copied text.
 *
 * Results are cached on disk (data/cache/albuminfo.json) following the
 * lib/labels.js plain-JSON-file convention: hits for 30 days, misses for
 * 1 day, plus an in-flight dedupe so a wall display polling every 2s can't
 * stampede the sources.
 */
"use strict";

const fs = require("fs");
const path = require("path");

const HIT_TTL_MS  = 30 * 24 * 60 * 60 * 1000;
const MISS_TTL_MS = 24 * 60 * 60 * 1000;
const CAN_TTL_MS  = 10 * 60 * 1000;
const MAX_TEXT    = 6000;

// ---- tiny HTML → text (Qobuz descriptions/bios are HTML-ish) ---------------
const NAMED_ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
  hellip: "...", mdash: "—", ndash: "–", copy: "©", reg: "®",
  lsquo: "‘", rsquo: "’", ldquo: "“", rdquo: "”", eacute: "é"
};
function decodeEntities(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);?/gi, (_, h) => { try { return String.fromCodePoint(parseInt(h, 16)); } catch (e) { return ""; } })
    .replace(/&#(\d+);?/g,        (_, d) => { try { return String.fromCodePoint(parseInt(d, 10)); } catch (e) { return ""; } })
    .replace(/&([a-z][a-z0-9]*);?/gi, (m, name) => {
      const v = NAMED_ENTITIES[name.toLowerCase()];
      return v !== undefined ? v : m;
    });
}
function stripHtml(html) {
  const s = String(html || "")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<\/?(?:p|div|h\d|li)[^>]*>/gi, "\n")
    .replace(/<[^>]+>/g, "");
  return decodeEntities(s)
    .replace(/[ \t]+/g, " ")
    .replace(/\n[ \t]+/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
function clampText(s) {
  if (!s) return s;
  if (s.length <= MAX_TEXT) return s;
  const cut = s.slice(0, MAX_TEXT);
  const stop = cut.lastIndexOf(". ");
  return (stop > MAX_TEXT * 0.5 ? cut.slice(0, stop + 1) : cut) + " …";
}

// Extract the Qobuz album id from an LMS extid like "qobuz:album:abc123".
function qobuzIdFromExtid(extid) {
  const m = /^qobuz:(?:album:)?(.+)$/i.exec(String(extid || "").trim());
  return m ? m[1] : null;
}

/*
 * deps:
 *   getLms()    → the live LMS adapter (or null when disconnected)
 *   qobuzCall(fn) → run an authenticated Qobuz call (throws when not connected)
 *   qobuz       → the lib/qobuz client module
 *   dataDir     → the persistent data directory (/app/data)
 *   normalize   → search.normalize
 *   artistKey   → search.artistKey (stylization-folded artist identity)
 */
function makeAlbumInfo({ getLms, qobuzCall, qobuz, dataDir, normalize, artistKey, debug }) {
  const CACHE_FILE = path.join(dataDir, "cache", "albuminfo.json");
  let cache = null;         // { reviews: {key:{at,v}}, bios: {key:{at,v}} }
  let saveTimer = null;
  const inflight = new Map();

  function loadCache() {
    if (cache) return cache;
    try { cache = JSON.parse(fs.readFileSync(CACHE_FILE, "utf8")) || {}; }
    catch (e) { cache = {}; }
    if (!cache.reviews) cache.reviews = {};
    if (!cache.bios)    cache.bios = {};
    if (!cache.artists) cache.artists = {};   // photos + band membership
    return cache;
  }
  function scheduleSave() {
    if (saveTimer) return;
    saveTimer = setTimeout(() => {
      saveTimer = null;
      try {
        fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
        fs.writeFileSync(CACHE_FILE, JSON.stringify(cache));
      } catch (e) { if (debug) console.error("[albuminfo] cache save failed:", e.message); }
    }, 3000);
    if (saveTimer.unref) saveTimer.unref();
  }
  function cacheGet(kind, key) {
    const c = loadCache();
    const hit = c[kind][key];
    if (!hit) return undefined;
    const ttl = hit.v ? HIT_TTL_MS : MISS_TTL_MS;
    if (Date.now() - hit.at > ttl) return undefined;
    return hit.v;   // may be null (a cached miss)
  }
  function cachePut(kind, key, v) {
    const c = loadCache();
    c[kind][key] = { at: Date.now(), v: v || null };
    scheduleSave();
  }

  // ---- LMS musicartistinfo plugin --------------------------------------------
  let canAt = 0, canVal = false;
  async function pluginAvailable() {
    const lms = getLms();
    if (!lms) return false;
    if (Date.now() - canAt < CAN_TTL_MS) return canVal;
    try {
      const r = await lms.request("", ["can", "musicartistinfo", "biography", "?"]);
      canVal = !!(r && (r._can === 1 || r._can === "1"));
    } catch (e) { canVal = false; }
    canAt = Date.now();
    return canVal;
  }

  // The plugin returns the text under a command-named field ({ albumreview },
  // { biography }); read defensively across versions.
  function maiText(r, field) {
    if (!r) return null;
    const raw = r[field] || r.text || null;
    if (!raw) return null;
    const text = clampText(stripHtml(raw));
    return text && text.length >= 40 ? text : null;   // a one-liner isn't a review/bio
  }

  async function maiAlbumReview({ albumId, title, artist }) {
    const lms = getLms();
    if (!lms || !(await pluginAvailable())) return null;
    // album_id first — the plugin then uses the ids/MBIDs LMS stores for the
    // local file, the most precise identification. Names are the fallback.
    const attempts = [];
    if (albumId) attempts.push(["musicartistinfo", "albumreview", "album_id:" + albumId]);
    if (title)   attempts.push(["musicartistinfo", "albumreview", "album:" + title, ...(artist ? ["artist:" + artist] : [])]);
    for (const cmd of attempts) {
      try {
        const text = maiText(await lms.request("", cmd), "albumreview");
        if (text) return { text, source: "LMS", attribution: "Music & Artist Information (LMS)" };
      } catch (e) { if (debug) console.error("[albuminfo] mai albumreview:", e.message); }
    }
    return null;
  }

  async function maiBiography(name) {
    const lms = getLms();
    if (!lms || !(await pluginAvailable())) return null;
    try {
      const r = await lms.request("", ["musicartistinfo", "biography", "artist:" + name]);
      const text = maiText(r, "biography");
      if (text) return { name, text, attribution: "Music & Artist Information (LMS)" };
    } catch (e) { if (debug) console.error("[albuminfo] mai biography:", e.message); }
    return null;
  }

  // ---- Qobuz fallbacks --------------------------------------------------------
  // Match an album on Qobuz by name: title must match normalized (exactly, or
  // as a prefix to tolerate "(Deluxe Edition)" suffixes) AND the artist must
  // match on the stylization-folded identity key.
  async function qobuzFindAlbumId(title, artist) {
    const nt = normalize(title);
    const ak = artistKey(artist.split(/ \/ |; |, | & | \+ | feat\.? | featuring | ft\.? /i)[0] || artist);
    if (!nt) return null;
    const r = await qobuzCall(t => qobuz.searchCatalog(t, (artist ? artist + " " : "") + title, 20));
    let best = null, bestScore = 0;
    for (const a of (r.albums.items || [])) {
      if (!a || a.id == null) continue;
      const at = normalize(a.title || "");
      const aa = artistKey((a.artist && a.artist.name) || (a.performer && a.performer.name) || "");
      const artistOk = !ak || !aa ? false : (aa === ak || aa.includes(ak) || ak.includes(aa));
      if (!artistOk) continue;
      let score = 0;
      if (at === nt) score = 3;
      else if (at.startsWith(nt) || nt.startsWith(at)) score = 2;
      if (score > bestScore) { bestScore = score; best = a; }
    }
    return best ? String(best.id) : null;
  }

  async function qobuzAlbumReview({ title, artist, extid }) {
    try {
      // LMS-side identification first: a Qobuz library album's extid IS the
      // Qobuz album id. Only fall back to a catalog-search match without one.
      let qid = qobuzIdFromExtid(extid);
      if (!qid) qid = await qobuzFindAlbumId(title, artist);
      if (!qid) return null;
      const al = await qobuzCall(t => qobuz.getAlbum(t, qid));
      const text = al && al.description ? clampText(stripHtml(al.description)) : null;
      if (text && text.length >= 40) return { text, source: "Qobuz", attribution: "Qobuz" };
    } catch (e) { if (debug) console.error("[albuminfo] qobuz review:", e.message); }
    return null;
  }

  // ---- artist photos + band members --------------------------------------------

  // Deadlined JSON GET (MusicBrainz). Kept local — this module otherwise only
  // talks through qobuzCall/lmsRequest.
  async function httpJson(url, headers, timeoutMs = 15000) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const res = await fetch(url, { headers, signal: ctl.signal });
      if (!res.ok) throw new Error("HTTP " + res.status);
      return await res.json();
    } finally { clearTimeout(timer); }
  }
  // MusicBrainz etiquette: identify ourselves, 1 request/second.
  const MB_UA = process.env.MB_USER_AGENT || "MusicD-LMS-Remote/1.0 (https://github.com/meltface-80/MusicD-LMS-Remote)";
  let mbLast = 0;
  async function mbWait() {
    const gap = Date.now() - mbLast;
    if (gap < 1100) await new Promise(r => setTimeout(r, 1100 - gap));
    mbLast = Date.now();
  }
  const mbQuote = (s) => String(s).replace(/([+\-!(){}\[\]^"~*?:\\\/])/g, "\\$1");

  // MAI artist photo(s). artistphoto answers flat { url, credits }; artistphotos
  // an item_loop of { url, credits, size }. URLs are either absolute remote
  // (last.fm/Discogs CDNs) or LMS-relative ("imageproxy/mai/artist/…", no
  // leading slash) — relative ones are normalized to "/imageproxy/…" which the
  // app reverse-proxies, so they load same-origin in the client. Anything that
  // is neither http(s) nor a known LMS web path (e.g. a local FILE path from
  // the plural verb's media-folder entries) is dropped.
  function normalizePhotoUrl(u) {
    if (!u || typeof u !== "string") return null;
    if (/^https?:\/\//i.test(u)) return u;
    const rel = u.replace(/^\//, "");
    if (/^(imageproxy|plugins|music|html)\//i.test(rel)) return "/" + rel;
    return null;
  }
  function maiPhotoUrls(r) {
    const out = [];
    const push = (u) => { const n = normalizePhotoUrl(u); if (n && !out.includes(n)) out.push(n); };
    if (r) {
      push(r.url); push(r.artist_image); push(r.photo);
      for (const it of (r.item_loop || [])) { if (it) { push(it.url); push(it.image); } }
    }
    return out;
  }
  async function maiArtistPhotos(name, wantMany) {
    const lms = getLms();
    if (!lms || !(await pluginAvailable())) return [];
    const cmds = wantMany
      ? [["musicartistinfo", "artistphotos", "artist:" + name], ["musicartistinfo", "artistphoto", "artist:" + name]]
      : [["musicartistinfo", "artistphoto", "artist:" + name]];
    for (const cmd of cmds) {
      try {
        const urls = maiPhotoUrls(await lms.request("", cmd));
        if (urls.length) return urls;
      } catch (e) { if (debug) console.error("[albuminfo] mai " + cmd[1] + ":", e.message); }
    }
    return [];
  }
  async function qobuzArtistImage(name) {
    try {
      const ak = artistKey(name);
      if (!ak) return null;
      const r = await qobuzCall(t => qobuz.searchCatalog(t, name, 10));
      const hit = (r.artists.items || []).find(a => {
        const k = artistKey((a && a.name) || "");
        return k && (k === ak || k.includes(ak) || ak.includes(k));
      });
      return hit ? qobuz.pickImage(hit) : null;
    } catch (e) { if (debug) console.error("[albuminfo] qobuz image:", e.message); }
    return null;
  }

  // Band membership from MusicBrainz artist relations ("member of band"):
  // for a BAND → its members; for a PERSON → the bands they belong(ed) to.
  async function mbArtistMembers(name) {
    const empty = { members: [], memberOf: [] };
    try {
      await mbWait();
      const q = await httpJson(
        "https://musicbrainz.org/ws/2/artist/?query=artist:%22" + encodeURIComponent(mbQuote(name)) + "%22&fmt=json&limit=3",
        { "User-Agent": MB_UA }, 20000);
      const hit = (q.artists || []).find(a => a && Number(a.score) >= 90);
      if (!hit || !hit.id) return empty;
      await mbWait();
      const full = await httpJson(
        "https://musicbrainz.org/ws/2/artist/" + encodeURIComponent(hit.id) + "?inc=artist-rels&fmt=json",
        { "User-Agent": MB_UA }, 20000);
      const members = [], memberOf = [];
      const seen = new Set();
      for (const rel of (full.relations || [])) {
        if (!rel || rel.type !== "member of band" || !rel.artist || !rel.artist.name) continue;
        const nm = rel.artist.name;
        if (seen.has(nm)) continue;
        seen.add(nm);
        // Direction backward = the related artist is a MEMBER of this band;
        // forward = this person is a member OF the related band.
        (rel.direction === "backward" ? members : memberOf).push(nm);
      }
      return { members, memberOf };
    } catch (e) { if (debug) console.error("[albuminfo] mb members:", e.message); }
    return empty;
  }

  async function qobuzArtistBio(name) {
    try {
      const ak = artistKey(name);
      if (!ak) return null;
      const r = await qobuzCall(t => qobuz.searchCatalog(t, name, 10));
      const hit = (r.artists.items || []).find(a => {
        const k = artistKey(a && a.name || "");
        return k && (k === ak || k.includes(ak) || ak.includes(k));
      });
      if (!hit || hit.id == null) return null;
      const ar = await qobuzCall(t => qobuz.getArtist(t, String(hit.id), 1, 0));
      const text = ar && ar.biography ? clampText(stripHtml(ar.biography)) : null;
      if (text && text.length >= 40) return { name, text, attribution: "Qobuz" };
    } catch (e) { if (debug) console.error("[albuminfo] qobuz bio:", e.message); }
    return null;
  }

  // ---- public lookups (cached + deduped) --------------------------------------
  function dedupe(key, fn) {
    if (inflight.has(key)) return inflight.get(key);
    const p = (async () => {
      try { return await fn(); }
      finally { inflight.delete(key); }
    })();
    inflight.set(key, p);
    return p;
  }

  // → { text, source: "LMS"|"Qobuz", attribution } | null
  async function albumReview({ albumId, title, artist, extid, source }) {
    if (!title) return null;
    const key = normalize(artist || "") + "||" + normalize(title);
    const hit = cacheGet("reviews", key);
    if (hit !== undefined) return hit;
    return dedupe("r:" + key, async () => {
      const again = cacheGet("reviews", key);
      if (again !== undefined) return again;
      const v = (await maiAlbumReview({ albumId, title, artist })) ||
                (await qobuzAlbumReview({ title, artist, extid, source }));
      cachePut("reviews", key, v);
      return v;
    });
  }

  // → { name, photo, photos: [urls], members: [names], memberOf: [names] }
  // Photo sources: MAI plugin (artistphotos → artistphoto) → Qobuz artist
  // image. Membership: MusicBrainz "member of band" relations — for a band,
  // its members; for a person, the bands they belong(ed) to. Cached like the
  // other lookups (bio text stays in its own cache via artistBio).
  async function artistInfo(name) {
    name = String(name || "").trim();
    if (!name) return null;
    const key = artistKey(name) || normalize(name);
    const hit = cacheGet("artists", key);
    if (hit !== undefined) return hit;
    return dedupe("a:" + key, async () => {
      const again = cacheGet("artists", key);
      if (again !== undefined) return again;
      const [maiPhotos, mb] = await Promise.all([
        maiArtistPhotos(name, true),
        mbArtistMembers(name)
      ]);
      let photos = maiPhotos.slice(0, 4);
      if (!photos.length) {
        const q = await qobuzArtistImage(name);
        if (q) photos = [q];
      }
      const v = {
        name,
        photo:    photos[0] || null,
        photos,
        members:  mb.members.slice(0, 12),
        memberOf: mb.memberOf.slice(0, 6)
      };
      // Only worth remembering as a HIT when something was found.
      cachePut("artists", key, (v.photo || v.members.length || v.memberOf.length) ? v : null);
      return (v.photo || v.members.length || v.memberOf.length) ? v : null;
    });
  }

  // → { name, text, attribution } | null
  async function artistBio(name) {
    name = String(name || "").trim();
    if (!name) return null;
    const key = artistKey(name) || normalize(name);
    const hit = cacheGet("bios", key);
    if (hit !== undefined) return hit;
    return dedupe("b:" + key, async () => {
      const again = cacheGet("bios", key);
      if (again !== undefined) return again;
      const v = (await maiBiography(name)) || (await qobuzArtistBio(name));
      cachePut("bios", key, v);
      return v;
    });
  }

  return { albumReview, artistBio, artistInfo, pluginAvailable,
           _internal: { stripHtml, clampText, qobuzIdFromExtid, normalizePhotoUrl } };
}

module.exports = { makeAlbumInfo };
