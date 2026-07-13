/*
 * Pitchfork album-review lookup — guesses a review URL from artist+album
 * names and scrapes the score / Best New Music flag / review body straight
 * off the page. No RSS feed, no listing/browse support: this is only the
 * single-album lookup used to populate the album detail view.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License.
 *
 * COMPLIANCE (UK law): the scraped review body must never reach a client —
 * it is returned here (and used internally for the artist-match guard
 * below), but the /api/album/extras route in index.js nulls it out before
 * responding, keeping only the score, the Best New Music flag, and a link
 * back to pitchfork.com.
 */
const DEBUG = process.env.DEBUG === "1";

const BROWSER_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const pitchforkCache = new Map();
let pitchforkLastReq = 0;

// Simple 1-req/sec rate limiter — be a polite scraper.
async function pitchforkWait() {
  const elapsed = Date.now() - pitchforkLastReq;
  if (elapsed < 1000) await new Promise(r => setTimeout(r, 1000 - elapsed));
  pitchforkLastReq = Date.now();
}

function slugifyForPitchfork(s) {
  return String(s || "")
    .toLowerCase()
    .replace(/['']/g, "")           // drop apostrophes before stripping
    .replace(/[^a-z0-9\s-]/g, " ")  // non-alphanumeric → space
    .replace(/\s+/g, "-")           // spaces → hyphens
    .replace(/-+/g, "-")            // collapse multiple hyphens
    .replace(/^-+|-+$/g, "");       // trim hyphens
}

// Plain fetch-based GET-as-text helper with a timeout.
async function httpText(url, headers, timeoutMs = 12000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctl.signal, redirect: "follow" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

function normalize(s) {
  return String(s || "").toLowerCase()
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// First significant token, skipping a leading article ("the who" -> "who").
function firstSignificantToken(s) {
  const toks = normalize(s).split(" ").filter(Boolean);
  if (toks.length > 1 && /^(the|a|an)$/.test(toks[0])) return toks[1];
  return toks[0] || "";
}

// Decode HTML entities — named (incl. &copy; &reg; &trade;) and numeric
// (&#169; / &#xA9;), with or without the trailing semicolon. Unknown
// entities are left untouched.
const NAMED_ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'",
  copy: "©", reg: "®", trade: "™",
  nbsp: " ", hellip: "...", mdash: "—", ndash: "–",
  lsquo: "‘", rsquo: "’", ldquo: "“", rdquo: "”",
  deg: "°"
};
function safeCodePoint(n) {
  try { return String.fromCodePoint(n); } catch { return ""; }
}
function decodeEntities(input) {
  if (!input) return "";
  return String(input)
    .replace(/&#x([0-9a-f]+);?/gi, (_, h) => safeCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);?/g,        (_, d) => safeCodePoint(parseInt(d, 10)))
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
    .replace(/<\/?p[^>]*>/gi, "\n\n")
    .replace(/<[^>]+>/g, "");
  return decodeEntities(s)            // named + numeric, semicolon optional
    .replace(/\n[ \t]+/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .replace(/[ \t]+/g, " ")
    .trim();
}

// Extractor for a Pitchfork review PAGE: the review body from the JSON-LD
// Review block, plus the score / Best-New-Music flag from the inline
// preloaded state. The body is stripped of HTML but NOT entity-decoded here
// (fetchPitchfork's caller decodes it if it ever needs to).
function parsePitchforkReviewHtml(html) {
  let description = null;
  const ldRe = /<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = ldRe.exec(html)) !== null) {
    try {
      const obj = JSON.parse(m[1]);
      if (obj["@type"] === "Review" && obj.reviewBody) {
        description = stripHtml(obj.reviewBody).trim() || null;
        break;
      }
    } catch (e) { /* malformed JSON-LD block — try the next one; loop continues */ }
  }
  let score = null, isBestNewMusic = false;
  const scoreM = html.match(/"musicRating"\s*:\s*\{[^}]*?"score"\s*:\s*(\d+(?:\.\d+)?)/);
  if (scoreM) score = parseFloat(scoreM[1]);
  const bnmM = html.match(/"isBestNewMusic"\s*:\s*(true|false)/);
  if (bnmM) isBestNewMusic = bnmM[1] === "true";
  return { description, score: Number.isFinite(score) ? score : null, isBestNewMusic };
}

// Guess a Pitchfork review URL from artist+album slugs and scrape it.
// Returns { description, score, isBestNewMusic, url, source: "Pitchfork" }
// or null (no guess possible, page missing/404, or artist mismatch).
async function fetchPitchfork(title, artist) {
  const key = normalize(title) + "||" + normalize(artist || "");
  if (pitchforkCache.has(key)) return pitchforkCache.get(key);

  // Use primary artist only (before collaborators)
  const primaryArtist = String(artist || "").split(/\s*[/,&+]\s*|\s+feat\.?\s+|\s+featuring\s+|\s+ft\.?\s+/i)[0].trim();
  const artistSlug = slugifyForPitchfork(primaryArtist);
  const albumSlug  = slugifyForPitchfork(title);
  if (!artistSlug || !albumSlug) { pitchforkCache.set(key, null); return null; }

  const url = `https://pitchfork.com/reviews/albums/${artistSlug}-${albumSlug}/`;
  try {
    await pitchforkWait();
    const html = await httpText(url, { "User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9" }, 15000);

    const { description, score, isBestNewMusic } = parsePitchforkReviewHtml(html);

    if (!description && score === null) { pitchforkCache.set(key, null); return null; }

    // Verify the review is for the right artist
    if (description) {
      const artistFirst = firstSignificantToken(primaryArtist);
      if (artistFirst && !normalize(description).includes(artistFirst)) {
        pitchforkCache.set(key, null);
        return null;
      }
    }

    const out = { description, score, isBestNewMusic, url, source: "Pitchfork" };
    pitchforkCache.set(key, out);
    return out;
  } catch (e) {
    if (DEBUG) console.error("[pitchfork]", e.message);
    pitchforkCache.set(key, null);
    return null;
  }
}

// ---------------------------------------------------------------------------
// Pitchfork review LISTINGS — the browsable "Latest / Best New Music" tabs and
// the external-search Pitchfork section. Ported from the Roon build. Scrapes
// the review-index pages' embedded __PRELOADED_STATE__ (primary), with the RSS
// feed as a Latest-tab fallback when the listing is blocked/unparseable.
//
// COMPLIANCE (UK law): only score / Best-New-Music flag / cover / link are
// surfaced — never the written review body. These listing items carry no body.
// ---------------------------------------------------------------------------
const PF_HEADERS = { "User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9" };
const PITCHFORK_LIST_TTL = 6 * 60 * 60 * 1000;
// Per-tab cache. Deliberately NOT a generic TTL memo: an EMPTY result (parse
// miss / blocked page) must NOT be cached, or a recovery is blocked for the
// whole TTL. Only non-empty results are stored.
const pitchforkLists = new Map();          // type → { at, items }
const pitchforkListPending = new Map();    // type → in-flight build Promise

function unCdata(s) {
  return s == null ? s : String(s).replace(/^\s*<!\[CDATA\[/, "").replace(/\]\]>\s*$/, "").trim();
}

// Best-effort artist name from a review URL when the listing parse didn't give
// a clean one: the slug is "<artist>-<album>", so strip the album-slug suffix
// and title-case what's left. Casing is approximate — fallback only.
function artistFromReviewUrl(url, albumTitle) {
  const m = /\/reviews\/albums\/([^\/?#]+)/.exec(url || "");
  if (!m) return null;
  let artistSlug = m[1];
  const albumSlug = slugifyForPitchfork(albumTitle || "");
  if (albumSlug && artistSlug.endsWith("-" + albumSlug)) {
    artistSlug = artistSlug.slice(0, artistSlug.length - albumSlug.length - 1);
  }
  const words = artistSlug.split("-").filter(Boolean);
  if (!words.length) return null;
  return words.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

// Parse the RSS album-reviews feed → [{ url, album, cover, date }].
async function fetchPitchforkRss() {
  await pitchforkWait();
  const xml = await httpText("https://pitchfork.com/feed/feed-album-reviews/rss", PF_HEADERS, 15000);
  const items = [];
  const itemRe = /<item\b[\s\S]*?<\/item>/gi;
  let im;
  while ((im = itemRe.exec(xml)) !== null) {
    const block = im[0];
    const pick = (re) => { const x = re.exec(block); return x ? unCdata(x[1]) : null; };
    const link = pick(/<link>([\s\S]*?)<\/link>/i);
    if (!link || !/\/reviews\/albums\//.test(link)) continue;
    const album = stripHtml(pick(/<title>([\s\S]*?)<\/title>/i) || "").trim();
    const cover = (/<media:thumbnail[^>]*\burl=["']([^"']+)["']/i.exec(block) || [])[1] || null;
    const date  = pick(/<pubDate>([\s\S]*?)<\/pubDate>/i);
    if (album) items.push({ url: link.split(/[?#]/)[0], album, cover, date });
  }
  return items;
}

// Extract window.__PRELOADED_STATE__ = {...} via brace-matching (a greedy
// regex can't balance braces on a ~2 MB page).
function extractPreloadedState(html) {
  const marker = html.indexOf("__PRELOADED_STATE__");
  if (marker === -1) return null;
  const start = html.indexOf("{", marker);
  if (start === -1) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < html.length; i++) {
    const c = html[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
    } else if (c === '"') inStr = true;
    else if (c === "{") depth++;
    else if (c === "}") { if (--depth === 0) return html.slice(start, i + 1); }
  }
  return null;
}

// Square cover URL from a listing item's image.sources (lg first).
function pfListingCover(node) {
  const s = node.image && node.image.sources;
  if (!s || typeof s !== "object") return null;
  return (s.lg && s.lg.url) || (s.xxl && s.xxl.url) || (s.md && s.md.url) || (s.sm && s.sm.url) || null;
}

// Walk the preloaded state and collect review-listing items. Matching on
// contentType + ratingValue + url (not a fixed path) keeps it resilient to
// container reshuffles.
function collectReviewItems(state) {
  const out = [];
  const seen = new Set();
  const stack = [state];
  let guard = 0;
  while (stack.length && guard++ < 500000) {
    const node = stack.pop();
    if (!node || typeof node !== "object") continue;
    if (Array.isArray(node)) { for (const x of node) if (x && typeof x === "object") stack.push(x); continue; }
    if (node.contentType === "review" && node.ratingValue && typeof node.url === "string") {
      const full = (node.url.startsWith("http") ? node.url : "https://pitchfork.com" + node.url).split(/[?#]/)[0];
      if (!seen.has(full)) {
        seen.add(full);
        let album = "";
        if (typeof node.dangerousHed === "string") album = stripHtml(node.dangerousHed).trim();
        if (!album && node.source && typeof node.source.hed === "string") album = node.source.hed.replace(/\*/g, "").trim();
        const artist = (node.subHed && typeof node.subHed.name === "string") ? node.subHed.name.trim() : null;
        const rv = node.ratingValue;
        const score = (rv.score != null && rv.score !== "") ? parseFloat(rv.score) : null;
        out.push({
          url: full, album, artist,
          score: Number.isFinite(score) ? score : null,
          isBestNewMusic: !!(rv.isBestNewMusic || rv.isBestNewReissue),
          cover: pfListingCover(node),
          date: node.pubDate || null
        });
      }
    }
    for (const k in node) { const v = node[k]; if (v && typeof v === "object") stack.push(v); }
  }
  return out;
}

async function fetchPitchforkListing(path) {
  await pitchforkWait();
  const html = await httpText("https://pitchfork.com" + path, PF_HEADERS, 15000);
  const raw = extractPreloadedState(html);
  if (!raw) { if (DEBUG) console.error("[pitchfork] no preloaded state in", path); return []; }
  let state;
  try { state = JSON.parse(raw); }
  catch (e) { if (DEBUG) console.error("[pitchfork] state parse failed:", e.message); return []; }
  return collectReviewItems(state);
}

function pfItemOut(x) {
  return {
    url: x.url, album: x.album || "", artist: x.artist || null, cover: x.cover || null,
    score: x.score != null ? x.score : null, isBestNewMusic: !!x.isBestNewMusic, date: x.date || null
  };
}

// Stable newest-first sort on ISO pubDate (lexicographic is correct for ISO).
function sortPfNewestFirst(items) {
  return items.sort((a, b) => String(b.date || "").localeCompare(String(a.date || "")));
}

// The listing is the primary source for both tabs; the Latest tab falls back
// to RSS (covers + title, artist from slug, no score) when the listing fails.
async function buildPitchforkList(type) {
  if (type === "best") {
    return sortPfNewestFirst((await fetchPitchforkListing("/reviews/best/albums/")).map(pfItemOut).filter(it => it.album));
  }
  let listErr = null, items = [];
  try { items = (await fetchPitchforkListing("/reviews/albums/")).map(pfItemOut).filter(it => it.album); }
  catch (e) { listErr = e; }
  if (items.length) return sortPfNewestFirst(items);
  const rss = await fetchPitchforkRss().catch(() => []);
  const out = rss
    .map(r => pfItemOut({ url: r.url, album: r.album, artist: artistFromReviewUrl(r.url, r.album), cover: r.cover, date: r.date }))
    .filter(it => it.album);
  if (!out.length && listErr) throw listErr;
  return out;
}

// Cached, in-flight-deduped listing fetch for one tab ("latest" | "best").
async function getPitchforkReviews(type) {
  const hit = pitchforkLists.get(type);
  if (hit && (Date.now() - hit.at) < PITCHFORK_LIST_TTL) return hit.items;
  if (pitchforkListPending.has(type)) return pitchforkListPending.get(type);
  const pending = (async () => {
    try {
      const items = await buildPitchforkList(type);
      if (items.length) pitchforkLists.set(type, { at: Date.now(), items });
      return items;
    } finally { pitchforkListPending.delete(type); }
  })();
  pitchforkListPending.set(type, pending);
  return pending;
}

// Match a query against both cached tabs (deduped) — the Pitchfork section of
// the external search. A blocked source just yields no section.
async function searchPitchforkReviews(q, limit) {
  const nq = normalize(q);
  if (!nq) return [];
  const [latest, best] = await Promise.all([
    getPitchforkReviews("latest").catch(() => []),
    getPitchforkReviews("best").catch(() => [])
  ]);
  const seen = new Set();
  const out = [];
  for (const it of [...latest, ...best]) {
    if (seen.has(it.url)) continue;
    seen.add(it.url);
    if (normalize(it.album).includes(nq) || normalize(it.artist || "").includes(nq)) {
      out.push(it);
      if (out.length >= limit) break;
    }
  }
  return out;
}

module.exports = { fetchPitchfork, getPitchforkReviews, searchPitchforkReviews };
