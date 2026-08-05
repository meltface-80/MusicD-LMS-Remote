/*
 * Random Albums — frontend
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */

// Single HTML-escaper shared by every module IIFE below (each is a separate
// scope, so this lives at script top-level). Use it on ANY LMS/network string
// interpolated into innerHTML — album/artist/track names can carry markup,
// especially online-library titles the owner didn't author.
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// Sample rate / bit depth on artwork. Top-level for the same reason as esc():
// tiles are built in one IIFE and the album modal paints in another, and both
// need the identical badge.
//
// The VALUE is always in the payload; the switch is one class on <body>, not a
// refetch. Server omits `quality` when it doesn't know, so the badge is never a
// guess.
const QUALITY_KEY = "musicd-show-quality";
let showQuality = false;
try { showQuality = localStorage.getItem(QUALITY_KEY) === "1"; }
catch (e) { /* private browsing — the default (off) stands */ }
function applyShowQuality() { document.body.classList.toggle("show-quality", showQuality); }
window.__showQuality = () => showQuality;
window.__setShowQuality = (on) => {
  showQuality = !!on;
  try { localStorage.setItem(QUALITY_KEY, showQuality ? "1" : "0"); }
  catch (e) { /* still applies for this session */ }
  applyShowQuality();
};
window.__qualityBadge = function (a) {
  if (!a || !a.quality) return null;
  const el = document.createElement("span");
  el.className = "album-quality" + (a.hires ? " is-hires" : "");
  el.textContent = a.quality;
  // "24/96" is unreadable to a screen reader; say it in words.
  const words = /\//.test(a.quality)
    ? a.quality.split("/")[0] + "-bit, " + a.quality.split("/")[1] + " kHz"
    : a.quality;
  el.title = words;
  el.setAttribute("aria-label", words);
  return el;
};
if (document.body) applyShowQuality();
else document.addEventListener("DOMContentLoaded", applyShowQuality);

(() => {
  // Disable pinch-zoom on iOS Safari (which ignores user-scalable=no since iOS 10)
  ["gesturestart", "gesturechange", "gestureend"].forEach((evt) => {
    document.addEventListener(evt, (e) => e.preventDefault(), { passive: false });
  });
  // Belt-and-braces: cancel any quick second tap (the iOS double-tap-to-zoom heuristic)
  let lastTouchEnd = 0;
  document.addEventListener("touchend", (e) => {
    const now = Date.now();
    if (now - lastTouchEnd < 320) e.preventDefault();
    lastTouchEnd = now;
  }, { passive: false });

  const grid       = document.getElementById("album-grid");
  const refreshBtn = document.getElementById("refresh-btn");
  const themeBtn   = document.getElementById("theme-toggle");
  const zoneSel    = document.getElementById("zone-select");
  const banner     = document.getElementById("status-banner");
  const toast      = document.getElementById("toast");

  const modal       = document.getElementById("album-modal");
  const modalImg    = document.getElementById("modal-img");
  const modalTitle  = document.getElementById("modal-title");
  const modalSub    = document.getElementById("modal-subtitle");
  const modalActs   = document.getElementById("modal-actions");
  const modalTracks = document.getElementById("modal-tracks");

  // Album select mode owns a contextual row in the top bar, not a bottom bar.
  const albumSelectRow       = document.getElementById("album-select-row");
  const albumSelectInfo      = document.getElementById("album-select-info");
  const albumOptionsBtn      = document.getElementById("album-options-btn");
  const albumActionCancelBtn = document.getElementById("album-select-cancel");
  const trackSelectRow       = document.getElementById("track-select-row");
  const trackSelectInfo      = document.getElementById("track-select-info");
  const trackOptionsBtn      = document.getElementById("track-options-btn");
  const trackSelectCancel    = document.getElementById("track-select-cancel");

  let currentAlbum = null;         // {offset,title,subtitle,image_key}
  let zones = [];
  let selectedZoneId = null;

  // Phone wall geometry (used by measurePhoneWall/computeAlbumCount below).
  // Declared BEFORE the computeAlbumCount() call on the next line — it's a
  // const, so referencing it from that call while it is still in its temporal
  // dead zone would throw and abort the whole app (blank screen). TEXT_BLOCK/
  // gaps mirror the .album-grid.phone-fit and phone .album-meta CSS.
  const PHONE_WALL = {
    COLS: 3,
    ROW_GAP: 10,     // .album-grid.phone-fit row-gap
    COL_GAP: 8,      // .album-grid.phone-fit column-gap
    TEXT_BLOCK: 51,  // worst case: 5px meta margin + 2 title lines (12×1.25=30) + 1px gap + artist (~15) = 51
                     // sized for the 2-line-title max so 4 rows never overflow into a scroll
    MIN_ART: 96,     // don't shrink art below this — drop a row instead
    TARGET_ROWS: 4
  };
  let albumCount = computeAlbumCount();
  let labelsActive = false;        // viewing the record-label browser?
  let unplayedWallActive = false;  // viewing the full "Not played in 6 months" grid?
  let albumSelectMode = false;
  let albumSelected = [];          // [{offset,title,subtitle}] albums chosen in select mode
  // The filter that the currently-open album modal belongs to. Usually the
  // active genre/tag filter, but a per-open override is used for label albums
  // so detail + play resolve offsets against the right list.
  let currentDetailFilter = null;

  // ----- Album filter (genre / tag) -----
  // null, or { type: "genre"|"tag", value: "<title>" }. Offsets in album
  // picks are positions *within the filtered list*, so the same filter must
  // accompany every /api/album and /api/play call.
  let activeFilter = null;
  try {
    const f = JSON.parse(localStorage.getItem("rra-filter") || "null");
    if (f && f.type && f.value) activeFilter = f;
  } catch (e) {} // corrupt localStorage entry — start with no filter
  function filterQSOf(f) {
    if (!f) return "";
    return "&filter_type=" + encodeURIComponent(f.type) +
           "&filter_value=" + encodeURIComponent(f.value) +
           (f.parent ? "&filter_parent=" + encodeURIComponent(f.parent) : "");
  }
  function filterQS() { return filterQSOf(activeFilter); }

  // ----- Theme -----
  // Four themes across two axes (see the header comment in style.css):
  // data-theme picks the family, data-palette picks the colours. Exposed on
  // window.__themes so the Settings pane can render the picker without
  // duplicating the list.
  const THEMES = [
    { id: "dark",         label: "Dark",        theme: "dark",  palette: "classic" },
    { id: "light",        label: "Light",       theme: "light", palette: "classic" },
    { id: "copper-dark",  label: "Copper dark", theme: "dark",  palette: "copper"  },
    { id: "brass-light",  label: "Brass light", theme: "light", palette: "copper"  },
  ];
  const THEME_KEY = "rra-theme-v2";
  window.__themes = THEMES;

  function applyTheme(id) {
    const t = THEMES.find(x => x.id === id) || THEMES[0];
    const root = document.documentElement;
    root.dataset.theme = t.theme;
    root.dataset.palette = t.palette;
    // Keep the iOS/Android status-bar tint in step with the actual painted
    // background — read it back rather than hardcoding a per-theme literal.
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) {
      const bg = getComputedStyle(root).getPropertyValue("--bg").trim();
      if (bg) meta.setAttribute("content", bg);
    }
    window.__currentTheme = t.id;
    return t.id;
  }
  window.__applyTheme = (id) => { applyTheme(id); localStorage.setItem(THEME_KEY, id); };

  (function initTheme() {
    let id = localStorage.getItem(THEME_KEY);
    if (!THEMES.some(t => t.id === id)) {
      // Migrate the v1 key, which only ever held "light" | "dark".
      const legacy = localStorage.getItem("rra-theme");
      if (legacy === "light" || legacy === "dark") id = legacy;
      else if (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches) id = "light";
      else id = "dark";
      localStorage.setItem(THEME_KEY, id);
    }
    applyTheme(id);
  })();

  // The old single icon-button toggle now just cycles dark <-> light within the
  // current palette; the Settings pane offers the full four-theme picker.
  if (themeBtn) {
    themeBtn.addEventListener("click", () => {
      const cur = THEMES.find(t => t.id === window.__currentTheme) || THEMES[0];
      const next = THEMES.find(t => t.palette === cur.palette && t.theme !== cur.theme) || THEMES[0];
      window.__applyTheme(next.id);
      document.dispatchEvent(new CustomEvent("themechange", { detail: next.id }));
    });
  }

  // ----- Sizing -----
  // Returns a fixed album count that exactly fills the responsive grid:
  //   Phone portrait   → 3 cols × measured rows (min 3×3 = 9, capped at 96)
  //   Tablet portrait  → 5×4  = 20
  //   Tablet landscape → 7×3  = 21
  //   Desktop          → 9×5  = 45

  // Measure the phone wall: return { rows, art } — the largest square art size
  // that lets `rows` rows fit the visible content box without scrolling. When
  // the wall is width-limited, art is the natural third-of-width (no shrink);
  // when height-limited, art shrinks so the target rows still fit. Falls back
  // to 3 rows if 4 can't fit at a reasonable size.
  function measurePhoneWall() {
    const P = PHONE_WALL;
    const mainEl = document.querySelector("main");
    let innerW, innerH;
    if (mainEl && mainEl.clientHeight > 0) {
      const cs = window.getComputedStyle(mainEl);
      // Subtract <main>'s padding — the bottom padding reserves the transport,
      // so innerH is the true height the grid can occupy.
      innerW = mainEl.clientWidth
        - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0);
      innerH = mainEl.clientHeight
        - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0);
    } else {
      // Pre-layout fallback: ~110px top bar, ~94px <main> vertical padding.
      innerW = window.innerWidth - 28;
      innerH = window.innerHeight - 110 - 94;
    }
    const artW = (innerW - (P.COLS - 1) * P.COL_GAP) / P.COLS;
    const artForRows = (r) => (innerH - (r - 1) * P.ROW_GAP - r * P.TEXT_BLOCK) / r;
    let rows = P.TARGET_ROWS;
    let art = Math.min(artW, artForRows(P.TARGET_ROWS));
    if (art < P.MIN_ART) {
      rows = 3;
      art = Math.min(artW, artForRows(3));
      if (art < P.MIN_ART) art = artW;   // very short screen: natural size, may scroll
    }
    return { rows, art: Math.max(1, Math.floor(art)) };
  }

  // Remove the phone-fit wall sizing (used when the labels browser takes over
  // the shared grid, so label tiles use their own default layout).
  function clearWallGridSizing() {
    grid.classList.remove("phone-fit");
    grid.style.removeProperty("--phone-art");
  }

  // Apply (or clear) the phone-fit sizing on the album wall grid. Called for
  // the album wall only — the labels browser removes it so it keeps its own
  // layout. Returns the album count for the wall, or null off-phone.
  function applyWallGridSizing() {
    if (Math.min(window.innerWidth, window.innerHeight) >= 768) {
      grid.classList.remove("phone-fit");
      grid.style.removeProperty("--phone-art");
      return null;
    }
    const m = measurePhoneWall();
    grid.style.setProperty("--phone-art", m.art + "px");
    grid.classList.add("phone-fit");
    return PHONE_WALL.COLS * m.rows;
  }

  function computeAlbumCount() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    const isLandscape = w > h;
    const minDim = Math.min(w, h);  // smallest dimension identifies phones vs tablets

    // Phone (narrowest side < 768 px): 3 columns, rows measured to fill the
    // screen (target 4) — see measurePhoneWall. Landscape is blocked via CSS.
    if (minDim < 768) {
      return Math.min(96, PHONE_WALL.COLS * measurePhoneWall().rows);  // 96 = server max
    }

    // Desktop (width ≥ 1200 px)
    if (w >= 1200) return 45;       // 9×5

    // Tablet (768–1199 px)
    return isLandscape ? 21 : 20;   // 7×3 or 5×4
  }

  // Re-fit the phone wall when the viewport resizes (Safari chrome collapsing,
  // iPad split view). Debounced; only applies to the actual phone-fit random
  // wall — it must not fire while Home, an active search, the labels browser,
  // or the "Not played" full grid are showing, since none of those are the
  // phone-fit wall and loadRandom() would silently replace their content.
  let _wallResizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(_wallResizeTimer);
    _wallResizeTimer = setTimeout(() => {
      if (labelsActive || unplayedWallActive) return;
      if (homeView && !homeView.classList.contains("hidden")) return;
      if (window.__searchActive && window.__searchActive()) return;
      if (Math.min(window.innerWidth, window.innerHeight) >= 768) return;
      const next = computeAlbumCount();
      if (next !== albumCount) loadRandom();   // rows changed → refetch to fill exactly
      else applyWallGridSizing();              // same rows → rescale art in place
    }, 250);
  });

  // ----- Home landing view -----
  const homeView     = document.getElementById("home-view");
  const homeSections = document.getElementById("home-sections");
  const homeUnplayed = document.getElementById("home-unplayed");
  const homeRandom   = document.getElementById("home-random");
  const homeLotw     = document.getElementById("home-lotw");
  const homeGenres   = document.getElementById("home-genres");
  const homeLibrary  = document.getElementById("home-library");
  const libControls  = document.getElementById("library-controls");
  const topbarBack   = document.getElementById("topbar-back");
  const topbarRefresh = document.getElementById("topbar-refresh");
  const topbarSearch  = document.getElementById("topbar-search");
  let homeSectionsLoaded = false;
  let homeLotwLoaded = false;   // set once the label-of-the-week row populates

  // Topbar chrome per view: Back button (off Home), Refresh button (random /
  // genre grids), and the Search box (Home only, beside the hamburger).
  function setTopbarNav(back, refresh, search) {
    if (topbarBack)    topbarBack.classList.toggle("hidden", !back);
    if (topbarRefresh) topbarRefresh.classList.toggle("hidden", !refresh);
    if (topbarSearch)  topbarSearch.classList.toggle("hidden", !search);
  }

  // Show the Home landing (hide the wall). The wall loads lazily when entered.
  function showHome() {
    unplayedWallActive = false;
    exitLibraryWall();   // retire the Sort/Focus bar with the wall it drove
    exitAlbumSelectMode();   // never strand the selection bar over Home
    if (window.__clearSearchIfActive) window.__clearSearchIfActive();  // drop stale search results
    if (window.__exitLabels) window.__exitLabels();   // leave the labels browser if active
    if (window.__exitArtistView) window.__exitArtistView();   // leave the artist view if active
    // Home is unfiltered — clear any active genre/tag filter so the breadcrumb
    // title goes away AND Home's full-library tiles resolve correctly.
    if (activeFilter) {
      activeFilter = null;
      try { localStorage.removeItem("rra-filter"); } catch (e) {} // localStorage optional (private browsing)
    }
    updateCountReadout(null);   // hide the genre/label breadcrumb
    setBanner(null);            // drop any error/empty banner left by a wall view
    if (homeView) homeView.classList.remove("hidden");
    if (homeSections) homeSections.classList.remove("hidden");  // in case a search hid them
    grid.classList.add("hidden");
    setTopbarNav(false, false, true);   // Home: search box, no Back/Refresh
    const m = document.querySelector("main");
    if (m) m.scrollTop = 0;
    // The unplayed + random rows keep their tiles for 5 minutes: every Back tap
    // lands here, and rebuilding ~60 fresh-random tiles each time re-fetched
    // ~60 cover images through the Roon Core — the single biggest repeated cost
    // in the app. Within the TTL the existing DOM (and the browser's image
    // cache) is reused; after it, or if a load failed, both rows reload fresh.
    const rowsFresh = homeRowsLoadedAt &&
      (Date.now() - homeRowsLoadedAt) < HOME_ROWS_TTL_MS &&
      homeUnplayed && homeUnplayed.querySelector(".album") &&
      homeRandom && homeRandom.querySelector(".album");
    if (!rowsFresh) {
      homeRowsLoadedAt = Date.now();
      loadHomeUnplayed();
      loadHomeRandom();
    }
    // Label of the week depends on the background labels scan, which may not be
    // ready on the first visit — retry each visit until it populates, then stop.
    if (!homeLotwLoaded) loadHomeLabelOfWeek();
    if (!homeSectionsLoaded) loadHomeGenres();
    if (homeLibraryStale || !rowHasContent(homeLibrary)) loadHomeLibrary();
  }
  // Reveal the album wall. opts.loadIfEmpty loads a fresh wall only when it has
  // no content yet (so passive reveals — opening an overlay from the menu —
  // don't leave an empty grid behind, without racing actions that render their
  // own content, e.g. labels/search).
  function showWall(opts) {
    unplayedWallActive = false;
    exitLibraryWall();
    if (window.__clearSearchIfActive) window.__clearSearchIfActive();  // drop stale search results
    if (window.__exitArtistView) window.__exitArtistView();   // leave the artist view if active
    if (homeView) homeView.classList.add("hidden");
    grid.classList.remove("hidden");
    setTopbarNav(true, true, false);   // random / genre grid: Back + Refresh, no search
    if (opts && opts.loadIfEmpty && !labelsActive && !grid.children.length) loadRandom();
  }
  window.__showHome = showHome;
  window.__showWall = showWall;
  // Labels/search reuse the shared grid but aren't the random-album wall, so
  // they show Back but not Refresh.
  window.__setTopbarNav = setTopbarNav;

  if (topbarBack)    topbarBack.addEventListener("click", showHome);
  if (topbarRefresh) topbarRefresh.addEventListener("click", () => loadRandom());

  // Home unplayed/random rows are reused within this TTL instead of being
  // rebuilt (and re-randomised) on every visit — see showHome.
  const HOME_ROWS_TTL_MS = 5 * 60 * 1000;
  let homeRowsLoadedAt = 0;

  // --- Home content persistence (instant open) --------------------------
  // The in-memory rows above live only as long as the page's JS context, so a
  // cold PWA open (the process is torn down when the app is backgrounded) reset
  // homeRowsLoadedAt to 0 and reloaded — and re-randomised — the entire Home
  // screen every single time. Persist the last rendered rows to localStorage
  // and repaint them instantly on open, then revalidate in the background
  // (stale-while-revalidate). Covers come straight from the browser's HTTP
  // cache (the server sends them immutable for a week), so it's a flash-free
  // repaint, not a reload. Bumped the key suffix if the cached shape changes.
  const HOME_CACHE_KEY = "rra-home-cache-v1";
  function saveHomeCache(patch) {
    try {
      const cur = JSON.parse(localStorage.getItem(HOME_CACHE_KEY) || "{}") || {};
      localStorage.setItem(HOME_CACHE_KEY, JSON.stringify(Object.assign(cur, patch)));
    } catch (e) {} // localStorage optional / over quota — persistence is best-effort
  }
  function readHomeCache() {
    try { return JSON.parse(localStorage.getItem(HOME_CACHE_KEY) || "null"); }
    catch (e) { return null; } // corrupt cache — ignore and load fresh
  }
  // A row already carries real content (tiles or genre cards), so a background
  // revalidation can swap fresh data in without first flashing "Loading…" over
  // the cached content the user is already looking at.
  const rowHasContent = (el) => !!(el && el.querySelector(".album, .home-genre-card"));

  // Build a Home tile that always opens full-library (filter: null) so its
  // offset resolves even when a genre filter was last active.
  function homeTile(a, extraClass) {
    const tile = buildAlbumTile(a, () => openAlbum(a, { source: "home", filter: null }));
    if (extraClass) tile.classList.add(extraClass);
    return tile;
  }

  // Render helper shared by the live loader and the instant-open cache repaint.
  function renderHomeUnplayed(aotd, albums) {
    albums = albums || [];
    homeUnplayed.innerHTML = "";
    if (!albums.length && !aotd) {
      homeUnplayed.innerHTML = '<div class="home-carousel-empty">Nothing here yet — play some music and check back.</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    if (aotd) {
      const tile = homeTile(aotd, "home-aotd");
      const wrap = tile.querySelector(".album-art-wrap");
      if (wrap) {
        const badge = document.createElement("span");
        badge.className = "aotd-badge";
        badge.textContent = "★ Today";
        wrap.appendChild(badge);
      }
      frag.appendChild(tile);
    }
    for (const a of albums) frag.appendChild(homeTile(a));
    homeUnplayed.appendChild(frag);
  }

  async function loadHomeUnplayed() {
    if (!homeUnplayed) return;
    // Don't flash "Loading…" over cached tiles the user is already looking at —
    // only when the row is genuinely empty (first ever load).
    if (!rowHasContent(homeUnplayed)) homeUnplayed.innerHTML = '<div class="home-carousel-empty">Loading…</div>';
    // Album of the day (completely random; hidden once played today) sits
    // first. Fetched in PARALLEL with the unplayed list — they're independent,
    // and awaiting them in sequence added a full round-trip to every reload.
    const aotdPromise = fetch("/api/home/album-of-the-day")
      .then(ar => ar.json()).catch(() => null);
    const unplayedPromise = fetch("/api/home/unplayed?months=6&count=30");
    unplayedPromise.catch(() => {});   // handled at the await below — this just silences the pre-await rejection warning
    const aj = await aotdPromise;
    const aotd = (aj && aj.album) ? aj.album : null;   // non-fatal — just no album-of-the-day
    try {
      const r = await unplayedPromise;
      if (r.status === 503) {
        if (!rowHasContent(homeUnplayed)) homeUnplayed.innerHTML = '<div class="home-carousel-empty">Waiting for LMS…</div>';
        homeRowsLoadedAt = 0;   // retry on the next Home visit
        return;   // keep any cached tiles + cache untouched while the index builds
      }
      const j = await r.json();
      const albums = (j && j.albums) || [];
      renderHomeUnplayed(aotd, albums);
      // Persist only a non-empty row (mirrors random/genres) so a legitimately
      // empty response can't be cached and shown as "Nothing here yet" next
      // open. Timestamp is per-row so a stale sibling can't ride a fresh one's
      // freshness (see hydrateHomeFromCache).
      if (albums.length || aotd) saveHomeCache({ unplayed: { aotd, albums }, unplayedAt: Date.now() });
    } catch (e) {
      if (!rowHasContent(homeUnplayed)) homeUnplayed.innerHTML = '<div class="home-carousel-empty">Couldn’t load.</div>';
      homeRowsLoadedAt = 0;   // retry on the next Home visit
    }
  }

  // Random-albums row (reuses /api/random-albums, no filter → full library).
  // Reloaded when the Home rows go stale (see showHome's TTL); tapping the
  // header opens the full random wall (same as the hamburger "Random albums").
  function renderHomeRandom(albums) {
    albums = albums || [];
    homeRandom.innerHTML = "";
    if (!albums.length) {
      homeRandom.innerHTML = '<div class="home-carousel-empty">No albums.</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    for (const a of albums) frag.appendChild(homeTile(a));   // filter:null → offsets resolve
    homeRandom.appendChild(frag);
  }

  async function loadHomeRandom() {
    if (!homeRandom) return;
    if (!rowHasContent(homeRandom)) homeRandom.innerHTML = '<div class="home-carousel-empty">Loading…</div>';
    try {
      const r = await fetch("/api/random-albums?count=30");
      if (r.status === 503) {
        if (!rowHasContent(homeRandom)) homeRandom.innerHTML = '<div class="home-carousel-empty">Waiting for LMS…</div>';
        homeRowsLoadedAt = 0;   // retry on the next Home visit
        return;   // keep any cached tiles while the index builds
      }
      const j = await r.json();
      const albums = (j && j.albums) || [];
      renderHomeRandom(albums);
      if (albums.length) saveHomeCache({ random: albums, randomAt: Date.now() });
    } catch (e) {
      if (!rowHasContent(homeRandom)) homeRandom.innerHTML = '<div class="home-carousel-empty">Couldn’t load.</div>';
      homeRowsLoadedAt = 0;   // retry on the next Home visit
    }
  }

  // Label of the week — one label featured for the whole ISO week (backend
  // picks deterministically). Retried each Home visit until it populates (the
  // labels scan runs in the background), then left alone. Tapping the header
  // opens the full label view.
  // Returns true when it painted a real row (a qualifying label with albums).
  function renderHomeLotw(label, albums) {
    const titleEl = document.getElementById("home-lotw-title");
    albums = albums || [];
    const sec = homeLotw.closest(".home-section");
    if (!label || !albums.length) {
      // No qualifying label yet (labels still scanning / library too small):
      // hide the whole section rather than show an empty row.
      if (sec) sec.classList.add("hidden");
      return false;
    }
    if (titleEl) titleEl.textContent = "Label of the week: " + label;
    homeLotw.dataset.label = label;
    if (sec) sec.classList.remove("hidden");   // un-hide if a prior attempt hid it
    homeLotw.innerHTML = "";
    const frag = document.createDocumentFragment();
    for (const a of albums) frag.appendChild(homeTile(a));   // full-hierarchy offsets → filter:null
    homeLotw.appendChild(frag);
    return true;
  }

  async function loadHomeLabelOfWeek() {
    if (!homeLotw) return;
    if (!rowHasContent(homeLotw)) homeLotw.innerHTML = '<div class="home-carousel-empty">Loading…</div>';
    try {
      const r = await fetch("/api/home/label-of-the-week");
      const j = await r.json();
      const albums = (j && j.albums) || [];
      if (j && j.label && albums.length) {
        renderHomeLotw(j.label, albums);
        homeLotwLoaded = true;   // populated — stop retrying on future visits
        saveHomeCache({ lotw: { label: j.label, albums } });
      } else if (!rowHasContent(homeLotw)) {
        // Empty 200 (labels index still building after a restart returns
        // {label:null} — not a 503). Only hide the section when nothing is
        // cached; otherwise keep the hydrated row rather than blanking it.
        renderHomeLotw(null, []);
      }
    } catch (e) {
      // Transient failure: keep any cached row rather than blanking it. Only
      // hide the section when there's nothing cached to fall back on.
      if (!rowHasContent(homeLotw)) {
        const sec = homeLotw.closest(".home-section");
        if (sec) sec.classList.add("hidden");
      }
    }
  }

  // Full-screen "Not played in 6 months" grid — reached by tapping the section
  // header. Fills the main grid with a larger unplayed list (tiles open
  // unfiltered, like the Home row) and shows a Back button to Home.
  async function showUnplayedWall() {
    unplayedWallActive = true;
    exitLibraryWall();
    exitAlbumSelectMode();
    if (window.__exitLabels) window.__exitLabels();
    if (activeFilter) { activeFilter = null; try { localStorage.removeItem("rra-filter"); } catch (e) {} }
    if (homeView) homeView.classList.add("hidden");
    if (homeSections) homeSections.classList.remove("hidden");
    grid.classList.remove("hidden");
    clearWallGridSizing();  // standard grid, not phone-fit wall
    setTopbarNav(true, false, false);   // Back (to Home), no Refresh, no search
    setCountText("Not played in 6 months");
    const m = document.querySelector("main");
    if (m) m.scrollTop = 0;
    renderSkeletons(computeAlbumCount());
    try {
      const r = await fetch("/api/home/unplayed?months=6&count=96");
      if (r.status === 503) {
        const j = await r.json().catch(() => ({}));
        setBanner(j.error || "Waiting for LMS. Check the server connection in Settings.", true);
        grid.innerHTML = ""; return;
      }
      const j = await r.json();
      const albums = (j && j.albums) || [];
      grid.innerHTML = "";
      if (!albums.length) {
        setBanner("Nothing here yet — play some music and check back.", false);
        return;
      }
      setBanner(null);
      const frag = document.createDocumentFragment();
      for (const a of albums) frag.appendChild(homeTile(a));   // filter:null → offsets resolve
      grid.appendChild(frag);
    } catch (e) {
      grid.innerHTML = "";
      setBanner("Couldn’t load: " + e.message, true);
    }
  }

  // ----- Library: ordered, paginated browse with Sort + Focus -------------
  // Every other wall in this app is a random SAMPLE; this is the one place the
  // whole library is browsable in a deterministic order, so the view state
  // (sort, direction, facets) is persisted and paging is stable.
  const LIB_PAGE = 60;
  const LIB_VIEW_KEY = "rra-library-view-v1";
  const LIB_SORT_OPTIONS = [
    { id: "album",  label: "Album name",   asc: "A → Z", desc: "Z → A" },
    { id: "artist", label: "Artist",       asc: "A → Z", desc: "Z → A" },
    { id: "genre",  label: "Genre",        asc: "A → Z", desc: "Z → A",
      note: "from the genre LMS reports for each album" },
    { id: "year",   label: "Release year", asc: "Oldest first", desc: "Newest first",
      note: "albums with no year are listed last" },
    { id: "added",  label: "Date added",   asc: "Oldest first", desc: "Newest first",
      note: "when the album reached your library, from LMS" },
    { id: "plays",  label: "Most played",  asc: "Least played first", desc: "Most played first",
      note: "from plays this app has seen — roughly the last year" },
    { id: "lastplayed", label: "Last played", asc: "Longest ago first", desc: "Most recent first",
      note: "from plays this app has seen — roughly the last year" },
    { id: "random", label: "Random" },
  ];
  const LIB_PLAYED_OPTIONS = [
    { id: "any",   label: "Any" },
    { id: "never", label: "Never played" },
    { id: "6",     label: "Not in 6 months" },
    { id: "12",    label: "Not in 12 months" },
  ];
  // A Live Playlist's own properties, distinct from the rules that pick its
  // albums. These mirror the server's vocabulary (lib/liveplaylists.js) — the
  // server re-validates anyway, so a drift here is a UI that offers a value
  // that silently becomes the default rather than a broken playlist.
  const LP_LIMITS        = [25, 50, 100, 200, 400];
  const LP_LIMIT_DEFAULT = 100;
  const LP_ORDERS        = [
    { id: "album",  label: "Album order" },
    { id: "random", label: "Random" },
  ];
  const LP_ORDER_DEFAULT = "album";
  // Alphabetical sorts read A→Z by default; quantitative ones read biggest-first.
  const libSortHasDir     = (id) => id !== "random";
  const libSortDefaultDir = (id) =>
    (id === "year" || id === "added" || id === "plays" || id === "lastplayed") ? "desc" : "asc";
  const libNextSeed = () => 1 + Math.floor(Math.random() * 100000);

  // Every facet id the server can offer. Held generically so adding a facet is
  // a server-side change only; a value prefixed "!" means EXCLUDE.
  const LIB_FACET_IDS = ["genre", "source", "decade", "label", "letter", "added"];
  const emptyFacets = () => { const o = {}; for (const id of LIB_FACET_IDS) o[id] = []; return o; };
  let libView = { sort: "album", dir: "asc", seed: 1, played: "any", ...emptyFacets() };
  let libraryWallActive = false;
  let libFacets = null;
  const libWall = { seq: 0, offset: 0, loading: false, done: false, total: 0 };

  (function loadLibView() {
    try {
      const v = JSON.parse(localStorage.getItem(LIB_VIEW_KEY) || "null");
      if (v && typeof v === "object") {
        const arr = (x) => Array.isArray(x) ? x.map(String) : [];
        libView = {
          sort:   LIB_SORT_OPTIONS.some(o => o.id === v.sort) ? v.sort : "album",
          dir:    v.dir === "desc" ? "desc" : "asc",
          seed:   Number.isFinite(v.seed) && v.seed > 0 ? v.seed : 1,
          played: LIB_PLAYED_OPTIONS.some(o => o.id === v.played) ? v.played : "any",
          ...emptyFacets(),
        };
        for (const id of LIB_FACET_IDS) libView[id] = arr(v[id]);
      }
    } catch (e) {} // corrupt/absent — defaults are fine
  })();
  const saveLibView = () => { try { localStorage.setItem(LIB_VIEW_KEY, JSON.stringify(libView)); } catch (e) {} };
  const libFocusCount = () =>
    LIB_FACET_IDS.reduce((n, id) => n + (libView[id] || []).length, 0) +
    (libView.played !== "any" ? 1 : 0);
  const libSortLabel = () => (LIB_SORT_OPTIONS.find(o => o.id === libView.sort) || LIB_SORT_OPTIONS[0]).label;
  function libDirLabel() {
    const o = LIB_SORT_OPTIONS.find(x => x.id === libView.sort) || LIB_SORT_OPTIONS[0];
    return libView.dir === "desc" ? (o.desc || "Descending") : (o.asc || "Ascending");
  }
  function libViewQuery() {
    const p = new URLSearchParams();
    p.set("sort", libView.sort);
    p.set("dir", libView.dir);
    if (libView.sort === "random") p.set("seed", String(libView.seed));
    for (const id of LIB_FACET_IDS) for (const v of (libView[id] || [])) p.append(id, v);
    if (libView.played !== "any") p.set("played", libView.played);
    return p.toString();
  }

  // The Home "Library" row follows the SORT you set on the Library wall, so the
  // two agree instead of the row being permanently A-Z. Deliberately sort+dir
  // only, not the Focus facets: the row is labelled "Library" and links to the
  // whole library, so silently hiding most of it behind an active filter would
  // be a surprise. Set from the Library wall via applyLibView().
  let homeLibraryStale = false;
  function homeLibrarySortQuery() {
    const p = new URLSearchParams();
    p.set("sort", libView.sort);
    p.set("dir", libView.dir);
    if (libView.sort === "random") p.set("seed", String(libView.seed));
    return p.toString();
  }
  async function loadHomeLibrary() {
    if (!homeLibrary) return;
    homeLibraryStale = false;
    try {
      const r = await fetch("/api/library/albums?offset=0&count=30&" + homeLibrarySortQuery(), { cache: "no-store" });
      if (!r.ok) throw new Error("HTTP " + r.status);
      const j = await r.json();
      const albums = (j && j.albums) || [];
      if (!albums.length) return;
      homeLibrary.innerHTML = "";
      const frag = document.createDocumentFragment();
      for (const a of albums) frag.appendChild(homeTile(a));
      homeLibrary.appendChild(frag);
      saveHomeCache({ library: albums });
    } catch (e) {
      // Transient — keep whatever the cache hydrated rather than blanking it.
    }
  }

  async function fetchLibraryPage(mySeq, first) {
    if (libWall.loading || libWall.done) return;
    libWall.loading = true;
    try {
      const r = await fetch("/api/library/albums?offset=" + libWall.offset + "&count=" + LIB_PAGE +
        "&" + libViewQuery(), { cache: "no-store" });
      if (mySeq !== libWall.seq) return;                  // a newer view superseded us
      if (r.status === 503) {
        const j = await r.json().catch(() => ({}));
        setBanner(j.error || "Waiting for LMS. Check the server connection in Settings.", true);
        libWall.done = true; return;
      }
      const j = await r.json();
      if (mySeq !== libWall.seq) return;
      const albums = (j && j.albums) || [];
      if (first) {
        grid.innerHTML = "";
        if (!albums.length) {
          setBanner(libFocusCount()
            ? "Nothing matches this focus — try clearing a filter."
            : "Nothing here yet.", false);
          libWall.done = true;
          setCountText("Library");
          return;
        }
        setBanner(null);
      }
      const frag = document.createDocumentFragment();
      for (const a of albums) frag.appendChild(homeTile(a));
      grid.appendChild(frag);
      libWall.offset += albums.length;
      // End of library = a short (or empty) page. `total` is display only, so a
      // mid-scroll change to it can't strand the loop.
      libWall.done = albums.length < LIB_PAGE;
      libWall.total = (j && typeof j.total === "number") ? j.total : libWall.offset;
      setCountText((libFocusCount() ? "Library · " + libWall.total + " matching" : "Library · " + libWall.total) +
        (libWall.total === 1 ? " album" : " albums"));
    } catch (e) {
      if (mySeq === libWall.seq && first) { grid.innerHTML = ""; setBanner("Couldn’t load: " + e.message, true); }
      libWall.done = true;
    } finally {
      if (mySeq === libWall.seq) libWall.loading = false;
    }
  }

  async function applyLibView() {
    saveLibView();
    homeLibraryStale = true;   // Home's Library row follows this sort
    renderLibraryControls();
    libWall.seq++;
    const mySeq = libWall.seq;
    libWall.offset = 0; libWall.loading = false; libWall.done = false;
    renderSkeletons(computeAlbumCount());
    await fetchLibraryPage(mySeq, true);
    // A wide desktop grid can swallow the first page without ever overflowing,
    // which would leave infinite scroll with nothing to trigger it — keep
    // filling until the page actually scrolls.
    const m = document.querySelector("main");
    while (libraryWallActive && mySeq === libWall.seq && !libWall.done && !libWall.loading &&
           m && m.scrollHeight <= m.clientHeight + 200) {
      const before = libWall.offset;
      await fetchLibraryPage(mySeq, false);
      if (libWall.offset === before) break;
    }
  }

  async function showLibraryWall() {
    unplayedWallActive = false;
    libraryWallActive = true;
    exitAlbumSelectMode();
    if (window.__exitLabels) window.__exitLabels();
    if (window.__exitArtistView) window.__exitArtistView();
    if (activeFilter) { activeFilter = null; try { localStorage.removeItem("rra-filter"); } catch (e) {} }
    if (homeView) homeView.classList.add("hidden");
    if (homeSections) homeSections.classList.remove("hidden");
    grid.classList.remove("hidden");
    clearWallGridSizing();
    setTopbarNav(true, false, false);
    setCountText("Library");
    const m = document.querySelector("main");
    if (m) m.scrollTop = 0;
    if (!libFacets) loadLibFacets();
    await applyLibView();
  }
  // Any other view taking over the grid must retire the Library's controls and
  // stop its infinite scroll, or a stale sort bar outlives the wall it drove.
  function exitLibraryWall() {
    if (!libraryWallActive) return;
    libraryWallActive = false;
    exitAlbumSelectMode();
    libWall.seq++;
    if (libControls) libControls.classList.add("hidden");
  }
  window.__exitLibraryWall = exitLibraryWall;
  // Used after a change that rebuilt the index server-side (an album merge), so
  // the open grid doesn't keep showing rows that no longer exist.
  window.__refreshCurrentView = () => {
    if (libraryWallActive) applyLibView();
    else if (!homeView.classList.contains("hidden")) { homeLibraryStale = true; showHome(); }
    else loadRandom();
  };
  window.__showLibraryWall = showLibraryWall;

  async function loadLibFacets() {
    try {
      const r = await fetch("/api/library/facets", { cache: "no-store" });
      if (r.ok) libFacets = await r.json();
    } catch (e) {} // Focus sheet degrades to whatever groups it can show
  }

  {
    const mainEl = document.querySelector("main");
    if (mainEl) mainEl.addEventListener("scroll", () => {
      if (!libraryWallActive || libWall.loading || libWall.done) return;
      if (mainEl.scrollTop + mainEl.clientHeight >= mainEl.scrollHeight - 600) {
        fetchLibraryPage(libWall.seq, false);
      }
    }, { passive: true });
  }

  // --- Library controls: [Sort pill] [direction] [Focus pill] ---
  function renderLibraryControls() {
    if (!libControls) return;
    libControls.classList.toggle("hidden", !libraryWallActive);
    if (!libraryWallActive) return;
    libControls.innerHTML = "";

    // Flat text controls with a hairline under the row, matching the Roon
    // build. The separate direction button is GONE: it was a third boxed
    // control between two others, and direction is a property of the sort, so
    // it belongs in the sort sheet (which already flips on a re-tap). The row
    // still SHOWS the direction as part of the sort's own label, so nothing is
    // hidden — it just isn't its own button.
    const focus = document.createElement("button");
    focus.type = "button";
    const n = libFocusCount();
    focus.className = "lib-ctl lib-ctl-focus" + (n ? " is-active" : "");
    const chev = document.createElement("span");
    chev.className = "lib-ctl-chevron"; chev.setAttribute("aria-hidden", "true"); chev.textContent = "\u203a";
    const ftext = document.createElement("span");
    ftext.className = "lib-ctl-text"; ftext.textContent = "Focus";
    focus.appendChild(chev); focus.appendChild(ftext);
    if (n) {
      const badge = document.createElement("span");
      badge.className = "lib-ctl-badge"; badge.textContent = String(n);
      focus.appendChild(badge);
    }
    // () => openLibFocusSheet(null), NOT the bare function: a click handler is
    // called with an Event, which would arrive as an edit target and make the
    // next save overwrite a playlist chosen at random.
    focus.addEventListener("click", () => openLibFocusSheet(null));
    libControls.appendChild(focus);

    const sort = document.createElement("button");
    sort.type = "button";
    sort.className = "lib-ctl lib-ctl-sort";
    const stext = document.createElement("span");
    stext.className = "lib-ctl-text"; stext.textContent = libSortLabel();
    const arrow = document.createElement("span");
    arrow.className = "lib-ctl-arrow"; arrow.setAttribute("aria-hidden", "true");
    // A label, not a button: it says which way the current sort runs. Random
    // has no direction, so it shows the reshuffle glyph instead.
    arrow.textContent = libSortHasDir(libView.sort) ? (libView.dir === "desc" ? "\u2193" : "\u2191") : "\u27f3";
    const caret = document.createElement("span");
    caret.className = "lib-ctl-caret"; caret.setAttribute("aria-hidden", "true"); caret.textContent = "\u2304";
    sort.appendChild(stext); sort.appendChild(arrow); sort.appendChild(caret);
    sort.setAttribute("aria-label", libSortLabel() + " \u2014 " + libDirLabel() + ", change sort");
    sort.addEventListener("click", openLibSortSheet);
    libControls.appendChild(sort);
  }

  // Shared bottom-sheet builder — every Library picker is built with this so
  // they can't drift apart visually.
  function openLibSheet(title, buildBody, footer, onClose) {
    const backdrop = document.createElement("div");
    backdrop.className = "lib-sheet-backdrop";
    const sheet = document.createElement("div");
    sheet.className = "lib-sheet";
    const head = document.createElement("div");
    head.className = "lib-sheet-head";
    const h = document.createElement("h2"); h.textContent = title;
    const x = document.createElement("button");
    x.type = "button"; x.className = "icon-btn"; x.textContent = "✕";
    x.setAttribute("aria-label", "Close");
    head.appendChild(h); head.appendChild(x);
    const body = document.createElement("div"); body.className = "lib-sheet-body";
    sheet.appendChild(head); sheet.appendChild(body);
    // Must fire for the X, the backdrop AND any footer button that calls
    // close() — a dismissal path that skips it is how an abandoned edit
    // stays armed.
    const close = () => { backdrop.remove(); document.body.style.overflow = ""; if (onClose) onClose(); };
    x.addEventListener("click", close);
    backdrop.addEventListener("click", (e) => { if (e.target === backdrop) close(); });
    if (footer) {
      const foot = document.createElement("div"); foot.className = "lib-sheet-foot";
      footer(foot, close);
      sheet.appendChild(foot);
    }
    backdrop.appendChild(sheet);
    document.body.appendChild(backdrop);
    document.body.style.overflow = "hidden";
    buildBody(body, close);
    return close;
  }

  window.__openLibSheet = openLibSheet;
  window.__selectedZoneId = () => selectedZoneId;

  function openLibSortSheet() {
    openLibSheet("Sort by", (body) => {
      const paint = () => {
        body.innerHTML = "";
        for (const opt of LIB_SORT_OPTIONS) {
          const on = libView.sort === opt.id;
          const row = document.createElement("button");
          row.type = "button";
          row.className = "lib-sort-row" + (on ? " is-on" : "");
          const arrow = document.createElement("span");
          arrow.className = "lib-sort-arrow";
          // Only the selected row shows an arrow, so every label stays aligned.
          arrow.textContent = on ? (libSortHasDir(opt.id) ? (libView.dir === "desc" ? "↓" : "↑") : "⟳") : "";
          const txt = document.createElement("span");
          const lab = document.createElement("div"); lab.className = "lib-sort-label"; lab.textContent = opt.label;
          txt.appendChild(lab);
          if (on && libSortHasDir(opt.id)) {
            const d = document.createElement("div"); d.className = "lib-sort-note"; d.textContent = libDirLabel();
            txt.appendChild(d);
          } else if (opt.note) {
            const nt = document.createElement("div"); nt.className = "lib-sort-note"; nt.textContent = opt.note;
            txt.appendChild(nt);
          }
          row.appendChild(arrow); row.appendChild(txt);
          row.addEventListener("click", () => {
            if (on) {
              // Tapping the current sort reverses it (Roon's pattern — no
              // separate direction control inside the sheet).
              if (libSortHasDir(opt.id)) libView.dir = libView.dir === "desc" ? "asc" : "desc";
              else libView.seed = libNextSeed();
            } else {
              libView.sort = opt.id;
              libView.dir = libSortDefaultDir(opt.id);
              if (!libSortHasDir(opt.id)) libView.seed = libNextSeed();
            }
            paint();
            applyLibView();
          });
          body.appendChild(row);
        }
      };
      paint();
    });
  }

  // `editTarget` is a PARAMETER, never module state: scoped to exactly one
  // sheet-open lifecycle, so an edit the user abandons cannot still be armed
  // when they later save something unrelated from this same sheet.
  function openLibFocusSheet(editTarget) {
    // A bare handler receives a click Event, which must never be mistaken for a
    // playlist to edit. Testing for an Event says exactly that, where the old
    // `.id` test also threw away a legitimate id-less target.
    if (editTarget instanceof Event || !editTarget || typeof editTarget !== "object") editTarget = null;
    // The playlist's own properties, held OUTSIDE libView: two playlists can
    // share a rule set and differ in how many albums they deliver or what order
    // they come out in, which is also why the server slices rather than folding
    // them into the query.
    let editLimit = (editTarget && editTarget.limit) || LP_LIMIT_DEFAULT;
    let editOrder = (editTarget && editTarget.order) || LP_ORDER_DEFAULT;
    // Editing loads the playlist's rules into the live view WITHOUT persisting
    // them: opening Edit and closing again must leave the user's own Library
    // sort/focus exactly as they left it.
    const viewBefore = currentLibViewSnapshot();
    let committed = false;
    if (editTarget && editTarget.view) {
      applyViewToLibView(editTarget.view);
      renderLibraryControls();
    }
    // Which sections are expanded, remembered across repaints so clearing the
    // last chip in a section doesn't collapse it under your finger.
    const openSections = new Set();
    openLibSheet(editTarget ? "Edit rules" : "Focus", (body) => {
      const paint = () => {
        body.innerHTML = "";
        const f = libFacets || {};
        const facets = Array.isArray(f.facets) ? f.facets : [];

        // A chip has THREE states: off -> include -> exclude -> off. Exclusion
        // is encoded in the value itself ("!Jazz"), so saved Live Playlists and
        // the query string round-trip with no schema change.
        const stateOf = (arr, v) => arr.includes(v) ? "on" : (arr.includes("!" + v) ? "not" : "off");
        const cycle = (arr, v) => {
          const i = arr.indexOf(v), j = arr.indexOf("!" + v);
          if (i !== -1) { arr.splice(i, 1); arr.push("!" + v); }
          else if (j !== -1) { arr.splice(j, 1); }
          else { arr.push(v); }
        };

        // `openByDefault` is for the sections that aren't filters at all — the
        // playlist's own Order and size. They have no "active count" to open
        // them, and collapsing the two controls this screen exists to set would
        // hide them behind a tap for no gain.
        const section = (id, label, activeCount, build, openByDefault) => {
          const sec = document.createElement("div");
          sec.className = "lib-facet-sec";
          const head = document.createElement("button");
          head.type = "button"; head.className = "lib-facet-head";
          const open = openSections.has(id) || activeCount > 0 || !!openByDefault;
          head.setAttribute("aria-expanded", open ? "true" : "false");
          const t = document.createElement("span");
          t.className = "lib-sheet-section-label"; t.textContent = label;
          head.appendChild(t);
          if (activeCount > 0) {
            const b = document.createElement("span");
            b.className = "lib-facet-count"; b.textContent = String(activeCount);
            head.appendChild(b);
          }
          const car = document.createElement("span");
          car.className = "lib-facet-caret"; car.textContent = open ? "\u2013" : "+";
          head.appendChild(car);
          head.addEventListener("click", () => {
            if (openSections.has(id)) openSections.delete(id); else openSections.add(id);
            paint();
          });
          sec.appendChild(head);
          if (open) { openSections.add(id); build(sec); }
          body.appendChild(sec);
        };

        // `local` marks a chip that changes the PLAYLIST rather than the query —
        // Order and size don't alter what matches, so they must not re-run the
        // library view.
        const chip = (host, label, state, onTap, local) => {
          const c = document.createElement("button");
          c.type = "button";
          c.className = "lib-chip" + (state === "on" ? " is-on" : state === "not" ? " is-not" : "");
          c.textContent = label;
          if (state === "not") c.setAttribute("aria-label", "Excluding " + label);
          c.addEventListener("click", () => { onTap(); paint(); if (!local) applyLibView(); });
          host.appendChild(c);
        };
        const note = (host, text) => {
          const n = document.createElement("div");
          n.className = "lib-facet-note"; n.textContent = text;
          host.appendChild(n);
        };

        // The playlist's OWN properties lead, ahead of every filter. They are
        // decisions about the playlist rather than about which albums match,
        // and burying them under a stack of collapsed facets meant scrolling
        // past the whole sheet to reach them.
        if (editTarget) {
          section("lp-order", "Order", 0, (sec) => {
            const wrap = document.createElement("div"); wrap.className = "lib-chips";
            sec.appendChild(wrap);
            for (const o of LP_ORDERS) {
              chip(wrap, o.label, editOrder === o.id ? "on" : "off", () => { editOrder = o.id; }, true);
            }
            note(sec, "Album order queues them in the sort you chose. Random shuffles " +
                      "which albums, and what order they play in. The shuffle is fixed " +
                      "per playlist, so it stays put while you scroll rather than " +
                      "reshuffling under you.");
          }, true);
          section("lp-limit", "Playlist size", 0, (sec) => {
            const wrap = document.createElement("div"); wrap.className = "lib-chips";
            sec.appendChild(wrap);
            for (const n of LP_LIMITS) {
              chip(wrap, String(n), editLimit === n ? "on" : "off", () => { editLimit = n; }, true);
            }
            note(sec, "How many albums this playlist actually plays. A rule can match your " +
                      "whole library, but every album is a separate command to the server — " +
                      "400 albums is thousands of tracks.");
          }, true);
        }

        for (const fc of facets) {
          const sel = libView[fc.id] || (libView[fc.id] = []);
          if (!fc.values.length && !sel.length) continue;
          section(fc.id, fc.label, sel.length, (sec) => {
            const wrap = document.createElement("div"); wrap.className = "lib-chips";
            sec.appendChild(wrap);
            const shown = new Set(fc.values.map(v => String(v.value)));
            for (const v of fc.values) {
              const val = String(v.value);
              chip(wrap, v.label + " (" + v.count + ")", stateOf(sel, val), () => cycle(sel, val));
            }
            // Anything selected that isn't in the server's top slice still gets
            // a chip, or a saved filter would be impossible to clear.
            for (const raw of sel) {
              const val = raw.charAt(0) === "!" ? raw.slice(1) : raw;
              if (shown.has(val)) continue;
              chip(wrap, val, stateOf(sel, val), () => cycle(sel, val));
            }
            if (fc.total_values > fc.values.length) {
              note(sec, "Showing the " + fc.values.length + " most common of " +
                        fc.total_values.toLocaleString() + ".");
            }
            if (f.total && fc.covered != null && fc.covered < f.total) {
              note(sec, fc.covered.toLocaleString() + " of " + f.total.toLocaleString() +
                        " albums have a value here.");
            }
          });
        }

        if (f.hasPlays) {
          section("played", "Listening", libView.played !== "any" ? 1 : 0, (sec) => {
            const wrap = document.createElement("div"); wrap.className = "lib-chips";
            sec.appendChild(wrap);
            for (const p of LIB_PLAYED_OPTIONS) {
              chip(wrap, p.label, libView.played === p.id ? "on" : "off",
                   () => { libView.played = p.id; });
            }
            note(sec, "Based on plays this app has seen, matched by album title.");
          });
        }

        if (!body.children.length) {
          const e = document.createElement("div");
          e.className = "lib-facet-note";
          e.textContent = "No filters available yet \u2014 the library is still being indexed.";
          body.appendChild(e);
        }
        // Tapping a chip twice EXCLUDES rather than clearing — say so once.
        const hint = document.createElement("div");
        hint.className = "lib-facet-note";
        hint.style.marginTop = "12px";
        hint.textContent = "Tap once to include, again to exclude, again to clear.";
        body.appendChild(hint);
      };
      paint();
    }, (foot, close) => {
      const clear = document.createElement("button");
      clear.type = "button"; clear.className = "action-btn";
      clear.textContent = "Clear all";
      clear.addEventListener("click", () => {
        committed = true;
        for (const id of LIB_FACET_IDS) libView[id] = [];
        libView.played = "any";
        close(); applyLibView();
      });
      // Saving the CURRENT sort+focus as a Live Playlist. When the sheet was
      // opened by "Edit" on an existing playlist, this writes back to that id
      // rather than forking a copy.
      const save = document.createElement("button");
      save.type = "button"; save.className = "action-btn";
      save.textContent = editTarget ? "Save changes" : "Save as Live Playlist";
      save.addEventListener("click", () => {
        committed = true;
        close();
        // Carry the chosen order and size into the save — this sheet is the
        // only place they can be set, so they have to travel with the thing
        // being saved.
        saveLivePlaylistPrompt(editTarget
          ? Object.assign({}, editTarget, { limit: editLimit, order: editOrder })
          : null);
      });
      const done = document.createElement("button");
      done.type = "button"; done.className = "action-btn primary";
      done.textContent = "Show albums";
      done.addEventListener("click", () => { committed = true; close(); });
      foot.appendChild(clear); foot.appendChild(save); foot.appendChild(done);
    }, () => {
      // Abandoned (X or backdrop) while editing a saved playlist — put the
      // user's own view back. It was never persisted, so there is nothing on
      // disk to undo, only the live view and the controls.
      if (editTarget && !committed) { applyViewToLibView(viewBefore); applyLibView(); }
    });
  }

  // ----- Live Playlists: saving / editing the current view -----
  // A Live Playlist stores the QUERY, not a track list, so it re-evaluates on
  // every open. The rules are exactly the Library's sort + focus, which is why
  // this lives next to them rather than in the Live Playlists overlay.
  // Copy a saved view into the live one. Facet values are normalised to
  // STRINGS because every comparison on the client is against String(value) —
  // a numeric decade would render its chip as off and toggling would push a
  // duplicate rather than clearing it.
  function applyViewToLibView(v) {
    if (!v) return;
    libView = {
      sort: v.sort || "album", dir: v.dir === "desc" ? "desc" : "asc", seed: v.seed || 1,
      played: v.played || "any", ...emptyFacets(),
    };
    for (const id of LIB_FACET_IDS) libView[id] = (v[id] || []).map(String);
  }

  const currentLibViewSnapshot = () => {
    const o = { sort: libView.sort, dir: libView.dir, seed: libView.seed, played: libView.played };
    for (const id of LIB_FACET_IDS) o[id] = (libView[id] || []).slice();
    return o;
  };

  async function saveLivePlaylistPrompt(existing) {
    const suggested = (existing && existing.name) || suggestLivePlaylistName();
    const name = window.prompt(existing ? "Rename this Live Playlist" : "Name this Live Playlist", suggested);
    if (name === null) return;                       // cancelled
    const trimmed = String(name).trim();
    if (!trimmed) { showToast("Give it a name first", "error"); return; }
    try {
      const r = await fetch("/api/live-playlists", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: existing && existing.id, name: trimmed, view: currentLibViewSnapshot(),
          // Absent for a brand-new playlist, which takes the server's defaults
          // and can be adjusted from Edit.
          limit: existing && existing.limit, order: existing && existing.order,
        })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      const pl = j.playlist || {};
      const n = pl.total || 0, matched = pl.matched != null ? pl.matched : n;
      // What it DELIVERS vs what the rule FOUND. Reporting only the second made
      // every capped playlist read as a failure to play the whole thing.
      showToast(matched > n
        ? "Saved “" + trimmed + "” — it plays " + n + " of the " +
          matched.toLocaleString() + " albums that match. Change that with Edit."
        : "Saved “" + trimmed + "” — " + n + " album" + (n === 1 ? "" : "s") + " right now");
      if (window.__refreshLivePlaylists) window.__refreshLivePlaylists();
    } catch (e) { showToast(e.message, "error"); }
  }

  // A name built from the active rules beats "My playlist" as a starting point.
  function suggestLivePlaylistName() {
    const bits = [];
    if (libView.played === "never") bits.push("Never played");
    else if (libView.played !== "any") bits.push("Unplayed " + libView.played + "m");
    // Only INCLUDED values make a good name; "not Pop" reads badly in a title.
    const inc = (id) => (libView[id] || []).filter(v => v.charAt(0) !== "!");
    if (inc("decade").length) bits.push(inc("decade").slice().sort().map(d => d + "s").join(" + "));
    if (inc("genre").length)  bits.push(inc("genre").join(" + "));
    if (inc("label").length)  bits.push(inc("label").join(" + "));
    if (inc("source").length && !bits.length) bits.push(inc("source").join(" + "));
    return bits.length ? bits.join(" ") : "My Live Playlist";
  }

  // Called by the Live Playlists overlay's Edit button: load the saved rules
  // into the Library view, show the wall, and open Focus armed to write back.
  // Edit deliberately does NOT touch libView here — the sheet applies the
  // playlist's rules itself, unpersisted, and restores them if abandoned.
  window.__editLivePlaylist = function (pl) {
    if (!pl || !pl.id) return;
    showLibraryWall().then(() => openLibFocusSheet(pl));
  };
  window.__saveLivePlaylistFromView = () => saveLivePlaylistPrompt(null);

  // Header taps: Not played → full unplayed grid; Random albums → full random
  // wall; Label of the week → label view; Library → the browsable library.
  {
    const libraryTitle = document.getElementById("home-library-title");
    if (libraryTitle) {
      libraryTitle.addEventListener("click", showLibraryWall);
      libraryTitle.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); showLibraryWall(); }
      });
    }
    const unplayedTitle = document.getElementById("home-unplayed-title");
    if (unplayedTitle) {
      unplayedTitle.addEventListener("click", showUnplayedWall);
      unplayedTitle.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); showUnplayedWall(); }
      });
    }
    const randTitle = document.getElementById("home-random-title");
    if (randTitle) {
      const goRandom = () => { if (window.__applyFilter) window.__applyFilter(null); };
      randTitle.addEventListener("click", goRandom);
      randTitle.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); goRandom(); }
      });
    }
    const lotwTitle = document.getElementById("home-lotw-title");
    if (lotwTitle) {
      const goLabel = () => {
        const name = homeLotw && homeLotw.dataset.label;
        if (name && window.__showLabelAlbums) window.__showLabelAlbums(name);
      };
      lotwTitle.addEventListener("click", goLabel);
      lotwTitle.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); goLabel(); }
      });
    }
  }

  // Weighted-random pick from a list of { title, count }.
  function pickWeightedSub(items) {
    let total = 0;
    for (const it of items) total += Math.max(1, it.count || 1);
    let r = Math.random() * total;
    for (const it of items) { r -= Math.max(1, it.count || 1); if (r <= 0) return it; }
    return items[items.length - 1];
  }

  // Render the genre buttons from card descriptors ({label, genre} or
  // {label, group, parent}). Shared by the live loader and the cache repaint;
  // the descriptors are plain data, so they persist and rebuild identically.
  function renderHomeGenres(cards) {
    cards = cards || [];
    homeGenres.innerHTML = "";
    if (!cards.length) {
      homeGenres.innerHTML = '<div class="home-carousel-empty">No genres found.</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    for (const c of cards) {
      const card = document.createElement("button");
      card.type = "button";
      card.className = "home-genre-card";
      card.textContent = c.label;
      card.addEventListener("click", () => {
        if (!window.__applyFilter) return;
        if (c.group) {
          // Pick a random sub-genre from the group; the breadcrumb keeps the
          // group label (e.g. "Rock/Metal"). Refreshing the grid reshuffles
          // that sub-genre; re-tapping the button picks a new one.
          const sub = pickWeightedSub(c.group);
          window.__applyFilter({ type: "genre", value: sub.title, parent: c.parent, label: c.label });
        } else {
          window.__applyFilter({ type: "genre", value: c.genre });
        }
      });
      frag.appendChild(card);
    }
    homeGenres.appendChild(frag);
  }

  async function loadHomeGenres() {
    if (!homeGenres) return;
    if (!rowHasContent(homeGenres)) homeGenres.innerHTML = '<div class="home-carousel-empty">Loading…</div>';
    try {
      const [genresRes, groupsRes] = await Promise.all([
        fetch("/api/filters/genres").catch(() => null),
        fetch("/api/home/genre-groups").catch(() => null)
      ]);
      if ((genresRes && genresRes.status === 503) || (groupsRes && groupsRes.status === 503)) {
        if (!rowHasContent(homeGenres)) homeGenres.innerHTML = '<div class="home-carousel-empty">Waiting for LMS…</div>';
        return;   // keep any cached cards while the index builds
      }
      const genresJ = genresRes ? await genresRes.json().catch(() => ({})) : {};
      const groupsJ = groupsRes ? await groupsRes.json().catch(() => ({})) : {};
      // Pull extra genres up front — splitting Pop/Rock adds a card, and we trim
      // down to an even count afterwards so the 2-column grid has full rows.
      const top = ((genresJ && genresJ.genres) || []).slice(0, 16); // biggest first
      const groups = groupsJ || {};
      const parent = groups.parent;

      // Build card descriptors. The "Pop/Rock" parent is split into two buttons:
      // "Rock/Metal" (curated rock/metal sub-genres) and "Pop" (pop sub-genres).
      // Rock/Metal and Pop are pushed FIRST so they always survive the trim.
      const cards = [];
      const haveRockMetal = groups.rockmetal && groups.rockmetal.length;
      const havePop = groups.pop && groups.pop.length;
      if (parent && (haveRockMetal || havePop)) {
        if (haveRockMetal) cards.push({ label: "Rock/Metal", group: groups.rockmetal, parent });
        if (havePop) cards.push({ label: "Pop", group: groups.pop, parent });
      }
      for (const g of top) {
        // Drop the raw Pop/Rock parent — it's represented by the split buttons.
        if (parent && /pop\s*\/\s*rock/i.test(g.title)) continue;
        cards.push({ label: g.title, genre: g.title });
      }

      // Target an even 12 buttons so the grid rows are balanced on every screen.
      // If we have more, keep the first 12 (biggest, Rock/Metal + Pop first); if
      // fewer, drop the last one when the count is odd.
      const MAX_CARDS = 12;
      if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
      if (cards.length % 2 === 1) cards.length -= 1;

      if (cards.length) {
        renderHomeGenres(cards);
        homeSectionsLoaded = true;   // populated — stop retrying on future visits
        saveHomeCache({ genres: cards });
      } else if (!rowHasContent(homeGenres)) {
        // Empty 200 (index still building after a restart) — keep the hydrated
        // cards if we have them; only show "No genres found." when nothing is
        // cached, rather than blanking a good cached row.
        renderHomeGenres([]);
      }
    } catch (e) {
      if (!rowHasContent(homeGenres)) homeGenres.innerHTML = '<div class="home-carousel-empty">Couldn’t load genres.</div>';
    }
  }

  // Instant open: repaint the last persisted Home rows immediately, before we've
  // even reconnected to Roon. Returns true if it painted the main content, so
  // the boot path can reveal Home right away instead of a blank "Connecting…".
  // The live loaders (called by showHome once paired) then revalidate silently,
  // swapping fresh data in without a "Loading…" flash. Seeding homeRowsLoadedAt
  // lets the existing 5-minute TTL skip the unplayed/random refetch entirely on
  // a quick reopen — but only when BOTH rows are recent: it's seeded from the
  // OLDER of the two per-row timestamps, so a stale sibling (e.g. unplayed kept
  // an old cache while random refreshed) forces a silent revalidation instead
  // of riding the fresh row's freshness.
  function hydrateHomeFromCache() {
    const c = readHomeCache();
    if (!c) return false;
    let painted = false;
    if (c.unplayed && homeUnplayed) { renderHomeUnplayed(c.unplayed.aotd, c.unplayed.albums); painted = rowHasContent(homeUnplayed) || painted; }
    if (c.random   && homeRandom)   { renderHomeRandom(c.random);                              painted = rowHasContent(homeRandom)   || painted; }
    if (c.lotw     && homeLotw)     { renderHomeLotw(c.lotw.label, c.lotw.albums); }
    if (c.genres   && homeGenres)   { renderHomeGenres(c.genres); }
    if (c.library  && homeLibrary)  {
      homeLibrary.innerHTML = "";
      for (const a of c.library) homeLibrary.appendChild(homeTile(a));
    }
    if (!painted) return false;
    if (typeof c.unplayedAt === "number" && typeof c.randomAt === "number") {
      homeRowsLoadedAt = Math.min(c.unplayedAt, c.randomAt);   // honour the TTL across reopens
    }
    // Reveal Home so the cached content is actually on screen while we reconnect.
    if (homeView)     homeView.classList.remove("hidden");
    if (homeSections) homeSections.classList.remove("hidden");
    grid.classList.add("hidden");
    setTopbarNav(false, false, true);   // Home chrome: search box, no Back/Refresh
    return true;
  }

  // ----- Toast / banner -----
  let toastTimer = null;
  // `ms` is for the rare message the user has to be able to READ — a server
  // failure naming the command that broke is no use if it's gone in 2.4s.
  function showToast(msg, kind, ms) {
    toast.textContent = msg;
    toast.classList.remove("hidden", "error");
    if (kind === "error") toast.classList.add("error");
    requestAnimationFrame(() => toast.classList.add("show"));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toast.classList.remove("show");
      setTimeout(() => toast.classList.add("hidden"), 250);
    }, ms || 2400);
  }
  function setBanner(msg, isError) {
    if (!msg) { banner.classList.add("hidden"); banner.textContent = ""; return; }
    banner.textContent = msg;
    banner.classList.toggle("error", !!isError);
    banner.classList.remove("hidden");
  }

  // ----- Scan progress bar -----
  function updateScanBar(progress) {
    const bar  = document.getElementById("scan-progress-bar");
    const fill = document.getElementById("scan-progress-fill");
    if (!bar || !fill) return;
    if (progress === null || progress === undefined) {
      bar.classList.add("hidden");
      fill.style.width = "0%";
    } else {
      bar.classList.remove("hidden");
      fill.style.width = Math.round((progress || 0) * 100) + "%";
    }
  }

  // ----- Skeletons -----
  function renderSkeletons(n) {
    grid.innerHTML = "";
    for (let i = 0; i < n; i++) {
      const el = document.createElement("div");
      el.className = "album skeleton";
      el.innerHTML = `
        <div class="album-art-wrap"></div>
        <div class="album-meta">
          <div class="album-title">&nbsp;</div>
          <div class="album-artist">&nbsp;</div>
        </div>`;
      grid.appendChild(el);
    }
  }

  // ----- Long-press utility -----
  window.__addLongPress = (el, cb) => addLongPress(el, cb);
  function addLongPress(el, callback) {
    let timer = null;
    let moved = false;
    const onStart = () => { moved = false; timer = setTimeout(() => { if (!moved) { if (navigator.vibrate) navigator.vibrate(25); callback(); } }, 500); };
    const onMove  = () => { moved = true; clearTimeout(timer); timer = null; };
    const onEnd   = () => { clearTimeout(timer); timer = null; };
    el.addEventListener("touchstart",  onStart,  { passive: true });
    el.addEventListener("touchmove",   onMove,   { passive: true });
    el.addEventListener("touchend",    onEnd);
    el.addEventListener("touchcancel", onEnd);
    el.addEventListener("mousedown",   onStart);
    el.addEventListener("mousemove",   onMove);
    el.addEventListener("mouseup",     onEnd);
    el.addEventListener("contextmenu", e => e.preventDefault());
  }

  // ----- Render -----
  // Tile art size matched to the display: tiles render at ~150-220px CSS, so
  // 500px covers were ~2.8× oversized on DPR-2 iPads — each one an on-demand
  // rescale by the Roon Core. Rounded to coarse steps so the whole session
  // shares a handful of cache keys (server LRU + browser cache); the 300px
  // floor keeps DPR-1 desktops sharp on wide walls where tiles exceed 200px.
  const TILE_IMG_SIZE = Math.min(500, Math.max(300, Math.ceil((190 * (window.devicePixelRatio || 1)) / 100) * 100));

  // ----- Qobuz favourite hearts -----
  // A library album imported from Qobuz carries a `qobuz_id`; a search result
  // carries a token. Both can be favourited/un-favourited through the LMS Qobuz
  // plugin (favourite-only — no library rescan is triggered).
  // Tick shown inside a selected track's checkbox. The hollow ring is CSS.
  const TICK_SVG = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>';
  const HEART_PATH = "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z";
  const heartSvg = (filled) =>
    '<svg viewBox="0 0 24 24" width="18" height="18" fill="' + (filled ? "currentColor" : "none") +
    '" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="' + HEART_PATH + '"/></svg>';
  const HEART_FILLED  = heartSvg(true);
  const HEART_OUTLINE = heartSvg(false);
  let _qobuzFavIds = null, _qobuzFavPromise = null;
  function ensureQobuzFavs() {
    if (_qobuzFavIds) return Promise.resolve(_qobuzFavIds);
    if (!_qobuzFavPromise) {
      _qobuzFavPromise = fetch("/api/qobuz/favorites").then(r => (r.ok ? r.json() : { ids: [] }))
        .then(j => (_qobuzFavIds = new Set(j.ids || []))).catch(() => (_qobuzFavIds = new Set()));
    }
    return _qobuzFavPromise;
  }
  function setHeart(btn, filled) {
    btn.classList.toggle("is-fav", !!filled);
    btn.innerHTML = filled ? HEART_FILLED : HEART_OUTLINE;
    btn.title = filled ? "Remove from Qobuz favourites" : "Add to Qobuz favourites";
    btn.setAttribute("aria-label", btn.title);
  }
  async function qobuzFavPost(url, body, btn) {
    const want = !btn.classList.contains("is-fav");
    btn.disabled = true;
    try {
      const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ...body, favorite: want }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      setHeart(btn, j.favorite);
      if (_qobuzFavIds && body.qobuz_id) { j.favorite ? _qobuzFavIds.add(body.qobuz_id) : _qobuzFavIds.delete(body.qobuz_id); }
      showToast(j.favorite ? "Added to Qobuz favourites" : "Removed from Qobuz favourites");
    } catch (e) { showToast(e.message, "error"); }
    finally { btn.disabled = false; }
  }

  // Build a single album tile. onClick defaults to opening the album modal,
  // but callers (e.g. the label browser) can override it to carry a filter.
  function buildAlbumTile(a, onClick) {
    const btn = document.createElement("button");
    btn.className = "album";
    btn.type = "button";
    btn.setAttribute("aria-label",
      `${a.title || "Untitled"}${a.subtitle ? " by " + a.subtitle : ""}`);
    btn.dataset.albumKey = (a.title || "").toLowerCase().trim();
    if (a.offset != null) btn.dataset.offset = String(a.offset);

    const artWrap = document.createElement("div");
    artWrap.className = "album-art-wrap";
    // A playlist tile has no cover of its own, so it borrows a 2x2 of the
    // first four DISTINCT album covers its tracks come from (a.art). Anything
    // with a single image_key renders as an ordinary album cover.
    const mosaic = Array.isArray(a.art) ? a.art.filter(Boolean).slice(0, 4) : [];
    if (mosaic.length) {
      artWrap.classList.add("is-mosaic");
      artWrap.dataset.mosaic = String(mosaic.length);
      for (const key of mosaic) {
        const img = document.createElement("img");
        img.loading = "lazy"; img.alt = "";
        img.src = `/api/image/${encodeURIComponent(key)}?size=${TILE_IMG_SIZE}`;
        img.onerror = () => img.remove();
        artWrap.appendChild(img);
      }
    } else if (a.image_key) {
      const img = document.createElement("img");
      img.loading = "lazy"; img.alt = "";
      img.src = `/api/image/${encodeURIComponent(a.image_key)}?size=${TILE_IMG_SIZE}`;
      img.onerror = () => { artWrap.classList.add("no-image"); img.remove(); };
      artWrap.appendChild(img);
    } else {
      artWrap.classList.add("no-image");
    }

    // Online-source badge (top-right of the art). Best-effort: only lights up
    // when the backend threaded a recognised `source` (from the LMS library
    // extend the map below to badge other sources.
    if (a.source === "qobuz") {
      const badge = document.createElement("div");
      badge.className = "album-source-badge qobuz";
      badge.setAttribute("aria-label", "Qobuz");
      badge.title = "Qobuz";
      // Same "Q" logomark as the header toggle (Arcticons, CC BY 4.0).
      badge.innerHTML =
        '<svg width="16" height="16" viewBox="0 0 48 48" fill="none" stroke="currentColor" ' +
        'stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<path d="M39.203 39.203A21.43 21.43 0 0 0 45.5 24c0-11.874-9.626-21.5-21.5-21.5S2.5 12.126 2.5 24S12.126 45.5 24 45.5c4.89 0 9.4-1.633 13.012-4.383"/>' +
        '<circle cx="24" cy="24" r="4.873"/>' +
        '<path d="M32.944 32.944L45.5 45.5"/></svg>';
      artWrap.appendChild(badge);
    }

    // Favourite heart for a Qobuz album that IS in the library. It was imported
    // BECAUSE it's a Qobuz favourite, so the heart starts filled; a tap removes
    // the favourite (matched by title+artist — favourite rows carry no id).
    // Only when the server can still reach Qobuz. The album stays badged as a
    // Qobuz album either way — that is where it came from, and hiding it would
    // make an online album look local — but the heart is an ACTION against the
    // account, so it goes when the account does.
    if (a.source === "qobuz" && a.qobuz_id && (!window.__serviceUsable || window.__serviceUsable("qobuz"))) {
      const heart = document.createElement("button");
      heart.type = "button"; heart.className = "album-fav-heart";
      setHeart(heart, true);
      heart.addEventListener("click", (e) => { e.stopPropagation(); qobuzFavPost("/api/qobuz/favorite-id", { title: a.title, artist: a.subtitle }, heart); });
      artWrap.appendChild(heart);
    }

    if (a.part_count > 1) {
      const badge = document.createElement("span");
      badge.className = "album-merge-badge";
      badge.textContent = a.part_count + " discs";
      artWrap.appendChild(badge);
    }

    // Sample rate / bit depth, bottom-left. ALWAYS built, shown or hidden by one
    // class on <body>: rendering it conditionally would mean every tile already
    // on screen kept its old state until something rebuilt it, so the Appearance
    // toggle would look like it had done nothing until you navigated away.
    const qb = window.__qualityBadge && window.__qualityBadge(a);
    if (qb) artWrap.appendChild(qb);

    // Mark tiles already in this app's Favourites. The key is stamped on the
    // element so a later refresh can repaint without rebuilding the grid —
    // /api/favourites/keys exists precisely for this.
    if (window.__favKeyOf) {
      const fk = window.__favKeyOf(a.title, a.subtitle || a.artist);
      if (fk) {
        btn.dataset.favKey = fk;
        if (window.__isFavourite && window.__isFavourite(a)) btn.classList.add("is-app-fav");
        const mark = document.createElement("span");
        mark.className = "album-fav-mark";
        mark.setAttribute("aria-hidden", "true");
        mark.innerHTML = '<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';
        artWrap.appendChild(mark);
      }
    }

    const meta = document.createElement("div");
    meta.className = "album-meta";
    meta.innerHTML = `<div class="album-title"></div><div class="album-artist"></div>`;
    meta.querySelector(".album-title").textContent  = a.title    || "Untitled";
    meta.querySelector(".album-artist").textContent = a.subtitle || "";

    btn.appendChild(artWrap);
    btn.appendChild(meta);
    btn.addEventListener("click", () => {
      // In select mode a tap toggles selection — even for tiles that carry a
      // custom open handler (Home carousels, label albums). A tile with no
      // offset can't be selected, so it opens as usual rather than being inert.
      if (albumSelectMode && a.offset != null) { handleAlbumTileSelect(btn, a); return; }
      (onClick || (() => openAlbum(a)))();
    });
    // Long-press STARTS multi-select, on every grid that uses this builder.
    // Only a library album can be selected: every batch action keys off
    // `offset`, so a catalogue album or a playlist tile has nothing to act on.
    // Those keep the context sheet, which is the only way to favourite a Qobuz
    // catalogue album from the screen you found it on.
    addLongPress(btn, () => {
      if (albumSelectMode) { handleAlbumTileSelect(btn, a); return; }
      if (a.offset != null && !btn.classList.contains("is-playlist")) {
        enterAlbumSelectMode(); handleAlbumTileSelect(btn, a); return;
      }
      // A playlist tile isn't an album at all — no offset, no catalogue token,
      // so neither selection nor the sheet's actions have a target.
      if (btn.classList.contains("is-playlist")) return;
      if (window.__openAlbumSheet) window.__openAlbumSheet(a, { tileEl: btn, allowSelect: false });
    });
    return btn;
  }

  // ----- Album multi-select chrome -----------------------------------------
  // The contextual row REPLACES the normal top row while selecting, so the
  // grid below never shifts, and every action sits in one dropdown list —
  // the old fixed bottom bar ran its last buttons off the edge of a phone.
  const topbarRow = document.querySelector(".topbar-row");

  function enterAlbumSelectMode() {
    albumSelectMode = true;
    if (topbarRow)       topbarRow.classList.add("hidden");
    if (albumSelectRow)  albumSelectRow.classList.remove("hidden");
    updateAlbumActionBar();
  }

  function exitAlbumSelectMode() {
    albumSelectMode = false;
    albumSelected = [];
    closeAlbumOptionsMenu();
    if (albumSelectRow) albumSelectRow.classList.add("hidden");
    if (topbarRow)      topbarRow.classList.remove("hidden");
    // Clear the highlight on every selectable album tile — the grid plus the
    // Home carousels — but leave the labels browser's own selection alone.
    document.querySelectorAll(".album.is-selected:not(.label-tile)").forEach(b => b.classList.remove("is-selected"));
  }

  // Named updateAlbumActionBar still, because every action handler calls it to
  // repaint after a failure; it now writes the top-bar readout.
  function updateAlbumActionBar() {
    const n = albumSelected.length;
    if (albumSelectInfo) {
      albumSelectInfo.textContent = n === 0
        ? "Tap albums to select"
        : n + " album" + (n === 1 ? "" : "s") + " selected";
    }
    if (albumOptionsBtn) albumOptionsBtn.disabled = n === 0;
    // Repaint an open menu rather than leaving stale gating behind it.
    if (albumOptionsMenu) buildAlbumOptionsMenu(albumOptionsMenu);
  }

  window.__exitAlbumSelectMode = exitAlbumSelectMode;

  function handleAlbumTileSelect(btn, a) {
    // Match on offset — the tile identity the batch endpoints use. Tiles with
    // no offset never reach here (long-press and tap both gate on it), so an
    // `undefined === undefined` collision can't collapse two selections.
    if (a.offset == null) return;
    const idx = albumSelected.findIndex(x => x.offset === a.offset);
    if (idx === -1) { albumSelected.push(a); btn.classList.add("is-selected"); }
    else            { albumSelected.splice(idx, 1); btn.classList.remove("is-selected"); }
    if (!albumSelected.length) { exitAlbumSelectMode(); return; }
    updateAlbumActionBar();
  }

  // ----- The Options dropdown ----------------------------------------------
  // Rendered into <body> with fixed positioning off the button's rect: the top
  // bar is its own stacking context, so a menu nested inside it could not sit
  // above a full-screen dismiss backdrop.
  let albumOptionsMenu = null;
  let albumOptionsBackdrop = null;

  // The button the open menu belongs to, so closing resets the right one —
  // albums and tracks share this machinery (v1.0.58).
  let albumOptionsOwner = null;
  function closeAlbumOptionsMenu() {
    if (albumOptionsBackdrop) albumOptionsBackdrop.remove();
    if (albumOptionsMenu) albumOptionsMenu.remove();
    albumOptionsBackdrop = null;
    albumOptionsMenu = null;
    if (albumOptionsOwner) albumOptionsOwner.setAttribute("aria-expanded", "false");
    albumOptionsOwner = null;
  }

  // One row builder for both menus. Returns a `row(label, note, disabled, fn)`
  // bound to this menu element.
  function optionsRowFactory(menu) {
    return (label, note, disabled, onClick) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "lib-sort-row";
      b.setAttribute("role", "menuitem");
      if (disabled) { b.disabled = true; b.setAttribute("aria-disabled", "true"); }
      const txt = document.createElement("span");
      const l = document.createElement("div");
      l.className = "lib-sort-label"; l.textContent = label;
      txt.appendChild(l);
      if (note) {
        const nn = document.createElement("div");
        nn.className = "lib-sort-note"; nn.textContent = note;
        txt.appendChild(nn);
      }
      b.appendChild(txt);
      if (!disabled) {
        b.addEventListener("click", async () => {
          closeAlbumOptionsMenu();
          try { await onClick(); } catch (e) { showToast(e.message, "error"); }
        });
      }
      menu.appendChild(b);
      return b;
    };
  }

  // Rendered into <body> with fixed positioning off the button's rect: the top
  // bar is its own stacking context, and the album view paints over it — a
  // nested menu could not sit above either.
  function openOptionsMenu(btn, build, label) {
    if (albumOptionsMenu) {
      const same = albumOptionsOwner === btn;
      closeAlbumOptionsMenu();
      if (same) return;
    }
    if (!btn) return;
    albumOptionsBackdrop = document.createElement("div");
    albumOptionsBackdrop.className = "dropdown-backdrop";
    albumOptionsBackdrop.addEventListener("click", closeAlbumOptionsMenu);
    const menu = document.createElement("div");
    menu.className = "dropdown-menu";
    menu.setAttribute("role", "menu");
    menu.setAttribute("aria-label", label);
    albumOptionsMenu = menu;
    albumOptionsOwner = btn;
    build(menu);
    document.body.appendChild(albumOptionsBackdrop);
    document.body.appendChild(menu);
    // Right-align under the button, clamped into the viewport.
    const r = btn.getBoundingClientRect();
    const w = menu.offsetWidth;
    let left = r.right - w;
    if (left < 8) left = 8;
    if (left + w > window.innerWidth - 8) left = Math.max(8, window.innerWidth - 8 - w);
    menu.style.left = left + "px";
    menu.style.top  = (r.bottom + 6) + "px";
    menu.style.maxHeight = Math.max(160, window.innerHeight - r.bottom - 20) + "px";
    btn.setAttribute("aria-expanded", "true");
  }
  window.__closeOptionsMenu = closeAlbumOptionsMenu;
  window.__openOptionsMenu  = openOptionsMenu;

  function buildAlbumOptionsMenu(menu) {
    menu.innerHTML = "";
    const n = albumSelected.length;
    const row = optionsRowFactory(menu);

    row("Play now", null, n === 0, () => invokeAlbumMulti("play_now"));
    row("Add to end of queue", null, n === 0, () => invokeAlbumMulti("queue"));
    // Long-press no longer opens the context sheet on a library album, so the
    // menu has to carry the sheet's un-favourite too — otherwise favouriting
    // would be one gesture and un-favouriting would need the album screen.
    // Like add-multi, it only offers the reverse when the WHOLE selection is
    // already favourited; a mixed selection is always an add.
    const allFav = n > 0 && window.__isFavourite && albumSelected.every(a => window.__isFavourite(a));
    row(allFav ? "Remove from Favourites" : "Add to Favourites",
        "Kept in this app, separate from your Qobuz favourites",
        n === 0, () => albumActFavourite(allFav));
    row("Add to playlist", null, n === 0, albumActPlaylist);
    // Merge is the one action that needs at least two albums.
    row("Merge into one album", n < 2 ? "Select two or more discs to merge them" : null,
        n < 2, albumActMerge);
    row("Clear selection", null, false, () => exitAlbumSelectMode());
  }

  function openAlbumOptionsMenu() {
    openOptionsMenu(albumOptionsBtn, buildAlbumOptionsMenu, "Album actions");
  }

  if (albumOptionsBtn) albumOptionsBtn.addEventListener("click", openAlbumOptionsMenu);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && albumOptionsMenu) { closeAlbumOptionsMenu(); return; }
    if (e.key === "Escape" && albumSelectMode) exitAlbumSelectMode();
  });

  // Builds the album tiles into the grid. Shared by the random wall and search.
  function renderAlbumGrid(albums) {
    grid.innerHTML = "";
    const frag = document.createDocumentFragment();
    for (const a of albums) frag.appendChild(buildAlbumTile(a));
    grid.appendChild(frag);
  }

  function renderAlbums(albums) {
    if (!albums.length) {
      grid.innerHTML = "";
      setBanner("No albums were returned. Is your library indexed?", true);
      return;
    }
    setBanner(null);
    renderAlbumGrid(albums);
  }

  // ----- Random albums fetch -----
  // ----- Library album count -----
  // The topbar no longer shows a persistent "N albums" readout — it crowded
  // the controls on phones. The library total now lives in Settings; the
  // topbar element is reused only for transient CONTEXT (the active filter
  // value and the labels-browser breadcrumb) and is hidden on the plain wall.
  // Set the topbar context text directly (used by the labels browser).
  function setCountText(text) {
    const el = document.getElementById("album-count");
    if (!el) return;
    el.textContent = text;
    el.classList.remove("hidden");
  }
  // Topbar context label: the active filter's value (genre/tag name) with NO
  // count; hidden on the plain wall. Counts were removed from all screens.
  function updateCountReadout(filteredTotal) {
    const el = document.getElementById("album-count");
    if (!el) return;
    if (labelsActive) return;   // labels browser manages its own header text
    if (activeFilter) {
      el.textContent = activeFilter.label || activeFilter.value;   // group label (e.g. "Rock/Metal") if set
      el.classList.remove("hidden");
    } else {
      el.textContent = "";
      el.classList.add("hidden");
    }
  }

  async function loadRandom() {
    refreshBtn.disabled = true;
    // Size the wall grid (phone-fit) and take its count in one measurement;
    // off-phone applyWallGridSizing returns null and we use computeAlbumCount.
    const wallCount = applyWallGridSizing();
    albumCount = wallCount != null ? Math.min(96, wallCount) : computeAlbumCount();
    renderSkeletons(albumCount);
    try {
      const r = await fetch(`/api/random-albums?count=${albumCount}${filterQS()}`);
      if (r.status === 503) {
        const j = await r.json().catch(() => ({}));
        setBanner(j.error || "Waiting for LMS. Check the server connection in Settings.", true);
        grid.innerHTML = ""; return;
      }
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        throw new Error(j.error || `HTTP ${r.status}`);
      }
      const j = await r.json();
      renderAlbums(j.albums || []);
      updateCountReadout(j.filtered ? j.total : null);
    } catch (e) {
      setBanner(`Couldn't load albums: ${e.message}`, true);
      grid.innerHTML = "";
    } finally {
      refreshBtn.disabled = false;
    }
  }

  // ----- Zones -----
  async function loadZones() {
    try {
      const r = await fetch("/api/zones");
      const j = await r.json();
      zones = j.zones || [];
      const prev = localStorage.getItem("rra-zone");
      zoneSel.innerHTML = "";
      if (!zones.length) {
        const opt = document.createElement("option");
        opt.textContent = "No zones available"; opt.value = "";
        zoneSel.appendChild(opt);
        selectedZoneId = null;
        return;
      }
      for (const z of zones) {
        const opt = document.createElement("option");
        opt.value = z.zone_id; opt.textContent = z.display_name;
        zoneSel.appendChild(opt);
      }
      selectedZoneId = (prev && zones.some(z => z.zone_id === prev)) ? prev : zones[0].zone_id;
      zoneSel.value = selectedZoneId;
    } catch (e) { /* status banner handles */ }
  }
  // Styled yes/no confirm. Resolves true/false. Falls back to native confirm.
  function confirmDialog(message) {
    return new Promise((resolve) => {
      const ov  = document.getElementById("confirm-overlay");
      const msg = document.getElementById("confirm-msg");
      const yes = document.getElementById("confirm-yes");
      const no  = document.getElementById("confirm-no");
      if (!ov || !msg || !yes || !no) { resolve(window.confirm(message)); return; }
      msg.textContent = message;
      let done = false;
      const close = (val) => {
        if (done) return; done = true;
        ov.classList.add("hidden");
        yes.removeEventListener("click", onYes);
        no.removeEventListener("click", onNo);
        ov.removeEventListener("click", onBackdrop);
        resolve(val);
      };
      const onYes = () => close(true);
      const onNo  = () => close(false);
      const onBackdrop = (e) => { if (e.target.classList.contains("confirm-backdrop")) close(false); };
      yes.addEventListener("click", onYes);
      no.addEventListener("click", onNo);
      ov.addEventListener("click", onBackdrop);
      ov.classList.remove("hidden");
    });
  }

  zoneSel.addEventListener("change", async () => {
    const newZoneId  = zoneSel.value;
    const prevZoneId = selectedZoneId;

    // Switch the active zone right away — this is what play actions and the
    // mini-transport target. Changing zones no longer moves the queue on its
    // own; we ask first (and only when the old zone is actually playing).
    selectedZoneId = newZoneId;
    localStorage.setItem("rra-zone", selectedZoneId);

    if (!prevZoneId || !newZoneId || prevZoneId === newZoneId) return;

    let playing = false;
    try {
      const r = await fetch(`/api/album/now-playing?zone=${encodeURIComponent(prevZoneId)}`, { cache: "no-store" });
      if (r.ok) {
        const j = await r.json();
        playing = !!(j && j.album && j.album.title);
      }
    } catch (e) { /* treat as nothing playing */ }
    if (!playing) return;

    const nameOf = (id, fb) => (zones.find(z => z.zone_id === id) || {}).display_name || fb;
    const move = await confirmDialog(
      `Move what's playing in ${nameOf(prevZoneId, "the other zone")} to ${nameOf(newZoneId, "this zone")}?`
    );
    if (!move) return;

    try {
      const r = await fetch("/api/transfer-zone", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ from_zone: prevZoneId, to_zone: newZoneId })
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        const msg = (j.error || "").toString();
        if (msg && !/no.*(queue|playing|track)/i.test(msg)) console.warn("[zone transfer]", msg);
      }
      loadZones();
    } catch (e) {
      console.warn("[zone transfer] network error", e);
    }
  });

  // ----- Device picker (now-playing screen) -----
  // Replaces the old share button. Lists available zones and switches the
  // active zone by driving the existing topbar selector, so playback, the
  // mini-transport, and the now-playing screen all stay in sync.
  const npDeviceBtn     = document.getElementById("np-device");
  const npDevicePopover = document.getElementById("np-device-popover");
  const npDeviceList    = document.getElementById("np-device-list");

  async function renderDeviceList() {
    if (!npDeviceList) return;
    let list = zones;
    try {
      const r = await fetch("/api/zones", { cache: "no-store" });
      if (r.ok) { const j = await r.json(); if (Array.isArray(j.zones)) { zones = j.zones; list = j.zones; } }
    } catch (e) { /* fall back to cached zones */ }

    npDeviceList.innerHTML = "";
    if (!list.length) {
      const empty = document.createElement("div");
      empty.className = "np-device-empty";
      empty.textContent = "No zones available";
      npDeviceList.appendChild(empty);
      return;
    }
    for (const z of list) {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "np-device-item" + (z.zone_id === selectedZoneId ? " is-current" : "");
      item.dataset.zone = z.zone_id;
      item.textContent = z.display_name;
      item.addEventListener("click", (e) => {
        e.stopPropagation();
        npDevicePopover.classList.add("hidden");
        npDeviceBtn.setAttribute("aria-expanded", "false");
        if (z.zone_id === selectedZoneId) return;
        zoneSel.value = z.zone_id;
        zoneSel.dispatchEvent(new Event("change"));   // reuse the existing switch flow
        if (typeof window.__refreshTransport === "function") window.__refreshTransport();
      });
      npDeviceList.appendChild(item);
    }
  }

  if (npDeviceBtn && npDevicePopover) {
    npDeviceBtn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const vp = document.getElementById("np-vol-popover");
      const vb = document.getElementById("np-volbtn");
      if (vp) vp.classList.add("hidden");
      if (vb) vb.setAttribute("aria-expanded", "false");
      const willShow = npDevicePopover.classList.contains("hidden");
      if (willShow) await renderDeviceList();
      npDevicePopover.classList.toggle("hidden", !willShow);
      npDeviceBtn.setAttribute("aria-expanded", String(willShow));
    });
  }

  // ----- Modal -----
  let currentSource = "random";
  let currentSourceZoneId = null;

  // Ambient glow layer behind the modal header — mirrors the cover image so
  // the blur always matches the art shown. Same URL as #modal-img, so the
  // browser serves it from cache (no second fetch). Pass null to hide.
  const modalAmbient = document.getElementById("modal-ambient");
  function setModalAmbient(url) {
    if (!modalAmbient) return;
    if (url) {
      // The glow is blurred anyway, so feed it a TINY cover (96px) instead of
      // the 800px big art: Safari otherwise keeps a full-size blurred layer
      // composited behind the scrolling modal body. Upscaling the small image
      // does most of the smoothing (the CSS blur radius is tuned to match).
      // Only /api/image URLs carry a size param; anything else passes through.
      modalAmbient.src = url.includes("/api/image/")
        ? url.replace(/([?&])size=\d+/, "$1size=96")
        : url;
      modalAmbient.classList.remove("hidden");
    } else {
      modalAmbient.removeAttribute("src");
      modalAmbient.classList.add("hidden");
    }
  }
  // The transport poll (separate closure) re-points the big art when the
  // playing track changes album; it uses this bridge to keep the Queue tab's
  // ambient glow on the same album.
  window.__setModalAmbient = setModalAmbient;

  // Split on multi-artist separators so each name becomes its own link:
  // " / " (Roon/LMS joiner), "; " and ", " (file-tag forms), " & " and " + "
  // (duo billing — "Panda Bear & Sonic Boom"), feat/featuring/ft. Owner
  // decision (v1.0.5): " & " IS split, band names included — each part's
  // artist page still lists the band's albums. Spaces required around the
  // symbol separators so "AC/DC" stays whole. Mirrors lib/search.js.
  const ARTIST_SPLIT_RE = / \/ |; |, | & | \+ | feat\.? | featuring | ft\.? /i;
  function splitArtistParts(subtitle) {
    return String(subtitle || "").split(ARTIST_SPLIT_RE).map(s => s.trim()).filter(Boolean);
  }

  // A fragment of per-artist link buttons for any artist string. Every name is
  // clickable and opens that artist's page (their own albums first, then the
  // albums they appear on).
// ---------------------------------------------------------------------------
// Whole-house actions, shared by both zone pickers (mini-transport and Now
// Playing). Defined once at top level because those two live in separate
// sibling IIFEs — see the note on esc() about scopes not being shared.
// Delegated from document so it works for whichever popover is open.
// ---------------------------------------------------------------------------
(function initAllZoneActions() {
  let busy = false;
  document.addEventListener("click", async (e) => {
    const btn = e.target.closest && e.target.closest("[data-all]");
    if (!btn) return;
    e.stopPropagation();
    if (busy) return;
    busy = true;
    const what = btn.dataset.all;
    try {
      const path = what === "pause" ? "/api/pause-all" : "/api/mute-all";
      const body = what === "pause" ? {} : { how: what === "mute" ? "mute" : "unmute" };
      const r = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" },
                                    body: JSON.stringify(body) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      window.__showToast(what === "pause"
        ? "Paused " + j.paused + " zone" + (j.paused === 1 ? "" : "s")
        : what === "mute" ? "Muted every zone" : "Unmuted every zone");
    } catch (err) {
      window.__showToast(err.message, "error");
    } finally { busy = false; }
  });
})();

  function artistLinkNodes(subtitle, linkClass) {
    const frag = document.createDocumentFragment();
    splitArtistParts(subtitle).forEach((part, i) => {
      if (i > 0) {
        const sep = document.createElement("span");
        sep.className = "modal-subtitle-year";
        sep.textContent = " / ";
        frag.appendChild(sep);
      }
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = linkClass || "modal-artist-link";
      btn.textContent = part;
      btn.addEventListener("click", (e) => {
        e.stopPropagation();   // track rows have their own tap action
        closeModal();
        if (window.__exitLabels) window.__exitLabels();   // leave the labels browser if active
        window.__showArtistAlbums && window.__showArtistAlbums(part);
      });
      frag.appendChild(btn);
    });
    return frag;
  }
  // Shared across IIFEs (Now Playing uses it too) — separate scopes, so it
  // has to be exposed rather than referenced directly.
  window.__artistLinkNodes = artistLinkNodes;

  function setModalArtist(subtitle) {
    modalSub.innerHTML = "";
    if (!subtitle) return;
    modalSub.appendChild(artistLinkNodes(subtitle));
  }

  // This app's Favourite, in the album modal chrome. Reuses the shared
  // helpers so its identity payload matches the grid/sheet exactly.
  const modalFavBtn = document.getElementById("modal-fav-btn");
  function paintModalFav() {
    if (!modalFavBtn) return;
    const on = !!(currentAlbum && window.__isFavourite && window.__isFavourite(currentAlbum));
    modalFavBtn.classList.toggle("is-fav", on);
    modalFavBtn.title = on ? "Remove from Favourites" : "Add to Favourites";
    // Spelled out, because a bare heart here would be easy to mistake for the
    // Qobuz one sitting in the action row below.
    modalFavBtn.setAttribute("aria-label", modalFavBtn.title + " (kept in this app, separate from Qobuz)");
  }
  if (modalFavBtn) modalFavBtn.addEventListener("click", async () => {
    if (!currentAlbum || !window.__toggleFavourite) return;
    modalFavBtn.disabled = true;
    try {
      const on = await window.__toggleFavourite(currentAlbum);
      paintModalFav();
      if (window.__repaintFavMarks) window.__repaintFavMarks();
      showToast(on ? "Added to Favourites" : "Removed from Favourites");
    } catch (e) { showToast(e.message, "error"); }
    finally { modalFavBtn.disabled = false; }
  });

  function openAlbum(album, opts) {
    opts = opts || {};
    // Opening a different album invalidates any live track selection — the
    // indices only mean anything against the album they were picked in.
    exitTrackSelectMode();
    currentAlbum = album;
    window.__currentAlbum = album;
    // Cleared here and repainted from the server album — otherwise the previous
    // album's badge sits over the new artwork until the fetch lands, and stays
    // forever on one the server has no quality for.
    setModalQuality(album);
    paintModalFav();
    // Keys may not have loaded yet on a cold open — repaint when they do.
    if (window.__refreshFavKeys) window.__refreshFavKeys().then(paintModalFav);
    currentSource = opts.source || "random";
    currentSourceZoneId = opts.zoneId || null;
    // An explicit opts.filter (incl. null) wins over the active filter — Home
    // tiles carry full-library offsets and must resolve unfiltered even if a
    // genre filter is still active.
    currentDetailFilter = ("filter" in opts) ? opts.filter : activeFilter;

    // Qobuz catalogue album (not in the library): reuses this modal's chrome
    // (cover, ambient, TRACKS, action pills) but loads tracks/actions from the
    // Qobuz plugin via its opaque token instead of a library offset.
    const isQobuz = !!(album && album.source === "qobuz" && album.token);

    // Persist so the modal survives a Safari reload after tapping an external
    // link — skip Qobuz albums (their token is short-lived / server-side).
    if (!isQobuz) try {
      sessionStorage.setItem("rra-modal",
        JSON.stringify({ album, source: currentSource, zoneId: currentSourceZoneId,
                         filter: currentDetailFilter }));
    } catch (e) { /* ignore */ }

    const isNP = currentSource === "now-playing";

    // Tabs visible only in now-playing mode
    const tabsEl = document.getElementById("modal-tabs");
    tabsEl.classList.toggle("hidden", !isNP);
    modal.classList.toggle("np-mode", isNP);
    modal.classList.toggle("qobuz-mode", isQobuz);   // hides Edit / bio (library-only)
    const oldNotice = document.querySelector(".qb-modal-notice");   // clear any prior Qobuz notice
    if (oldNotice) oldNotice.remove();
    showTab("album");

    modalTitle.textContent = album.title || "Untitled";
    setModalArtist(album.subtitle);
    modalActs.innerHTML    = isNP ? "" : `<div class="modal-loading">Loading…</div>`;
    modalTracks.innerHTML  = "";

    // Reset bio sections
    document.getElementById("album-bio-section").classList.add("hidden");
    document.getElementById("album-bio-toggle").classList.add("hidden");
    document.getElementById("album-bio-source").classList.add("hidden");
    document.getElementById("album-bio-text").dataset.clipped = "true";
    if (album.image_key) {
      modalImg.src = `/api/image/${encodeURIComponent(album.image_key)}?size=800`;
      modalImg.style.display = "";
      setModalAmbient(modalImg.src);
    } else {
      modalImg.removeAttribute("src");
      modalImg.style.display = "none";
      setModalAmbient(null);
    }
    modal.classList.remove("hidden");
    document.body.style.overflow = "hidden";

    if (isNP) {
      // The now-playing screen is driven live by the transport poll loop;
      // refresh it immediately from the latest zone state.
      if (typeof window.__refreshTransport === "function") window.__refreshTransport();
    } else if (isQobuz) {
      fetchQobuzAlbumDetail(album).catch(err => {
        modalActs.innerHTML = `<div class="modal-error">${esc(err.message)}</div>`;
      });
    } else {
      fetchAlbumDetail(album).catch(err => {
        modalActs.innerHTML = `<div class="modal-error">${esc(err.message)}</div>`;
      });
      fetchAlbumExtras(album).catch(() => { /* extras are non-critical — modal still opens */ });
    }
  }
  window.__openAlbum = openAlbum;

  // Populate the shared album modal for a Qobuz catalogue album. Uses the same
  // .action-btn pills and .t-row track rows as the library detail, so it inherits
  // the full modal styling (centred cover, ambient wash, TRACKS section).
  async function qobuzModalPlay(token, kind, btn) {
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    const prev = btn.style.opacity; btn.style.opacity = ".6"; btn.disabled = true;
    try {
      const r = await fetch("/api/qobuz/play", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token, zone_or_output_id: selectedZoneId, kind }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      showToast(`${kind === "queue" ? "Queued" : "Playing"} → ${zoneName(selectedZoneId)}`);
    } catch (e) { showToast(e.message, "error"); }
    finally { btn.style.opacity = prev; btn.disabled = false; }
  }

  async function fetchQobuzAlbumDetail(album) {
    modalActs.innerHTML = "";
    const play = document.createElement("button"); play.className = "action-btn primary"; play.type = "button"; play.textContent = "Play Now";
    play.addEventListener("click", () => qobuzModalPlay(album.token, "play_now", play));
    modalActs.appendChild(play);
    if (album.can_queue !== false) {
      const q = document.createElement("button"); q.className = "action-btn"; q.type = "button"; q.textContent = "Queue";
      q.addEventListener("click", () => qobuzModalPlay(album.token, "queue", q));
      modalActs.appendChild(q);
    }
    let favBtn = null;
    if (album.can_favorite) {
      favBtn = document.createElement("button"); favBtn.className = "action-btn qobuz-fav"; favBtn.type = "button";
      const paint = (on) => { favBtn.classList.toggle("is-fav", !!on); favBtn.textContent = on ? "♥ Favourited" : "♡ Favourite"; };
      paint(false);
      favBtn.addEventListener("click", async () => {
        const want = !favBtn.classList.contains("is-fav");
        favBtn.disabled = true;
        try {
          const r = await fetch("/api/qobuz/favorite", { method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ token: album.token, favorite: want }) });
          const j = await r.json().catch(() => ({}));
          if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
          paint(j.favorite); showToast(j.favorite ? "Added to Qobuz favourites" : "Removed from Qobuz favourites");
        } catch (e) { showToast(e.message, "error"); }
        finally { favBtn.disabled = false; }
      });
      modalActs.appendChild(favBtn);
    }

    const trackWrap = document.querySelector(".track-list-wrap");
    modalTracks.innerHTML = "";
    trackWrap.classList.remove("hidden");
    const r = await fetch("/api/qobuz/album?token=" + encodeURIComponent(album.token), { cache: "no-store" });
    const j = await r.json();
    if (album !== currentAlbum) return;                 // navigated away while loading
    if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
    if (favBtn && j.favorite != null) { favBtn.classList.toggle("is-fav", !!j.favorite); favBtn.textContent = j.favorite ? "♥ Favourited" : "♡ Favourite"; }
    const tracks = j.tracks || [];
    if (j.notice) {   // re-auth / error prompt from the plugin — clean message, not a "track"
      trackWrap.classList.add("hidden");
      const d = document.createElement("div"); d.className = "qb-modal-notice"; d.textContent = j.notice;
      document.getElementById("tab-album").appendChild(d);
      return;
    }
    if (!tracks.length) { trackWrap.classList.add("hidden"); return; }
    tracks.forEach((t) => {
      const li = document.createElement("li"); li.className = "t-row";
      const tx = document.createElement("div"); tx.className = "t-text";
      const ti = document.createElement("span"); ti.className = "t-title"; ti.textContent = t.title || "";
      tx.appendChild(ti);
      if (t.artist) { const su = document.createElement("span"); su.className = "t-sub"; su.textContent = t.artist; tx.appendChild(su); }
      li.appendChild(tx);
      li.addEventListener("click", () => qobuzModalPlay(t.token, "play_now", li));
      modalTracks.appendChild(li);
    });
  }

  function showTab(name) {
    document.querySelectorAll(".modal-tab").forEach(b => {
      b.classList.toggle("is-active", b.dataset.tab === name);
    });
    document.getElementById("tab-album").classList.toggle("hidden", name !== "album");
    document.getElementById("tab-queue").classList.toggle("hidden", name !== "queue");

    // Track the active tab on the modal so the transport bar / now-playing
    // screen can react: bar hidden on the Now playing tab, shown on Queue.
    modal.classList.toggle("tab-album", name === "album");
    modal.classList.toggle("tab-queue", name === "queue");

    // The Roon-style now-playing block only shows on the Now playing tab while
    // in now-playing mode.
    const npScreen = document.getElementById("np-screen");
    if (npScreen) {
      npScreen.classList.toggle("hidden",
        !(name === "album" && modal.classList.contains("np-mode")));
    }

    if (name === "queue") loadQueue();
    if (typeof window.__refreshTransport === "function") window.__refreshTransport();
  }
  document.querySelectorAll(".modal-tab").forEach(b => {
    b.addEventListener("click", () => showTab(b.dataset.tab));
  });

  async function fetchNowPlayingDetail(zoneId) {
    const r = await fetch(`/api/album/now-playing?zone=${encodeURIComponent(zoneId)}`);
    if (!r.ok) {
      const j = await r.json().catch(() => ({}));
      throw new Error(j.error || `HTTP ${r.status}`);
    }
    const j = await r.json();
    if (j.album) {
      if (j.album.title)    modalTitle.textContent = j.album.title;
      if (j.album.subtitle) setModalArtist(j.album.subtitle);
      if (j.album.image_key) {
        modalImg.src = `/api/image/${encodeURIComponent(j.album.image_key)}?size=800`;
        setModalAmbient(modalImg.src);
      }
    }
    const wrap = document.querySelector(".track-list-wrap");
    if ((j.tracks || []).length) {
      wrap.classList.remove("hidden");
      modalTracks.innerHTML = "";
      for (const t of j.tracks) {
        const li = document.createElement("li");
        const tx = document.createElement("div"); tx.className = "t-text";
        const ti = document.createElement("span"); ti.className = "t-title";
        ti.textContent = t.title || "";
        const su = document.createElement("span"); su.className = "t-sub";
        // Every credited artist is its own tappable link to their artist page.
        su.appendChild(artistLinkNodes(t.subtitle, "t-artist-link"));
        tx.appendChild(ti); tx.appendChild(su);
        li.appendChild(tx);
        modalTracks.appendChild(li);
      }
    } else {
      wrap.classList.add("hidden");
    }
  }

  async function loadQueue() {
    if (!currentSourceZoneId) return;
    const summary = document.getElementById("queue-summary");
    const list    = document.getElementById("queue-list");
    const empty   = document.getElementById("queue-empty");
    summary.textContent = "Loading queue…";
    list.innerHTML = "";
    empty.classList.add("hidden");
    try {
      const r = await fetch(`/api/queue?zone=${encodeURIComponent(currentSourceZoneId)}`);
      const j = await r.json();
      const items = j.items || [];
      if (!items.length) {
        summary.textContent = "";
        empty.classList.remove("hidden");
        return;
      }
      // The server returns only the current + upcoming tracks (played ones
      // are dropped), so the totals here reflect just the remaining queue.
      let totalSec = 0;
      const quals = new Set();
      for (const it of items) {
        if (it.length) totalSec += it.length;
        const q = trackQualityLabel(it);
        if (q) quals.add(q);
      }
      const qualText = quals.size === 1 ? [...quals][0] : (quals.size > 1 ? "Mixed quality" : "");
      summary.textContent =
        `${items.length} track${items.length === 1 ? "" : "s"} · ${fmtDuration(totalSec)} remaining` +
        (qualText ? ` · ${qualText}` : "");

      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        if (i === 0) {
          // Roon-style "Now playing" divider above the current track
          const div = document.createElement("li");
          div.className = "q-divider";
          div.setAttribute("aria-hidden", "true");
          div.innerHTML =
            '<span class="q-divider-line"></span>' +
            '<span class="q-divider-label">Now playing</span>' +
            '<span class="q-divider-line"></span>';
          list.appendChild(div);
        }
        const li = document.createElement("li");
        if (i === 0) li.classList.add("is-now");
        else li.classList.add("is-tappable");

        const art = document.createElement("img"); art.className = "q-art";
        if (it.image_key) art.src = `/api/image/${encodeURIComponent(it.image_key)}?size=120`;
        else art.style.visibility = "hidden";
        const tx = document.createElement("div"); tx.className = "q-text";
        const tt = document.createElement("div"); tt.className = "q-title";  tt.textContent = it.title || "";
        const ts = document.createElement("div"); ts.className = "q-sub";    ts.textContent = it.subtitle || "";
        tx.appendChild(tt); tx.appendChild(ts);
        const len = document.createElement("span"); len.className = "q-len";
        if (it.length) len.textContent = fmtDuration(it.length);
        li.appendChild(art); li.appendChild(tx); li.appendChild(len);

        if (i !== 0) {
          const rm = document.createElement("button");
          rm.className = "q-remove";
          rm.type = "button";
          rm.setAttribute("aria-label", "Remove from queue");
          rm.textContent = "✕";
          rm.addEventListener("click", async (ev) => {
            ev.stopPropagation();
            try {
              const r = await fetch("/api/queue/remove", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                  zone_or_output_id: currentSourceZoneId,
                  queue_item_id: it.queue_item_id
                })
              });
              if (!r.ok) {
                const j = await r.json().catch(() => ({}));
                window.alert("Couldn't remove: " + (j.error || `HTTP ${r.status}`));
                return;
              }
              loadQueue();
            } catch (e) {
              window.alert("Couldn't remove: " + e.message);
            }
          });
          li.appendChild(rm);

          li.addEventListener("click", async () => {
            const trackName = it.title || "this track";
            if (!window.confirm(`Play from "${trackName}"?`)) return;
            try {
              const r = await fetch("/api/play-from-here", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                  zone_or_output_id: currentSourceZoneId,
                  queue_item_id: it.queue_item_id
                })
              });
              if (!r.ok) {
                const j = await r.json().catch(() => ({}));
                window.alert("Couldn't play from here: " + (j.error || `HTTP ${r.status}`));
                return;
              }
              // Give Roon a moment, then re-pull the queue so the "now playing"
              // marker moves and earlier-played tracks fall away.
              setTimeout(loadQueue, 600);
            } catch (e) {
              window.alert("Couldn't play from here: " + e.message);
            }
          });
        }

        list.appendChild(li);
      }
    } catch (e) {
      summary.textContent = "Couldn't load queue: " + e.message;
    }
  }
  function fmtDuration(secs) {
    secs = Math.max(0, Math.floor(secs || 0));
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    if (h) return `${h}:${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}`;
    return `${m}:${String(s).padStart(2,"0")}`;
  }

  // Human quality label for a queue item: "FLAC 16/44.1", "MP3 320kbps", …
  const TYPE_LABELS = { flc: "FLAC", flac: "FLAC", alc: "ALAC", alac: "ALAC",
                        aif: "AIFF", mp3: "MP3", mp4: "AAC", aac: "AAC",
                        ogg: "OGG", ops: "Opus", wma: "WMA", dsf: "DSD", dff: "DSD", wav: "WAV" };
  function trackQualityLabel(it) {
    const type = it.type ? (TYPE_LABELS[String(it.type).toLowerCase()] || String(it.type).toUpperCase()) : "";
    if (it.samplesize && it.samplerate) {
      const khz = (it.samplerate / 1000).toFixed(it.samplerate % 1000 ? 1 : 0);
      return (type ? type + " " : "") + `${it.samplesize}/${khz}`;
    }
    if (it.bitrate) return (type ? type + " " : "") + String(it.bitrate).replace(/\s+/g, "");
    return type || "";
  }

  // The album view's artwork is far bigger than a tile, so the badge is drawn
  // larger there (#modal-quality in the CSS) — but it is the same element and
  // the same class, so the Appearance toggle governs both.
  function setModalQuality(album) {
    const mq = document.getElementById("modal-quality");
    if (!mq) return;
    const q = album && album.quality;
    mq.className = "album-quality" + (q ? (album.hires ? " is-hires" : "") : " hidden");
    mq.textContent = q || "";
    if (q) {
      const words = /\//.test(q) ? q.split("/")[0] + "-bit, " + q.split("/")[1] + " kHz" : q;
      mq.title = words;
      mq.setAttribute("aria-label", words);
    }
  }

  function closeModal() {
    modal.classList.add("hidden");
    modal.classList.remove("np-mode", "tab-album", "tab-queue");
    document.body.style.overflow = "";
    // Track selection only means anything for the album that's open — never
    // let its bar (or a set of indices) survive the modal it belongs to.
    exitTrackSelectMode();
    currentAlbum = null;
    window.__currentAlbum = null;
    try { sessionStorage.removeItem("rra-modal"); } catch (e) {} // sessionStorage optional
    if (typeof window.__refreshTransport === "function") window.__refreshTransport();
  }
  modal.addEventListener("click", (e) => {
    if (e.target.closest && e.target.closest("[data-close]")) closeModal();
  });
  // np-mode's top-left Home button (the × is hidden there): close the modal
  // and land on the Home screen, leaving any labels/artist view behind.
  const modalHomeBtn = document.getElementById("modal-home-btn");
  if (modalHomeBtn) modalHomeBtn.addEventListener("click", () => {
    closeModal();
    showHome();   // showHome resets labels/artist/search state itself
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !modal.classList.contains("hidden")) closeModal();
  });

  async function fetchAlbumDetail(album) {
    const r = await fetch(`/api/album?offset=${album.offset}${filterQSOf(currentDetailFilter)}`);
    if (!r.ok) {
      const j = await r.json().catch(() => ({}));
      throw new Error(j.error || `HTTP ${r.status}`);
    }
    const j = await r.json();

    // Modal may have been closed/reopened on a different album while we
    // waited — bail rather than render album A's rows (whose tap handlers
    // would fire against album B's offset). Same guard as fetchAlbumExtras.
    if (album !== currentAlbum) return;

    // Only accept server title if it matches what we expected — guards against
    // stale index offsets returning a completely different album after a library change.
    if (j.album && j.album.title) {
      const expectedNorm = currentAlbum ? (currentAlbum.title || "").toLowerCase().trim() : "";
      const returnedNorm = (j.album.title || "").toLowerCase().trim();
      if (!expectedNorm || returnedNorm === expectedNorm) {
        modalTitle.textContent = j.album.title;
        // Subtitle already set as a clickable button by openAlbum(); don't overwrite.
      }
    }

    // Quality badge on the album's own artwork. Painted from the SERVER album,
    // not the tile that was tapped — a tile from a search result or a Qobuz row
    // carries no quality at all.
    setModalQuality(j.album);

    // Build action buttons in preferred order
    const order  = ["play_now", "queue", "play_next", "shuffle", "radio"];
    const labels = {
      play_now:  "Play Now",
      queue:     "Queue",
      play_next: "Next",
      shuffle:   "Shuffle",
      radio:     "Radio"
    };
    const map = new Map();
    for (const a of (j.actions || [])) {
      if (!map.has(a.kind)) map.set(a.kind, a);
    }

    modalActs.innerHTML = "";
    let first = true;
    for (const k of order) {
      if (!map.has(k)) continue;
      const btn = document.createElement("button");
      btn.className = "action-btn" + (first ? " primary" : "");
      btn.type = "button";
      btn.textContent = labels[k];
      btn.addEventListener("click", () => invoke(k, btn));
      modalActs.appendChild(btn);
      first = false;
    }
    if (!modalActs.children.length) {
      modalActs.innerHTML =
        `<div class="modal-error">No playback actions available for this album.</div>`;
    }

    // Tracks — each row is tappable and reveals Play now / Queue for that
    // track (one open row at a time; tapping again collapses it).
    const trackWrap = document.querySelector(".track-list-wrap");
    modalTracks.innerHTML = "";
    const trackList = j.tracks || [];
    if (trackList.length === 0) {
      trackWrap.classList.add("hidden");
    } else {
      trackWrap.classList.remove("hidden");
      trackList.forEach((t, idx) => {
        const li = document.createElement("li");
        li.className = "t-row";
        // Title stacked over the full artist credit (Qobuz/Roon style) so
        // every performer on multi-artist / various-artists tracks shows in
        // full and wraps instead of being clipped.
        const tx = document.createElement("div"); tx.className = "t-text";
        const ti = document.createElement("span"); ti.className = "t-title";
        ti.textContent = t.title || "";
        const su = document.createElement("span"); su.className = "t-sub";
        // Every credited artist is its own tappable link to their artist page
        // (stopPropagation inside keeps the row's play/queue toggle intact).
        su.appendChild(artistLinkNodes(t.subtitle, "t-artist-link"));
        tx.appendChild(ti); tx.appendChild(su);
        li.appendChild(tx);
        li.dataset.trackIdx = String(idx);
        // Selection has its OWN target at the right of the row, rather than the
        // whole row being tappable: a track line is full of artist links, and a
        // row-wide hit area meant every attempt to follow one risked toggling
        // the track instead. Only this control (and its padding) selects.
        const check = document.createElement("button");
        check.type = "button";
        check.className = "t-check";
        check.setAttribute("aria-label", "Select track");
        check.setAttribute("aria-pressed", "false");
        check.innerHTML = TICK_SVG;
        check.addEventListener("click", (e) => {
          e.stopPropagation();          // never let it reach the row handler
          if (!trackSelectMode) enterTrackSelectMode();
          handleTrackSelect(li, idx);
        });
        li.appendChild(check);

        li.addEventListener("click", (e) => {
          if (e.target.closest(".t-actions")) return;   // taps on the buttons themselves
          if (e.target.closest(".t-artist-link")) return;   // artist links keep working
          if (e.target.closest(".t-check")) return;     // its own handler owns this
          // While selecting, the row body does nothing — the checkbox is the
          // only way to pick a track, so tapping a title can't select by
          // accident and artist links stay usable.
          if (trackSelectMode) return;
          toggleTrackActions(li, t, idx);
        });
        // Long-press enters track select mode. Only library rows get this —
        // fetchQobuzAlbumDetail() builds its own rows and is left alone, so
        // catalogue tracks (which have no album offset) can't be selected.
        addLongPress(li, () => {
          if (!trackSelectMode) enterTrackSelectMode();
          handleTrackSelect(li, idx);
        });
        modalTracks.appendChild(li);
      });
    }
  }

  // ----- Track multi-select (album modal) -----
  // Selection is (album offset, track index) because the client is never given
  // LMS track ids — see /api/play-tracks, which re-resolves indices defensively.
  let trackSelectMode = false;
  let trackSelected = [];          // array of track indices, in tap order
  let trackSelectOffset = null;    // the album the selection belongs to

  function enterTrackSelectMode() {
    trackSelectMode = true;
    trackSelectOffset = currentAlbum ? currentAlbum.offset : null;
    // A row can't be both "expanded with Play/Queue buttons" and "picked" —
    // collapse whatever was open before switching interaction models.
    const open = modalTracks.querySelector("li.is-open");
    if (open) closeTrackRow(open);
    modalTracks.classList.add("is-selecting");
    if (trackSelectRow) trackSelectRow.classList.remove("hidden");
    updateTrackActionBar();
  }
  function exitTrackSelectMode() {
    trackSelectMode = false;
    trackSelected = [];
    trackSelectOffset = null;
    modalTracks.classList.remove("is-selecting");
    modalTracks.querySelectorAll("li.t-row.is-picked").forEach(li => li.classList.remove("is-picked"));
    modalTracks.querySelectorAll(".t-check").forEach(b => {
      b.setAttribute("aria-pressed", "false");
      b.setAttribute("aria-label", "Select track");
    });
    if (trackSelectRow) trackSelectRow.classList.add("hidden");
    // The menu is shared with album selection and rendered into <body>, so it
    // would outlive the row that opened it.
    if (trackOptionsBtn && albumOptionsOwner === trackOptionsBtn) closeAlbumOptionsMenu();
  }
  function updateTrackActionBar() {
    const n = trackSelected.length;
    if (trackSelectInfo) trackSelectInfo.textContent = n === 0
      ? "Tap tracks to select" : n + " track" + (n === 1 ? "" : "s") + " selected";
    if (trackOptionsBtn) trackOptionsBtn.disabled = n === 0;
    // Rebuild in place so a menu left open while the selection changes doesn't
    // keep offering actions for a count that no longer holds.
    if (albumOptionsMenu && albumOptionsOwner === trackOptionsBtn) buildTrackOptionsMenu(albumOptionsMenu);
  }

  // Track actions, in the same Options dropdown albums use (v1.0.58). The old
  // fixed bottom bar is gone: three text buttons plus a cancel could not wrap,
  // and a vertical list cannot overflow.
  function buildTrackOptionsMenu(menu) {
    menu.innerHTML = "";
    const n = trackSelected.length;
    const row = optionsRowFactory(menu);
    row("Play now", null, n === 0, () => invokeTrackMulti("play_now"));
    row("Add to end of queue", null, n === 0, () => invokeTrackMulti("queue"));
    row("Add to playlist", null, n === 0, trackActPlaylist);
    row("Clear selection", null, false, () => exitTrackSelectMode());
  }
  function handleTrackSelect(li, idx) {
    const at = trackSelected.indexOf(idx);
    if (at === -1) { trackSelected.push(idx); li.classList.add("is-picked"); }
    else           { trackSelected.splice(at, 1); li.classList.remove("is-picked"); }
    const box = li.querySelector(".t-check");
    if (box) {
      const on = li.classList.contains("is-picked");
      box.setAttribute("aria-pressed", on ? "true" : "false");
      box.setAttribute("aria-label", on ? "Deselect track" : "Select track");
    }
    updateTrackActionBar();
  }

  async function invokeTrackMulti(kind) {
    if (!trackSelected.length) return;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    if (trackSelectSufficient() === false) { showToast("Album changed — reopen it and try again", "error"); return; }
    if (trackOptionsBtn) trackOptionsBtn.disabled = true;
    const n = trackSelected.length;
    try {
      const r = await fetch("/api/play-tracks", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ offset: trackSelectOffset, tracks: trackSelected.slice(),
          zone_or_output_id: selectedZoneId, kind })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      const verb = kind === "play_now" ? "Playing" : "Queued";
      const played = typeof j.played === "number" ? j.played : n;
      showToast(verb + " " + played + " track" + (played === 1 ? "" : "s") +
        (j.missing ? " (" + j.missing + " no longer in the album)" : "") + " → " + zoneName(selectedZoneId));
      exitTrackSelectMode();
    } catch (e) {
      showToast(e.message, "error");
      updateTrackActionBar();
    }
  }
  // Guard against the modal having moved on to a different album underneath a
  // live selection (shouldn't happen — closing resets — but the offsets must
  // agree before we send indices that are only meaningful for one album).
  function trackSelectSufficient() {
    if (trackSelectOffset == null) return false;
    return !currentAlbum || currentAlbum.offset === trackSelectOffset;
  }

  function trackActPlaylist() {
    if (!trackSelected.length || !window.__addToPlaylistSheet) return;
    const n = trackSelected.length;
    window.__afterPlaylistAdd = exitTrackSelectMode;
    window.__addToPlaylistSheet(
      { offset: trackSelectOffset, tracks: trackSelected.slice() },
      n + " selected track" + (n === 1 ? "" : "s") + ".");
  }
  if (trackOptionsBtn) {
    trackOptionsBtn.addEventListener("click", () =>
      openOptionsMenu(trackOptionsBtn, buildTrackOptionsMenu, "Track actions"));
  }
  if (trackSelectCancel) trackSelectCancel.addEventListener("click", exitTrackSelectMode);

  // Expand/collapse the per-track action row. Only one row is open at a time.
  function closeTrackRow(li) {
    li.classList.remove("is-open");
    const row = li.querySelector(".t-actions");
    if (row) row.remove();
  }
  function toggleTrackActions(li, track, index) {
    const wasOpen = li.classList.contains("is-open");
    const open = modalTracks.querySelector("li.is-open");
    if (open) closeTrackRow(open);
    if (wasOpen) return;

    li.classList.add("is-open");
    const row = document.createElement("div");
    row.className = "t-actions";
    const mk = (label, kind, primary) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "action-btn t-act" + (primary ? " primary" : "");
      b.textContent = label;
      b.addEventListener("click", () => invokeTrack(kind, b, track, index, li));
      return b;
    };
    row.appendChild(mk("Play now", "play_now", true));
    row.appendChild(mk("Queue", "queue", false));
    li.appendChild(row);
  }

  // Mirrors invoke() for a single track (same zone + filter handling).
  async function invokeTrack(kind, btn, track, index, li) {
    if (!currentAlbum) return;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    const orig = btn.textContent;
    btn.disabled = true; btn.textContent = "…";
    try {
      const r = await fetch("/api/play-track", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          offset: currentAlbum.offset,
          track:  index,
          title:  track.title || "",
          zone_or_output_id: selectedZoneId,
          kind,
          filter_type:   currentDetailFilter ? currentDetailFilter.type   : "",
          filter_value:  currentDetailFilter ? currentDetailFilter.value  : "",
          filter_parent: currentDetailFilter && currentDetailFilter.parent ? currentDetailFilter.parent : ""
        })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      showToast(`${j.action || orig}: ${track.title} → ${zoneName(selectedZoneId)}`);
      // Success — collapse the action row; the user stays on the album.
      closeTrackRow(li);
    } catch (e) {
      showToast(e.message, "error");
      btn.disabled = false; btn.textContent = orig;
    }
  }

  async function fetchAlbumExtras(album) {
    if (!album) return;
    const params = new URLSearchParams({
      title:  album.title    || "",
      artist: album.subtitle || ""
    });
    const r = await fetch(`/api/album/extras?${params}`);
    if (!r.ok) return;
    const j = await r.json();
    // Modal may have been closed/reopened while we waited; bail if so.
    if (album !== currentAlbum) return;
    renderExtras(j, album);
  }

  function renderExtras(extras, album) {
    // 1. Append year + label to subtitle line (artist button already present)
    const yearToShow = extras.year || (extras.album && extras.album.year ? String(extras.album.year) : "");
    if (yearToShow) {
      const yearSpan = document.createElement("span");
      yearSpan.className = "modal-subtitle-year";
      yearSpan.textContent = " · " + yearToShow;
      modalSub.appendChild(yearSpan);
    }
    if (extras.album && extras.album.label) {
      const sep = document.createElement("span");
      sep.className = "modal-subtitle-year";
      sep.textContent = " · ";
      modalSub.appendChild(sep);
      const labelBtn = document.createElement("button");
      labelBtn.className = "modal-artist-link";
      labelBtn.textContent = extras.album.label;
      labelBtn.addEventListener("click", () => {
        closeModal();
        if (window.__showLabelAlbums) window.__showLabelAlbums(extras.album.label);
      });
      modalSub.appendChild(labelBtn);
    }
    if (extras.album && typeof extras.album.score === "number" && !isNaN(extras.album.score)) {
      const sep = document.createElement("span");
      sep.className = "modal-subtitle-year";
      sep.textContent = " · ";
      modalSub.appendChild(sep);
      const chip = document.createElement("span");
      chip.className = "pitchfork-score";
      chip.textContent = extras.album.score % 1 === 0
        ? extras.album.score + ".0"
        : String(extras.album.score);
      modalSub.appendChild(chip);
      if (extras.album.isBestNewMusic) {
        const bnm = document.createElement("span");
        bnm.className = "bnm-badge";
        bnm.textContent = "BNM";
        modalSub.appendChild(bnm);
      }
    }

    // 2. Album bio section (description + source link; year/label now in subtitle)
    if (extras.album && (extras.album.description || (extras.album.url && extras.album.source))) {
      const section = document.getElementById("album-bio-section");
      const meta    = document.getElementById("album-meta");
      const text    = document.getElementById("album-bio-text");
      const toggle  = document.getElementById("album-bio-toggle");
      const srcLink = document.getElementById("album-bio-source");

      meta.style.display = "none";

      text.textContent = extras.album.description || "";
      text.style.display = extras.album.description ? "" : "none";

      // Attribution for the review TEXT (LMS Music & Artist Information
      // plugin or Qobuz) — separate from the Pitchfork link below, which is
      // always link-only.
      let attrib = document.getElementById("album-bio-attrib");
      if (!attrib) {
        attrib = document.createElement("div");
        attrib.id = "album-bio-attrib";
        attrib.className = "album-bio-attrib";
        text.insertAdjacentElement("afterend", attrib);
      }
      const showAttrib = !!(extras.album.description && extras.album.descriptionSource);
      attrib.textContent = showAttrib ? "Review: " + extras.album.descriptionSource : "";
      attrib.style.display = showAttrib ? "" : "none";

      if (extras.album.url && extras.album.source) {
        srcLink.href = extras.album.url;
        // Pitchfork review text is never shown (UK-law compliance) — the
        // link is the way to read it, so say so explicitly.
        srcLink.textContent = extras.album.source === "Pitchfork"
          ? "Read the full review on Pitchfork"
          : "View on " + extras.album.source;
        srcLink.classList.remove("hidden");
      } else {
        srcLink.classList.add("hidden");
      }

      section.classList.remove("hidden");
      if (extras.album.description) setupBioToggle(text, toggle);
      else toggle.classList.add("hidden");
    }

    // (Artist bio section removed — the album bio is enough, and the
    // artist Wikipedia lookup was prone to returning wrong articles for
    // less-famous artists.)
  }

  function setupBioToggle(textEl, toggleEl) {
    requestAnimationFrame(() => {
      textEl.dataset.clipped = "true";
      if (textEl.scrollHeight > textEl.clientHeight + 4) {
        toggleEl.classList.remove("hidden");
        toggleEl.textContent = "Show more";
        toggleEl.onclick = () => {
          const isClipped = textEl.dataset.clipped === "true";
          textEl.dataset.clipped = isClipped ? "false" : "true";
          toggleEl.textContent  = isClipped ? "Show less" : "Show more";
        };
      } else {
        toggleEl.classList.add("hidden");
      }
    });
  }

  async function invoke(kind, btn) {
    if (!currentAlbum) return;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    const orig = btn.textContent;
    btn.disabled = true; btn.textContent = "…";
    try {
      const r = await fetch("/api/play", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          offset: currentAlbum.offset,
          zone_or_output_id: selectedZoneId,
          kind,
          filter_type:   currentDetailFilter ? currentDetailFilter.type   : "",
          filter_value:  currentDetailFilter ? currentDetailFilter.value  : "",
          filter_parent: currentDetailFilter && currentDetailFilter.parent ? currentDetailFilter.parent : ""
        })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      showToast(`${j.action || orig} → ${zoneName(selectedZoneId)}`);
      // Keep the album view open after playing so the user stays on the album.
    } catch (e) {
      showToast(e.message, "error");
    } finally {
      btn.disabled = false; btn.textContent = orig;
    }
  }

  function zoneName(id) {
    const z = zones.find(z => z.zone_id === id);
    return z ? z.display_name : "zone";
  }

  // ----- Album editor: owner metadata/artwork overrides ---------------------
  // Edits live in the app's own database (the music mount is read-only) and
  // are applied on top of what LMS reports. Artwork candidates come from the
  // server's external lookup (/api/albumart/candidates).
  (function initAlbumEdit() {
    const sheet   = document.getElementById("album-edit-sheet");
    const editBtn = document.getElementById("modal-edit-btn");
    if (!sheet || !editBtn) return;
    const fTitle   = document.getElementById("ae-title");
    const fArtist  = document.getElementById("ae-artist");
    const fYear    = document.getElementById("ae-year");
    const fUrl     = document.getElementById("ae-art-url");
    const artGrid  = document.getElementById("ae-art-grid");
    const statusEl = document.getElementById("ae-art-status");
    const findBtn  = document.getElementById("ae-find-art");
    const saveBtn  = document.getElementById("ae-save");
    const resetBtn = document.getElementById("ae-reset");

    let selectedArtUrl = null;

    function setStatus(msg) {
      statusEl.textContent = msg || "";
      statusEl.classList.toggle("hidden", !msg);
    }

    function openSheet() {
      if (!currentAlbum || currentAlbum.offset == null) return;
      fTitle.value  = currentAlbum.title || "";
      fArtist.value = currentAlbum.subtitle || "";
      fYear.value   = currentAlbum.year != null ? currentAlbum.year : "";
      fUrl.value    = "";
      artGrid.innerHTML = "";
      selectedArtUrl = null;
      setStatus("");
      resetBtn.classList.toggle("hidden", !currentAlbum.edited);
      saveBtn.disabled = false;
      sheet.classList.remove("hidden");
    }
    function closeSheet() { sheet.classList.add("hidden"); }
    editBtn.addEventListener("click", openSheet);
    document.getElementById("ae-close").addEventListener("click", closeSheet);
    document.getElementById("ae-cancel").addEventListener("click", closeSheet);

    findBtn.addEventListener("click", async () => {
      if (!currentAlbum) return;
      findBtn.disabled = true;
      setStatus("Searching cover sources…");
      artGrid.innerHTML = "";
      selectedArtUrl = null;
      try {
        const r = await fetch(`/api/albumart/candidates?offset=${encodeURIComponent(currentAlbum.offset)}` +
          `&title=${encodeURIComponent(fTitle.value || currentAlbum.title || "")}` +
          `&artist=${encodeURIComponent(fArtist.value || currentAlbum.subtitle || "")}`);
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
        const cands = j.candidates || [];
        if (!cands.length) { setStatus("No artwork found — try adjusting title/artist, or paste a URL below."); return; }
        setStatus("Tap a cover to select it, then Save.");
        for (const c of cands) {
          const b = document.createElement("button");
          b.type = "button";
          b.className = "ae-art-candidate";
          const img = document.createElement("img");
          img.loading = "lazy";
          img.alt = "";
          // Remote candidates render through the server-side thumb proxy so a
          // CORS-less/hotlink-blocked source still previews.
          img.src = `/api/albumart/thumb?url=${encodeURIComponent(c.url)}`;
          img.onerror = () => b.remove();
          const src = document.createElement("span");
          src.className = "ae-art-src";
          src.textContent = c.source || "";
          b.appendChild(img); b.appendChild(src);
          b.addEventListener("click", () => {
            selectedArtUrl = c.url;
            fUrl.value = "";
            artGrid.querySelectorAll(".ae-art-candidate").forEach(el => el.classList.toggle("selected", el === b));
          });
          artGrid.appendChild(b);
        }
      } catch (e) {
        setStatus("Artwork search failed: " + e.message);
      } finally {
        findBtn.disabled = false;
      }
    });
    // Typing a manual URL supersedes any tapped candidate.
    fUrl.addEventListener("input", () => {
      if (fUrl.value.trim()) {
        selectedArtUrl = null;
        artGrid.querySelectorAll(".ae-art-candidate.selected").forEach(el => el.classList.remove("selected"));
      }
    });

    // Push the saved record back into the open modal (and the cached album the
    // tiles handed us) so the edit is visible immediately.
    function applySaved(album) {
      if (!album) return;
      Object.assign(currentAlbum, album);
      window.__currentAlbum = currentAlbum;
      modalTitle.textContent = currentAlbum.title || "Untitled";
      setModalArtist(currentAlbum.subtitle);
      if (currentAlbum.image_key) {
        modalImg.src = `/api/image/${encodeURIComponent(currentAlbum.image_key)}?size=800`;
        modalImg.style.display = "";
        setModalAmbient(modalImg.src);
      } else {
        modalImg.removeAttribute("src");
        modalImg.style.display = "none";
        setModalAmbient(null);
      }
      // Any tile currently on screen for this album gets the fresh data too.
      document.querySelectorAll(".album[data-offset]").forEach(tile => {
        if (tile.dataset.offset !== String(currentAlbum.offset)) return;
        const t = tile.querySelector(".album-title");    if (t) t.textContent = currentAlbum.title || "";
        const a = tile.querySelector(".album-artist");   if (a) a.textContent = currentAlbum.subtitle || "";
        const wrap = tile.querySelector(".album-art-wrap");
        if (wrap && currentAlbum.image_key) {
          let im = wrap.querySelector("img");
          if (!im) { im = document.createElement("img"); im.alt = ""; wrap.prepend(im); }
          im.src = `/api/image/${encodeURIComponent(currentAlbum.image_key)}?size=400`;
          wrap.classList.remove("no-image");
        }
      });
    }

    saveBtn.addEventListener("click", async () => {
      if (!currentAlbum) return;
      saveBtn.disabled = true;
      const artUrl = (fUrl.value || "").trim() || selectedArtUrl || undefined;
      setStatus(artUrl ? "Saving (downloading artwork)…" : "Saving…");
      try {
        const r = await fetch("/api/album/edit", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            offset: currentAlbum.offset,
            title:  fTitle.value,
            artist: fArtist.value,
            year:   fYear.value === "" ? null : Number(fYear.value),
            art_url: artUrl
          })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
        applySaved(j.album);
        closeSheet();
        showToast("Album saved to the app's database");
      } catch (e) {
        setStatus("Save failed: " + e.message);
        saveBtn.disabled = false;
      }
    });

    resetBtn.addEventListener("click", async () => {
      if (!currentAlbum) return;
      resetBtn.disabled = true;
      try {
        const r = await fetch(`/api/album/edit?offset=${encodeURIComponent(currentAlbum.offset)}`, { method: "DELETE" });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
        applySaved(j.album);
        closeSheet();
        showToast("Edits removed — back to LMS values");
      } catch (e) {
        setStatus("Remove failed: " + e.message);
      } finally {
        resetBtn.disabled = false;
      }
    });
  })();

  // ----- Library search (instant, prefix-aware; collapsible) -----
  (function initSearch() {
    const input    = document.getElementById("search-input");
    const clear    = document.getElementById("search-clear");
    const statusEl = document.getElementById("search-status");
    const row      = document.getElementById("search-row");
    if (!input || !row) return;

    let seq           = 0;     // guards against out-of-order responses
    let abort         = null;  // in-flight fetch controller
    let debounceTimer = null;
    let retryTimer    = null;
    let extTimer      = null;  // delayed external (Pitchfork) search
    let active        = false; // currently showing search results?

    function setStatus(msg) { statusEl.textContent = msg || ""; }

    // Stop searching and restore the random wall, WITHOUT touching whether the
    // bar itself is open. Used when the field is emptied (incl. the 1st X tap).
    // Search lives on the Home screen. Clearing it drops the results grid and
    // restores the Home sections (unplayed / genres) below the search box.
    function stopSearch() {
      active = false;
      seq++;                                   // invalidate any pending response
      if (abort) { try { abort.abort(); } catch (e) {} abort = null; }
      clearTimeout(retryTimer);
      clearTimeout(extTimer);
      extWrap = null; extWrapSeq = -1;         // release the rendered external sections
      setStatus("");
      setBanner(null);
      grid.innerHTML = "";
      grid.classList.add("hidden");
      const hs = document.getElementById("home-sections");
      if (hs) hs.classList.remove("hidden");
    }

    async function run(q) {
      const mySeq = ++seq;
      if (abort) { try { abort.abort(); } catch (e) {} }
      abort = new AbortController();
      clearTimeout(retryTimer);
      // Global search: the external source (Pitchfork
      // reviews) ride a LONGER debounce than the instant local-index search —
      // they're network calls against rate-limit-sensitive APIs. Scheduled
      // before the library fetch so external results appear even when the
      // library search errors or has zero matches.
      clearTimeout(extTimer);
      extTimer = setTimeout(() => runExternal(q, mySeq), 600);
      extAllowBannerClear = false;
      try {
        const r = await fetch(`/api/search?q=${encodeURIComponent(q)}&limit=60`,
                              { signal: abort.signal, cache: "no-store" });
        if (mySeq !== seq) return;                       // superseded by a newer keystroke
        // Library-search failures clear the grid: leaving the PREVIOUS query's
        // results would let this query's external sections append beneath them
        // (a mixed-query page). The banner/status explains what's missing, and
        // extAllowBannerClear stays false so arriving externals can't wipe it.
        if (r.status === 503) { grid.innerHTML = ""; extReappend(mySeq); setBanner("Waiting for LMS…", true); return; }
        if (!r.ok) { grid.innerHTML = ""; extReappend(mySeq); setStatus("search error"); return; }
        const j = await r.json();
        if (mySeq !== seq) return;

        if (j.building) {
          // First-time index build still running — show progress and retry.
          const pct = Math.round((j.progress || 0) * 100);
          setStatus(`Building index… ${pct}%`);
          grid.innerHTML = "";
          extReappend(mySeq);
          retryTimer = setTimeout(() => {
            if (active && input.value.trim() === q) run(q);
          }, 350);
          return;
        }

        const results = j.results || [];
        const labels  = j.labels  || [];
        const artists = j.artists || [];
        if (!results.length && !labels.length && !artists.length) {
          grid.innerHTML = "";
          setStatus("");
          // Externals can still match \u2014 if some already landed, keep them and
          // skip the banner; otherwise show it and let a later external
          // arrival clear it (extAllowBannerClear).
          extAllowBannerClear = true;
          if (!extReappend(mySeq)) setBanner(`No matches for \u201C${q}\u201D.`, false);
          return;
        }
        setBanner(null);
        const more = results.length >= 60 ? "+" : "";
        const parts = [];
        if (artists.length) parts.push(`${artists.length} artist${artists.length === 1 ? "" : "s"}`);
        if (labels.length)  parts.push(`${labels.length} label${labels.length === 1 ? "" : "s"}`);
        if (results.length) parts.push(`${results.length}${more} album${results.length === 1 ? "" : "s"}`);
        setStatus(parts.join(", "));

        grid.innerHTML = "";
        const frag = document.createDocumentFragment();

        // Artists section
        if (artists.length) {
          const hdr = document.createElement("div"); hdr.className = "search-section-header"; hdr.textContent = "Artists";
          frag.appendChild(hdr);
          const row = document.createElement("div"); row.className = "search-chip-row";
          for (const ar of artists) {
            const btn = document.createElement("button"); btn.className = "search-chip";
            btn.textContent = ar.name;
            btn.addEventListener("click", () => {
              stopSearch();
              window.__showArtistAlbums && window.__showArtistAlbums(ar.name);
            });
            row.appendChild(btn);
          }
          frag.appendChild(row);
        }

        // Labels section
        if (labels.length) {
          const hdr = document.createElement("div"); hdr.className = "search-section-header"; hdr.textContent = "Labels";
          frag.appendChild(hdr);
          const row = document.createElement("div"); row.className = "search-chip-row";
          for (const lb of labels) {
            const btn = document.createElement("button"); btn.className = "search-chip";
            btn.textContent = lb.display;
            btn.addEventListener("click", () => {
              stopSearch();
              if (window.__exitLabels) window.__exitLabels();
              if (window.__showLabelAlbums) window.__showLabelAlbums(lb.display);
            });
            row.appendChild(btn);
          }
          frag.appendChild(row);
        }

        // Albums section
        if (results.length) {
          if (artists.length || labels.length) {
            const hdr = document.createElement("div"); hdr.className = "search-section-header"; hdr.textContent = "Albums";
            frag.appendChild(hdr);
          }
          for (const a of results) frag.appendChild(buildAlbumTile(a));
        }

        grid.appendChild(frag);
        // A slow library response can land AFTER this query's external sections
        // rendered — the innerHTML reset above destroyed them, so re-attach.
        extReappend(mySeq);
      } catch (e) {
        if (e && e.name === "AbortError") return;        // expected when typing fast
        if (mySeq === seq) setStatus("search error");
      }
    }

    // ---- Global search: external source (Pitchfork reviews) ----
    // Best-effort and additive: sections are appended below the library results
    // when they arrive; any failure just means that section doesn't appear.
    // All sections live in ONE wrapper (display:contents, so the grid lays out
    // its children directly) — run(q)'s innerHTML resets would otherwise
    // destroy already-rendered externals; extReappend re-attaches the wrapper.
    let extWrap = null;              // rendered external sections for extWrapSeq
    let extWrapSeq = -1;
    let extAllowBannerClear = false; // only the "No matches" banner may be cleared

    function extReappend(mySeq) {
      if (extWrapSeq !== mySeq || !extWrap || !extWrap.childNodes.length) return false;
      grid.appendChild(extWrap);     // appendChild MOVES it if already attached
      return true;
    }

    async function runExternal(q, mySeq) {
      try {
        const r = await fetch(`/api/search/external?q=${encodeURIComponent(q)}`, { cache: "no-store" });
        if (mySeq !== seq || !r.ok) return;
        const j = await r.json();
        if (mySeq !== seq) return;
        const wrap = document.createElement("div");
        wrap.className = "ext-search-wrap";
        let added = 0;
        added += extQobuzSection(wrap, j.qobuz);        // playable online albums first
        added += extPitchforkSection(wrap, j.pitchfork);
        if (!added) return;
        extWrap = wrap;
        extWrapSeq = mySeq;
        // Externals may arrive while a "No matches for X" banner shows —
        // clear THAT banner (there are matches after all), but never the
        // Roon-disconnect/error banners, which explain the missing library rows.
        if (extAllowBannerClear) setBanner(null);
        grid.appendChild(wrap);
      } catch (e) { /* best-effort — external sections just don't appear */ }
    }

    function extHeader(frag, label) {
      const hdr = document.createElement("div");
      hdr.className = "search-section-header";
      hdr.textContent = label;
      frag.appendChild(hdr);
    }

    function extRow(cover, title, sub, onClick) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ext-search-row";
      const img = document.createElement("img");
      img.className = "ext-search-art"; img.loading = "lazy"; img.alt = "";
      if (cover) {
        img.src = cover;
        // Dead cover URL → blank placeholder box, not the broken-image glyph.
        img.addEventListener("error", () => { img.removeAttribute("src"); img.style.visibility = "hidden"; });
      } else {
        img.style.visibility = "hidden";
      }
      const tx = document.createElement("div"); tx.className = "ext-search-meta";
      const t  = document.createElement("div"); t.className = "ext-search-title"; t.textContent = title;
      const s  = document.createElement("div"); s.className = "ext-search-sub";   s.textContent = sub || "";
      tx.appendChild(t); tx.appendChild(s);
      btn.appendChild(img); btn.appendChild(tx);
      btn.addEventListener("click", onClick);
      return btn;
    }


    // Qobuz section: albums NOT in the library that Qobuz can stream. Each row
    // offers Play Now / Add to Queue without importing the album (the actions
    // were captured server-side from the LMS Qobuz plugin at search time; the
    // client only echoes the opaque token back to /api/qobuz/play).
    async function qobuzPlay(token, kind, btn) {
      if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
      const orig = btn.innerHTML; btn.disabled = true; btn.classList.add("is-busy");
      try {
        const r = await fetch("/api/qobuz/play", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token, zone_or_output_id: selectedZoneId, kind })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
        showToast(`${kind === "queue" ? "Queued" : "Playing"} → ${zoneName(selectedZoneId)}`);
      } catch (e) {
        showToast(e.message, "error");
      } finally { btn.disabled = false; btn.classList.remove("is-busy"); btn.innerHTML = orig; }
    }

    const QOBUZ_PLAY_SVG  = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>';
    const QOBUZ_QUEUE_SVG = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M4 10h12v2H4zm0-4h12v2H4zm0 8h8v2H4zm10 0h3v-3h2v3h3v2h-3v3h-2v-3h-3z"/></svg>';

    // One Qobuz album row (cover + title/artist + Play / Queue / Heart). Reused
    // by search results AND the native Qobuz browser (window.__buildQobuzRow).
    function buildQobuzAlbumRow(it) {
      const row = document.createElement("div");
      row.className = "ext-search-row ext-qobuz-row";
      const img = document.createElement("img");
      img.className = "ext-search-art"; img.loading = "lazy"; img.alt = "";
      if (it.image_key) {
        img.src = `/api/image/${encodeURIComponent(it.image_key)}?size=100`;
        img.addEventListener("error", () => { img.removeAttribute("src"); img.style.visibility = "hidden"; });
      } else { img.style.visibility = "hidden"; }
      const tx = document.createElement("div"); tx.className = "ext-search-meta";
      const t  = document.createElement("div"); t.className = "ext-search-title"; t.textContent = it.title || "Untitled";
      const s  = document.createElement("div"); s.className = "ext-search-sub";   s.textContent = it.subtitle || "";
      tx.appendChild(t); tx.appendChild(s);
      const actions = document.createElement("div"); actions.className = "ext-qobuz-actions";
      const playBtn = document.createElement("button");
      playBtn.type = "button"; playBtn.className = "ext-qobuz-btn"; playBtn.title = "Play now";
      playBtn.setAttribute("aria-label", "Play now"); playBtn.innerHTML = QOBUZ_PLAY_SVG;
      playBtn.addEventListener("click", () => qobuzPlay(it.token, "play_now", playBtn));
      actions.appendChild(playBtn);
      if (it.can_queue) {
        const qBtn = document.createElement("button");
        qBtn.type = "button"; qBtn.className = "ext-qobuz-btn"; qBtn.title = "Add to queue";
        qBtn.setAttribute("aria-label", "Add to queue"); qBtn.innerHTML = QOBUZ_QUEUE_SVG;
        qBtn.addEventListener("click", () => qobuzPlay(it.token, "queue", qBtn));
        actions.appendChild(qBtn);
      }
      if (it.can_favorite) {
        const heart = document.createElement("button");
        heart.type = "button"; heart.className = "ext-qobuz-btn ext-qobuz-heart";
        setHeart(heart, false);
        heart.addEventListener("click", () => qobuzFavPost("/api/qobuz/favorite", { token: it.token }, heart));
        actions.appendChild(heart);
      }
      row.appendChild(img); row.appendChild(tx); row.appendChild(actions);
      return row;
    }
    window.__buildQobuzRow = buildQobuzAlbumRow;   // used by the native Qobuz browser

    function extQobuzSection(frag, items) {
      if (!items || !items.length) return 0;
      extHeader(frag, "Available on Qobuz");
      for (const it of items) frag.appendChild(buildQobuzAlbumRow(it));
      return items.length;
    }

    // ----- Native Qobuz browser (grid tiles + tappable album detail) -----
    // Same scope as qobuzPlay / qobuzFavPost / setHeart / showToast / TILE_IMG_SIZE.
    (function initQobuzBrowse() {
      const overlay = document.getElementById("qobuz-browse-overlay");
      const body    = document.getElementById("qb-body");
      const titleEl = document.getElementById("qb-title");
      const backBtn = document.getElementById("qb-back");
      if (!overlay || !body) return;
      const favCache = new Map();     // fav_key → bool, persists heart state within the session
      let stack = [];
      let seq = 0;
      const CHEVRON = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="9 18 15 12 9 6"/></svg>';

      function closeOverlay() { overlay.classList.add("hidden"); document.body.style.overflow = ""; }
      overlay.querySelectorAll("[data-qb-close]").forEach(el => el.addEventListener("click", closeOverlay));
      backBtn.addEventListener("click", () => { if (stack.length > 1) { stack.pop(); renderFrame(); } else closeOverlay(); });
      const msg = (cls, text) => { body.innerHTML = ""; const d = document.createElement("div"); d.className = cls; d.textContent = text; body.appendChild(d); };

      function renderFrame() {
        const f = stack[stack.length - 1] || { kind: "list", item_id: null, title: "Browse Qobuz" };
        titleEl.textContent = f.title || "Browse Qobuz";
        backBtn.hidden = stack.length <= 1;
        body.scrollTop = 0;
        if (f.kind === "album") renderAlbum(f.album);
        else loadList(f);
      }

      // A big favourites / bestsellers list paginates — load page 0, then fetch
      // more as the bottom sentinel scrolls into view (IntersectionObserver is
      // robust to whichever element actually scrolls).
      let list = null;   // { itemId, loaded, total, busy, mySeq, nodesEl, gridEl, io }
      const browseUrl = (itemId, start) => "/api/qobuz/browse?start=" + start +
        (itemId != null ? "&item_id=" + encodeURIComponent(itemId) : "");

      function nodeRow(n) {
        const b = document.createElement("button"); b.type = "button"; b.className = "qb-node";
        const t = document.createElement("span"); t.className = "qb-node-title"; t.textContent = n.title || "…";
        const chev = document.createElement("span"); chev.className = "qb-chevron"; chev.innerHTML = CHEVRON;
        b.appendChild(t); b.appendChild(chev);
        b.addEventListener("click", () => { stack.push({ kind: "list", item_id: n.item_id, title: n.title }); renderFrame(); });
        return b;
      }
      function appendItems(items) {
        if (!list) return;
        for (const it of items) {
          if (it.kind === "node") list.nodesEl.appendChild(nodeRow(it));
          else list.gridEl.appendChild(albumTile(it));
        }
        list.loaded += items.length;
      }
      async function loadMore() {
        const s = list;
        if (!s || s.busy || s.loaded >= s.total) return;
        s.busy = true;
        try {
          const r = await fetch(browseUrl(s.itemId, s.loaded), { cache: "no-store" });
          const j = await r.json();
          if (list !== s || s.mySeq !== seq) return;   // navigated away mid-fetch
          if (r.ok) { s.total = j.total || s.total; appendItems(j.items || []); }
        } catch (e) { /* keep what we have */ }
        finally { if (list === s) s.busy = false; }
      }

      async function loadList(f) {
        if (list && list.io) { list.io.disconnect(); }
        list = null;
        msg("qb-loading", "Loading…");
        const mySeq = ++seq;
        let j;
        try {
          const r = await fetch(browseUrl(f.item_id, 0), { cache: "no-store" });
          j = await r.json();
          if (mySeq !== seq) return;
          if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
        } catch (e) { if (mySeq === seq) msg("qb-empty", "Couldn't load: " + e.message); return; }
        const items = j.items || [];
        if (j.notice && !items.length) { msg("qb-empty", j.notice); return; }
        if (!items.length) { msg("qb-empty", "Nothing here."); return; }
        body.innerHTML = "";
        const nodesEl = document.createElement("div"); nodesEl.className = "qb-nodes";
        const gridEl  = document.createElement("div"); gridEl.className = "qb-grid";
        const sentinel = document.createElement("div"); sentinel.className = "qb-sentinel";
        body.appendChild(nodesEl); body.appendChild(gridEl); body.appendChild(sentinel);
        const io = new IntersectionObserver((es) => { if (es[0].isIntersecting) loadMore(); });
        io.observe(sentinel);
        list = { itemId: f.item_id, loaded: 0, total: j.total || items.length, busy: false, mySeq, nodesEl, gridEl, io };
        appendItems(items);
      }

      function albumTile(a) {
        const btn = document.createElement("button"); btn.type = "button"; btn.className = "album qb-tile";
        const artWrap = document.createElement("div"); artWrap.className = "album-art-wrap";
        if (a.image_key) {
          const img = document.createElement("img"); img.loading = "lazy"; img.alt = "";
          img.src = `/api/image/${encodeURIComponent(a.image_key)}?size=${TILE_IMG_SIZE}`;
          img.onerror = () => { artWrap.classList.add("no-image"); img.remove(); };
          artWrap.appendChild(img);
        } else { artWrap.classList.add("no-image"); }
        if (a.can_favorite) {
          const heart = document.createElement("button"); heart.type = "button"; heart.className = "album-fav-heart";
          setHeart(heart, favCache.get(a.fav_key) === true);
          heart.addEventListener("click", (e) => { e.stopPropagation(); toggleFav(a, heart); });
          artWrap.appendChild(heart);
        }
        const meta = document.createElement("div"); meta.className = "album-meta";
        meta.innerHTML = '<div class="album-title"></div><div class="album-artist"></div>';
        meta.querySelector(".album-title").textContent  = a.title || "Untitled";
        meta.querySelector(".album-artist").textContent = a.subtitle || "";
        btn.appendChild(artWrap); btn.appendChild(meta);
        // Open the SHARED album modal (same look as the main library) rather than
        // a bespoke detail — it inherits the cover / ambient / TRACKS styling.
        btn.addEventListener("click", () => { if (window.__openAlbum) window.__openAlbum(a, { source: "qobuz" }); });
        // Built here rather than by buildAlbumTile, so it doesn't inherit that
        // long-press. Wire it explicitly, or a catalogue album can't be added
        // to this app's Favourites from the place you actually find it.
        // No "Select" — these have no library offset to multi-select on.
        if (window.__addLongPress && window.__openAlbumSheet) {
          window.__addLongPress(btn, () => window.__openAlbumSheet(a, { tileEl: btn, allowSelect: false }));
        }
        return btn;
      }

      async function toggleFav(a, btn) {
        const want = !(favCache.get(a.fav_key) === true);
        btn.disabled = true;
        try {
          const r = await fetch("/api/qobuz/favorite", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: a.token, favorite: want }) });
          const j = await r.json().catch(() => ({}));
          if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
          if (a.fav_key) favCache.set(a.fav_key, j.favorite);
          setHeart(btn, j.favorite);
          showToast(j.favorite ? "Added to Qobuz favourites" : "Removed from Qobuz favourites");
        } catch (e) { showToast(e.message, "error"); }
        finally { btn.disabled = false; }
      }

      function actionBtn(cls, html, onClick) {
        const b = document.createElement("button"); b.type = "button"; b.className = cls; b.innerHTML = html;
        b.addEventListener("click", () => onClick(b));
        return b;
      }

      async function renderAlbum(a) {
        body.innerHTML = "";
        const head = document.createElement("div"); head.className = "qb-album-head";
        const cover = document.createElement("div"); cover.className = "album-art-wrap qb-album-cover";
        if (a.image_key) { const img = document.createElement("img"); img.alt = ""; img.src = `/api/image/${encodeURIComponent(a.image_key)}?size=600`; img.onerror = () => { cover.classList.add("no-image"); img.remove(); }; cover.appendChild(img); } else cover.classList.add("no-image");
        const meta = document.createElement("div"); meta.className = "qb-album-meta";
        const t = document.createElement("div"); t.className = "qb-album-title"; t.textContent = a.title || "";
        const s = document.createElement("div"); s.className = "qb-album-artist"; s.textContent = a.subtitle || "";
        meta.appendChild(t); meta.appendChild(s);
        const acts = document.createElement("div"); acts.className = "qb-album-actions";
        acts.appendChild(actionBtn("qb-act-btn", QOBUZ_PLAY_SVG + "<span>Play</span>", (btn) => qobuzPlay(a.token, "play_now", btn)));
        if (a.can_queue) acts.appendChild(actionBtn("qb-act-btn", QOBUZ_QUEUE_SVG + "<span>Queue</span>", (btn) => qobuzPlay(a.token, "queue", btn)));
        if (a.can_favorite) {
          const heart = document.createElement("button"); heart.type = "button"; heart.className = "qb-act-btn qb-album-heart ext-qobuz-heart";
          setHeart(heart, favCache.get(a.fav_key) === true);
          heart.addEventListener("click", () => toggleFav(a, heart));
          acts.appendChild(heart);
        }
        meta.appendChild(acts);
        head.appendChild(cover); head.appendChild(meta); body.appendChild(head);
        const list = document.createElement("div"); list.className = "qb-tracks";
        list.innerHTML = '<div class="qb-loading">Loading tracks…</div>';
        body.appendChild(list);
        const mySeq = ++seq;
        try {
          const r = await fetch("/api/qobuz/album?token=" + encodeURIComponent(a.token), { cache: "no-store" });
          const j = await r.json();
          if (mySeq !== seq) return;
          if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
          if (j.favorite != null) { if (a.fav_key) favCache.set(a.fav_key, j.favorite); const h = acts.querySelector(".qb-album-heart"); if (h) setHeart(h, j.favorite); }
          renderTracks(list, j.tracks || []);
        } catch (e) { list.innerHTML = ""; const d = document.createElement("div"); d.className = "qb-empty"; d.textContent = "Couldn't load tracks: " + e.message; list.appendChild(d); }
      }

      function renderTracks(list, tracks) {
        list.innerHTML = "";
        if (!tracks.length) { const d = document.createElement("div"); d.className = "qb-empty"; d.textContent = "No track list."; list.appendChild(d); return; }
        tracks.forEach((t, i) => {
          const row = document.createElement("div"); row.className = "qb-track";
          const num = document.createElement("span"); num.className = "qb-track-num"; num.textContent = String(i + 1);
          const tx = document.createElement("div"); tx.className = "qb-track-meta";
          const tt = document.createElement("div"); tt.className = "qb-track-title"; tt.textContent = t.title || "";
          tx.appendChild(tt);
          if (t.artist) { const ta = document.createElement("div"); ta.className = "qb-track-artist"; ta.textContent = t.artist; tx.appendChild(ta); }
          const play = document.createElement("button"); play.type = "button"; play.className = "ext-qobuz-btn"; play.title = "Play now"; play.setAttribute("aria-label", "Play now"); play.innerHTML = QOBUZ_PLAY_SVG;
          play.addEventListener("click", () => qobuzPlay(t.token, "play_now", play));
          row.appendChild(num); row.appendChild(tx); row.appendChild(play);
          list.appendChild(row);
        });
      }

      window.__openQobuzBrowse = function () {
        stack = [{ kind: "list", item_id: null, title: "Browse Qobuz" }];
        overlay.classList.remove("hidden");
        document.body.style.overflow = "hidden";
        renderFrame();
      };
    })();

    // Pitchfork section: tapping a review deep-links to its detail view.
    function extPitchforkSection(frag, items) {
      if (!items || !items.length) return 0;
      extHeader(frag, "Pitchfork reviews");
      for (const it of items) {
        const row = extRow(it.cover, it.album, it.artist, () => {
          stopSearch();
          if (window.__openPitchforkReview) window.__openPitchforkReview(it);
        });
        if (it.score != null) {
          const sc = document.createElement("span");
          sc.className = "ext-search-score" + (it.isBestNewMusic ? " is-bnm" : "");
          sc.textContent = Number(it.score).toFixed(1);
          row.appendChild(sc);
        }
        frag.appendChild(row);
      }
      return items.length;
    }

    function onInput() {
      const q = input.value.trim();
      clearTimeout(debounceTimer);
      if (!q) { stopSearch(); return; }                  // emptied: back to Home sections
      if (window.__exitLabels) window.__exitLabels();    // leave the label browser
      exitAlbumSelectMode();
      active = true;
      // Show the results grid in place of the Home sections (the search box
      // above it stays put).
      const hs = document.getElementById("home-sections");
      if (hs) hs.classList.add("hidden");
      grid.classList.remove("hidden");
      // Small debounce: long enough to coalesce a fast burst, short enough to
      // still feel instant.
      debounceTimer = setTimeout(() => run(q), 120);
    }

    input.addEventListener("input",  onInput);
    input.addEventListener("search", onInput);
    input.addEventListener("keydown", (e) => {
      // The search box is always present on Home; Escape just clears it.
      if (e.key === "Escape") { input.value = ""; stopSearch(); input.blur(); }
    });

    // The X has two stages: 1st tap clears the text (bar stays open), 2nd tap
    // (now empty) closes the bar.
    clear.addEventListener("click", () => {
      // The box stays present on Home; clearing empties it and restores the
      // Home sections, keeping focus so the user can retype.
      input.value = "";
      stopSearch();
      input.focus();
    });

    window.__runSearch = (q) => { input.value = q; onInput(); };
    // Called when leaving Home for the wall/labels so stale search results
    // don't linger in the shared grid. No-op unless a search is active.
    window.__clearSearchIfActive = () => { if (active) { input.value = ""; stopSearch(); } };
    window.__searchActive = () => active;
  })();

  // ----- Boot -----
  refreshBtn.addEventListener("click", loadRandom);

  // ----- Filter sheet (All / Genre / Tag) -----
  (() => {
    const overlay      = document.getElementById("filter-overlay");
    const toggleBtn    = document.getElementById("filter-toggle");
    const allBtn       = document.getElementById("filter-all");
    const allCheck     = overlay && overlay.querySelector('.filter-check[data-for="all"]');
    const genresToggle = document.getElementById("filter-genres-toggle");
    const genresList   = document.getElementById("filter-genres-list");
    const decadesToggle = document.getElementById("filter-decades-toggle");
    const decadesList   = document.getElementById("filter-decades-list");
    if (!overlay || !toggleBtn) return;

    function markActive() {
      toggleBtn.classList.toggle("is-active", !!activeFilter);
      if (allCheck) allCheck.classList.toggle("hidden", !!activeFilter);
      for (const el of overlay.querySelectorAll(".filter-item")) {
        const t = el.dataset.ftype, v = el.dataset.fvalue;
        el.classList.toggle("is-current",
          !!activeFilter && activeFilter.type === t && activeFilter.value === v);
      }
    }

    function applyFilter(f) {
      activeFilter = f;
      try {
        if (f) localStorage.setItem("rra-filter", JSON.stringify(f));
        else   localStorage.removeItem("rra-filter");
      } catch (e) {} // localStorage optional (private browsing)
      if (window.__exitLabels) window.__exitLabels();
      markActive();
      close();
      if (window.__showWall) window.__showWall();   // reveal the album grid (leave Home)
      // Entering a filtered grid must start at the TOP. <main> is the sole
      // scroller, and tapping a genre card low on the Home screen otherwise
      // leaves the new grid scrolled to that offset (mirrors showHome()).
      const m = document.querySelector("main");
      if (m) m.scrollTop = 0;
      updateCountReadout(null);
      loadRandom();
    }
    window.__applyFilter = applyFilter;   // used by the Home "Browse by genre" cards

    function renderList(container, type, rows) {
      container.innerHTML = "";
      if (!rows.length) {
        const d = document.createElement("div");
        d.className = "filter-empty";
        d.textContent = type === "genre" ? "No genres found"
                      : "No decades — no album release years in the library yet.";
        container.appendChild(d);
        return;
      }
      for (const row of rows) {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "filter-item";
        b.dataset.ftype = type;
        b.dataset.fvalue = row.title;
        const t = document.createElement("span");
        t.className = "filter-item-title";
        t.textContent = row.title;
        b.appendChild(t);
        if (row.subtitle) {
          const sub = document.createElement("span");
          sub.className = "filter-item-sub";
          sub.textContent = row.subtitle;
          b.appendChild(sub);
        }
        b.addEventListener("click", () => applyFilter({ type, value: row.title }));
        container.appendChild(b);
      }
      markActive();
    }

    const loaded = { genre: false, decade: false };
    async function ensureList(type) {
      if (loaded[type]) return;
      const container = type === "genre" ? genresList : decadesList;
      container.innerHTML = '<div class="filter-empty">Loading\u2026</div>';
      try {
        const url = type === "genre" ? "/api/filters/genres" : "/api/filters/decades";
        const r = await fetch(url);
        const j = await r.json();
        if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
        const rows = type === "genre" ? j.genres : j.decades;
        renderList(container, type, rows || []);
        loaded[type] = true;
      } catch (e) {
        container.innerHTML = "";
        const d = document.createElement("div");
        d.className = "filter-empty";
        d.textContent = "Couldn't load: " + e.message;
        container.appendChild(d);
      }
    }

    function wireSection(toggle, list, type) {
      toggle.addEventListener("click", async () => {
        const willOpen = list.classList.contains("hidden");
        list.classList.toggle("hidden", !willOpen);
        toggle.setAttribute("aria-expanded", String(willOpen));
        toggle.classList.toggle("is-open", willOpen);
        if (willOpen) await ensureList(type);
      });
    }
    wireSection(genresToggle, genresList, "genre");
    // Tags section was removed from the sheet (owner decision, v1.0.8) — no
    // toggle/list elements and no /api/filters/tags plumbing remain.
    wireSection(decadesToggle, decadesList, "decade");

    function open()  { overlay.classList.remove("hidden"); markActive(); }
    function close() { overlay.classList.add("hidden"); }

    toggleBtn.addEventListener("click", open);
    allBtn.addEventListener("click", () => applyFilter(null));
    overlay.addEventListener("click", (e) => {
      if (e.target.closest && e.target.closest("[data-filter-close]")) close();
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && !overlay.classList.contains("hidden")) close();
    });

    markActive();
  })();

  // ----- Labels browser (record labels → their albums) -----
  // Tapping the tag button shows every record label as a grid tile
  // (alphabetical). Tapping a label shows its albums — alphabetical by
  // default, or shuffled per the "Label album order" setting. Each album
  // opens carrying a { type:"label" } filter so detail + play resolve the
  // offset against that label's album list (reusing all existing machinery).
  (() => {
    const labelsBtn          = document.getElementById("labels-toggle");
    const labelsBar          = document.getElementById("labels-bar");
    const labelsBack         = document.getElementById("labels-back");
    const labelsTitle        = document.getElementById("labels-title");
    const labelMergeBar      = document.getElementById("label-merge-bar");
    const labelMergeInfo     = document.getElementById("label-merge-info");
    const labelMergeBtn      = document.getElementById("label-merge-btn");
    const labelMergeCancelBtn = document.getElementById("label-merge-cancel-btn");
    const labelUnmergeSheet  = document.getElementById("label-unmerge-sheet");
    const labelUnmergeName   = document.getElementById("label-unmerge-name");
    const labelUnmergeList   = document.getElementById("label-unmerge-list");
    const labelUnmergeClose  = document.getElementById("label-unmerge-close");
    const labelsLogoBtn      = document.getElementById("labels-logo-btn");
    const logoUrlSheet       = document.getElementById("logo-url-sheet");
    const logoCandidatesEl   = document.getElementById("logo-candidates");
    const logoUrlInput       = document.getElementById("logo-url-input");
    const logoUrlSave        = document.getElementById("logo-url-save");
    const logoUrlCancel      = document.getElementById("logo-url-cancel");
    if (!labelsBtn) return;

    let currentLabelName = null;
    let currentLabelLogoUrl = null; // set when showLabelAlbums loads — used by logo picker
    let _labelsScrollSaved = 0;    // restores position when returning from a label's album view
    let _labelsScrollTarget = null; // label name to scroll into view when arriving via a deep-link (album/search)
    const mainEl = document.querySelector("main");

    const TAG_SVG =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M20.59 13.41 13.42 20.58a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/>' +
      '<line x1="7" y1="7" x2="7.01" y2="7"/></svg>';

    let mode = null;           // null | "list" | "albums"
    let _lastLabelCount = -1;  // track last rendered count to avoid flicker on re-poll
    let labelsSelectMode = false;
    let labelsSelected   = [];  // [{key, display, mergedFrom}] — first item is merge target

    function labelOrder() {
      return localStorage.getItem("rra-label-order") === "random" ? "random" : "alpha";
    }
    function labelMin() {
      const v = parseInt(localStorage.getItem("rra-label-min") || "1", 10);
      return Number.isFinite(v) && v > 0 ? v : 1;
    }

    function enterLabelSelectMode() {
      labelsSelectMode = true;
      if (labelMergeBar) { labelMergeBar.classList.remove("hidden"); updateMergeBar(); }
    }

    function exitLabelSelectMode() {
      labelsSelectMode = false;
      labelsSelected = [];
      if (labelMergeBar) labelMergeBar.classList.add("hidden");
      grid.querySelectorAll(".album.label-tile.is-selected,.album.label-tile.is-first-selected")
        .forEach(b => b.classList.remove("is-selected", "is-first-selected"));
    }

    function updateMergeBar() {
      if (!labelMergeInfo || !labelMergeBtn) return;
      const n = labelsSelected.length;
      while (labelMergeInfo.firstChild) labelMergeInfo.removeChild(labelMergeInfo.firstChild);
      if (n === 0) {
        labelMergeInfo.textContent = "Tap labels to select";
        labelMergeBtn.textContent = "Merge";
        labelMergeBtn.disabled = true;
      } else if (n === 1) {
        const s = document.createElement("strong"); s.textContent = labelsSelected[0].display;
        labelMergeInfo.appendChild(s);
        labelMergeInfo.appendChild(document.createTextNode(" — select more to merge"));
        labelMergeBtn.textContent = "Merge";
        labelMergeBtn.disabled = true;
      } else {
        labelMergeInfo.appendChild(document.createTextNode("Merge " + n + " into "));
        const s = document.createElement("strong"); s.textContent = labelsSelected[0].display;
        labelMergeInfo.appendChild(s);
        labelMergeBtn.textContent = "Merge";
        labelMergeBtn.disabled = false;
      }
    }

    function handleLabelTileSelect(btn, lb) {
      const idx = labelsSelected.findIndex(s => s.key === lb.key);
      if (idx >= 0) {
        labelsSelected.splice(idx, 1);
        btn.classList.remove("is-selected", "is-first-selected");
      } else {
        labelsSelected.push({ key: lb.key, display: lb.title, mergedFrom: lb.mergedFrom || [] });
        btn.classList.add("is-selected");
      }
      // Re-apply first-selected only to the first item in the array.
      grid.querySelectorAll(".album.label-tile").forEach(b => b.classList.remove("is-first-selected"));
      if (labelsSelected.length > 0) {
        const fk = labelsSelected[0].key;
        const fb = grid.querySelector(`.album.label-tile[data-label-key="${CSS.escape(fk)}"]`);
        if (fb) fb.classList.add("is-first-selected");
      }
      updateMergeBar();
    }

    function showUnmergeSheet(targetDisplay, sources) {
      if (!labelUnmergeSheet || !labelUnmergeName || !labelUnmergeList) return;
      labelUnmergeName.textContent = targetDisplay;
      labelUnmergeList.innerHTML = "";
      for (const src of sources) {
        const row = document.createElement("div");
        row.className = "label-unmerge-row";
        const nameEl = document.createElement("span");
        nameEl.className = "label-unmerge-source";
        nameEl.textContent = src.display;
        const xBtn = document.createElement("button");
        xBtn.type = "button";
        xBtn.className = "icon-btn label-unmerge-remove";
        xBtn.setAttribute("aria-label", "Remove " + src.display);
        xBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>';
        xBtn.addEventListener("click", async () => {
          xBtn.disabled = true;
          try {
            const r = await fetch("/api/labels/merge/" + encodeURIComponent(src.key), { method: "DELETE" });
            if (!r.ok) throw new Error((await r.json()).error || "Failed");
            row.remove();
            if (!labelUnmergeList.children.length) labelUnmergeSheet.classList.add("hidden");
            _lastLabelCount = -1;
            showLabelsList(false);
          } catch(e) {
            xBtn.disabled = false;
            if (window.__showToast) window.__showToast("Unmerge failed: " + e.message, "error");
          }
        });
        row.appendChild(nameEl);
        row.appendChild(xBtn);
        labelUnmergeList.appendChild(row);
      }
      labelUnmergeSheet.classList.remove("hidden");
    }

    function exitLabels() {
      mode = null;
      labelsActive = false;
      _lastLabelCount = -1;
      labelsBtn.classList.remove("is-active");
      if (labelsBar) labelsBar.classList.add("hidden");
      closeLabelLogoSheet();
      exitLabelSelectMode();
      exitAlbumSelectMode();
      updateScanBar(null);
      if (labelUnmergeSheet) labelUnmergeSheet.classList.add("hidden");
    }
    window.__exitLabels       = exitLabels;
    window.__showLabelAlbums  = showLabelAlbums;

    // ----- Logo picker sheet -----

    async function saveLogo(url) {
      if (!currentLabelName) return;
      try {
        const r = await fetch("/api/labels/logo", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ label: currentLabelName, url })
        });
        const j = await r.json();
        if (j.ok) {
          currentLabelLogoUrl = j.storedUrl || url; // keep current URL in sync with what the server persisted
          closeLabelLogoSheet();
          showToast("Logo saved", "ok");
        } else {
          showToast(j.error || "Failed to save logo", "error");
        }
      } catch (e) {
        showToast("Failed: " + e.message, "error");
      }
    }

    async function loadLogoCandidates(labelName) {
      if (!logoCandidatesEl) return;
      logoCandidatesEl.innerHTML = '<span class="logo-candidates-hint">Searching Discogs…</span>';
      try {
        const r = await fetch("/api/labels/logo-candidates?label=" + encodeURIComponent(labelName));
        const j = await r.json();
        if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
        const candidates = (j && j.candidates) || [];
        logoCandidatesEl.innerHTML = "";
        if (!candidates.length) {
          logoCandidatesEl.innerHTML = '<span class="logo-candidates-hint">No logos found on Discogs</span>';
          return;
        }
        for (const c of candidates) {
          const btn = document.createElement("button");
          btn.type = "button";
          btn.className = "logo-candidate-btn";
          btn.title = c.title || "";
          const img = document.createElement("img");
          img.src = c.img;
          img.alt = c.title || "";
          img.loading = "lazy";
          img.onerror = () => btn.remove();
          btn.appendChild(img);
          btn.addEventListener("click", () => saveLogo(c.img));
          logoCandidatesEl.appendChild(btn);
        }
      } catch (e) {
        logoCandidatesEl.innerHTML = '<span class="logo-candidates-hint">' + (e.message || "Discogs search failed") + '</span>';
      }
    }

    if (labelsLogoBtn) {
      labelsLogoBtn.addEventListener("click", () => {
        if (!logoUrlSheet) return;
        const opening = logoUrlSheet.classList.contains("hidden");
        logoUrlSheet.classList.toggle("hidden");
        if (opening) {
          loadLogoCandidates(currentLabelName || "");
          if (logoUrlInput) {
            if (currentLabelLogoUrl) logoUrlInput.value = currentLabelLogoUrl; // pre-fill existing logo URL
            logoUrlInput.focus();
          }
        }
      });
    }
    if (logoUrlCancel) {
      logoUrlCancel.addEventListener("click", closeLabelLogoSheet);
    }
    if (logoUrlSave) {
      logoUrlSave.addEventListener("click", async () => {
        const url = logoUrlInput ? logoUrlInput.value.trim() : "";
        if (!url || !currentLabelName) return;
        logoUrlSave.disabled = true;
        try {
          await saveLogo(url);
        } finally {
          logoUrlSave.disabled = false;
        }
      });
    }

    function makeScanLogLink() {
      const wrap = document.createElement("div");
      wrap.className = "scan-log-link";
      wrap.style.cssText = "text-align:center;margin:8px 0 4px;font-size:0.8em;opacity:0.7;";
      const a = document.createElement("a");
      a.href = "/api/labels-scan-log";
      a.download = "labels-scan.log";
      a.textContent = "Download scan log";
      a.style.cssText = "color:inherit;text-decoration:underline;cursor:pointer;margin-right:12px;";
      const copyBtn = document.createElement("button");
      copyBtn.textContent = "Copy log";
      copyBtn.style.cssText = "background:none;border:none;color:inherit;text-decoration:underline;cursor:pointer;font-size:inherit;padding:0;";
      copyBtn.addEventListener("click", async () => {
        try {
          const r = await fetch("/api/labels-scan-log");
          const text = await r.text();
          await navigator.clipboard.writeText(text);
          copyBtn.textContent = "Copied!";
          setTimeout(() => { copyBtn.textContent = "Copy log"; }, 2000);
        } catch (e) { copyBtn.textContent = "Failed"; setTimeout(() => { copyBtn.textContent = "Copy log"; }, 2000); }
      });
      wrap.appendChild(a);
      wrap.appendChild(copyBtn);
      return wrap;
    }

    async function showLabelsList(isRepoll = false) {
      if (!isRepoll) {
        if (window.__clearSearchIfActive) window.__clearSearchIfActive();  // drop stale search results
        exitAlbumSelectMode(); closeLabelLogoSheet(); currentLabelName = null; currentLabelLogoUrl = null;
      }
      const restoreScroll = !isRepoll && _labelsScrollSaved > 0;
      mode = "list";
      labelsActive = true;
      clearWallGridSizing();   // labels grid uses its own layout, not the wall's phone-fit
      { const _hv = document.getElementById("home-view"); if (_hv) _hv.classList.add("hidden"); }
      grid.classList.remove("hidden");
      if (window.__setTopbarNav) window.__setTopbarNav(true, false, false);   // Back (to Home), no Refresh, no search
      labelsBtn.classList.add("is-active");
      if (labelsBar) labelsBar.classList.add("hidden");
      setBanner(null);
      setCountText("Labels");
      if (!isRepoll) { renderSkeletons(computeAlbumCount()); _lastLabelCount = -1; }
      try {
        const r = await fetch("/api/filters/labels");
        const j = await r.json();
        if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
        const minAlbums = labelMin();
        const labels = (j.labels || []).filter(lb => (lb.albumCount || 1) >= minAlbums);
        const pct = Math.round((j.progress || 0) * 100);
        if (!labels.length) {
          if (!isRepoll) grid.innerHTML = "";
          if (j.scanning) {
            const msg = pct > 0
              ? "Scanning for record labels… " + pct + "% complete."
              : "Building library index…";
            setBanner(msg, false);
            updateScanBar(j.scanning ? (j.progress || 0) : null);
            // Re-poll every 4 s while the scan is running
            setTimeout(() => { if (mode === "list") showLabelsList(true); }, 4000);
          } else {
            setBanner("No labels found yet — the background scan looks up labels via iTunes and MusicBrainz. This can take a few minutes for large libraries.", false);
            // Show a rescan button so the user can retry without restarting the server.
            const rescanBtn = document.createElement("button");
            rescanBtn.className = "action-btn primary";
            rescanBtn.style.cssText = "margin:16px auto;";
            rescanBtn.textContent = "Rescan now";
            rescanBtn.addEventListener("click", async () => {
              rescanBtn.disabled = true;
              rescanBtn.textContent = "Starting…";
              try {
                await fetch("/api/labels/rescan", { method: "POST",
                  headers: { "Content-Type": "application/json" }, body: "{}" });
                _lastLabelCount = -1;
                setTimeout(() => { if (mode === "list") showLabelsList(false); }, 1000);
              } catch (e) { rescanBtn.disabled = false; rescanBtn.textContent = "Rescan now"; }
            });
            grid.appendChild(rescanBtn);
            grid.appendChild(makeScanLogLink());
          }
          return;
        }
        setCountText("Labels");
        updateScanBar(j.scanning ? (j.progress || 0) : null);
        // Only re-render tiles on first load or when the scan finishes.
        // During an active scan, just update the count text so the grid stays
        // stable — no flash every 5 s as new labels trickle in.
        if (_lastLabelCount <= 0 || !j.scanning) {
          renderLabelTiles(labels);
          const oldLink = grid.querySelector(".scan-log-link");
          if (oldLink) oldLink.remove();
          if (!j.scanning) grid.appendChild(makeScanLogLink());
          if (_labelsScrollTarget && mainEl) {
            // Arrived via a deep-link (album view / search chip). Scroll the grid
            // to that label's tile so "back" lands on it instead of the top.
            const want = _labelsScrollTarget.trim().toLowerCase();
            _labelsScrollTarget = null;
            requestAnimationFrame(() => {
              let found = null;
              grid.querySelectorAll(".label-tile").forEach(t => {
                if (found) return;
                const tt = t.querySelector(".album-title");
                if (tt && tt.textContent.trim().toLowerCase() === want) found = t;
              });
              if (found) found.scrollIntoView({ block: "center" });
            });
          } else if (restoreScroll && mainEl) {
            requestAnimationFrame(() => { mainEl.scrollTop = _labelsScrollSaved; _labelsScrollSaved = 0; });
          }
        }
        // Keep polling while the scan is running
        if (j.scanning) {
          setTimeout(() => { if (mode === "list") showLabelsList(true); }, 5000);
        }
      } catch (e) {
        if (!isRepoll) grid.innerHTML = "";
        setBanner("Couldn't load labels: " + e.message, true);
        // Retry after 10 s so a transient network error doesn't stop updates permanently.
        setTimeout(() => { if (mode === "list") showLabelsList(true); }, 10000);
      }
    }

    function setLabelTextArt(artEl, title) {
      artEl.className = "album-art-wrap is-label-text";
      artEl.innerHTML = "";
      artEl.style.fontSize = "";
      const words = (title || "").trim().split(/\s+/).filter(Boolean);
      (words.length ? words : ["?"]).forEach(word => {
        const span = document.createElement("span");
        span.textContent = word;
        artEl.appendChild(span);
      });
    }

    function renderLabelTiles(labels) {
      if (labels.length === _lastLabelCount && !labelsSelectMode) return; // no change — skip re-render
      if (labelsSelectMode) exitLabelSelectMode(); // re-render clears tile selection state
      _lastLabelCount = labels.length;
      grid.innerHTML = "";
      const frag = document.createDocumentFragment();
      for (const lb of labels) {
        const btn = document.createElement("button");
        btn.className = "album label-tile";
        btn.type = "button";
        btn.setAttribute("aria-label", lb.title || "Label");
        btn.dataset.labelKey = lb.key || "";
        const art = document.createElement("div");
        if (lb.logo_url) {
          art.className = "album-art-wrap is-label-logo";
          const img = document.createElement("img");
          img.loading = "lazy"; img.alt = "";
          img.src = lb.logo_url;
          img.onerror = () => { img.remove(); setLabelTextArt(art, lb.title); };
          art.appendChild(img);
        } else {
          setLabelTextArt(art, lb.title);
        }
        const meta = document.createElement("div");
        meta.className = "album-meta";
        const titleEl  = document.createElement("div"); titleEl.className  = "album-title";  titleEl.textContent  = lb.title || "";
        const artistEl = document.createElement("div"); artistEl.className = "album-artist"; artistEl.textContent = lb.subtitle || "";
        meta.appendChild(titleEl);
        meta.appendChild(artistEl);
        if (lb.mergedFrom && lb.mergedFrom.length > 0) {
          const mergedEl = document.createElement("div");
          mergedEl.className = "album-merged-info";
          mergedEl.textContent = lb.mergedFrom.length + " merged";
          mergedEl.title = "Tap to manage merged labels";
          mergedEl.addEventListener("click", (e) => {
            e.stopPropagation();
            if (!labelsSelectMode) showUnmergeSheet(lb.title, lb.mergedFrom);
          });
          meta.appendChild(mergedEl);
        }
        btn.appendChild(art);
        btn.appendChild(meta);
        btn.addEventListener("click", () => {
          if (labelsSelectMode) handleLabelTileSelect(btn, lb);
          else showLabelAlbums(lb.title, true);
        });
        addLongPress(btn, () => {
          if (!labelsSelectMode) enterLabelSelectMode();
          handleLabelTileSelect(btn, lb);
        });
        frag.appendChild(btn);
      }
      grid.appendChild(frag);
    }

    function closeLabelLogoSheet() {
      if (logoUrlSheet) logoUrlSheet.classList.add("hidden");
      if (logoUrlInput) logoUrlInput.value = "";
      if (logoCandidatesEl) logoCandidatesEl.innerHTML = "";
    }

    async function showLabelAlbums(name, fromLabelsList = false) {
      if (window.__clearSearchIfActive) window.__clearSearchIfActive();  // drop stale search results
      if (fromLabelsList) {
        // Came from a tap on the Labels grid — remember the grid scroll position.
        _labelsScrollSaved = mainEl ? mainEl.scrollTop : 0;
        _labelsScrollTarget = null;
      } else {
        // Deep-linked from an album view or search chip — there's no Labels-grid
        // scroll position to restore, so remember which label to scroll to on back.
        _labelsScrollSaved = 0;
        _labelsScrollTarget = name;
      }
      exitAlbumSelectMode();
      closeLabelLogoSheet();
      currentLabelName = name;
      mode = "albums";
      labelsActive = true;
      clearWallGridSizing();   // label-album grid uses its own layout, not the wall's phone-fit
      { const _hv = document.getElementById("home-view"); if (_hv) _hv.classList.add("hidden"); }
      grid.classList.remove("hidden");
      if (window.__setTopbarNav) window.__setTopbarNav(true, false, false);   // Back (to Home), no Refresh, no search
      labelsBtn.classList.add("is-active");
      if (labelsBar)   labelsBar.classList.remove("hidden");
      if (labelsTitle) labelsTitle.textContent = name;
      setBanner(null);
      setCountText(name);
      renderSkeletons(computeAlbumCount());
      try {
        const r = await fetch("/api/label-albums?label=" + encodeURIComponent(name) +
                              "&order=" + encodeURIComponent(labelOrder()));
        const j = await r.json();
        if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
        currentLabelLogoUrl = j.logo_url || null; // expose to logo picker
        const albums = j.albums || [];
        if (!albums.length) {
          grid.innerHTML = "";
          setBanner("No albums found for this label.", false);
          return;
        }
        setCountText(name);
        grid.innerHTML = "";
        const frag = document.createDocumentFragment();
        for (const a of albums) {
          frag.appendChild(buildAlbumTile(a, () => openAlbum(a)));
        }
        grid.appendChild(frag);
      } catch (e) {
        grid.innerHTML = "";
        setBanner("Couldn't load albums: " + e.message, true);
      }
    }

    if (labelsBack) labelsBack.addEventListener("click", () => showLabelsList());

    window.__exitLabelSelectMode = exitLabelSelectMode;

    if (labelMergeBtn) {
      labelMergeBtn.addEventListener("click", async () => {
        if (labelsSelected.length < 2) return;
        labelMergeBtn.disabled = true;
        try {
          const r = await fetch("/api/labels/merge", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ items: labelsSelected.map(s => ({ key: s.key, display: s.display })) })
          });
          const j = await r.json();
          if (!r.ok) throw new Error(j.error || "Merge failed");
          exitLabelSelectMode();
          _lastLabelCount = -1;
          showLabelsList(false);
        } catch(e) {
          labelMergeBtn.disabled = false;
          if (window.__showToast) window.__showToast("Merge failed: " + e.message, "error");
        }
      });
    }

    if (labelMergeCancelBtn) labelMergeCancelBtn.addEventListener("click", exitLabelSelectMode);

    if (labelUnmergeClose) {
      labelUnmergeClose.addEventListener("click", () => {
        if (labelUnmergeSheet) labelUnmergeSheet.classList.add("hidden");
      });
    }

    labelsBtn.addEventListener("click", () => {
      if (mode) { exitLabels(); loadRandom(); }
      else      { showLabelsList(); }
    });

    // Refresh always returns to the random wall.
    if (refreshBtn) refreshBtn.addEventListener("click", exitLabels);
  })();



  // Play or queue an arbitrary set of albums, from anywhere. Library albums go
  // through /api/play-multi by offset; Qobuz catalogue albums have no offset
  // and are replayed by their action token instead. Shared by the context
  // sheet and the multi-select bar.
  window.__albumAction = async (items, kind) => {
    items = (items || []).filter(Boolean);
    if (!items.length) return;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    const libs   = items.filter(a => a.offset != null);
    const tokens = items.filter(a => a.offset == null && a.token);
    // A favourite whose album has left the library (or was only ever a
    // catalogue album) carries neither an offset nor a token. Say which it is
    // rather than the unhelpful "nothing playable".
    if (!libs.length && !tokens.length) {
      showToast(items.length === 1
        ? "That album isn\u2019t in your library right now"
        : "Those albums aren\u2019t in your library right now", "error");
      return;
    }
    let done = 0;
    if (libs.length) {
      const r = await fetch("/api/play-multi", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ offsets: libs.map(a => a.offset), zone_or_output_id: selectedZoneId, kind }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      done += libs.length;
    }
    // The first token honours `kind`; any after it must append, or each would
    // wipe the one before.
    let first = !libs.length;
    for (const t of tokens) {
      const r = await fetch("/api/qobuz/play", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: t.token, kind: (first && kind === "play_now") ? "play_now" : "queue" }),
      });
      if (r.ok) { done++; first = false; }
    }
    const verb = kind === "play_now" ? "Playing" : "Queued";
    showToast(verb + " " + done + " album" + (done === 1 ? "" : "s") + " \u2192 " + zoneName(selectedZoneId));
  };

  // Enter multi-select from the context sheet, pre-selecting the album it was
  // opened on so the gesture doesn't lose what you long-pressed.
  window.__enterAlbumSelect = (a, tileEl) => {
    if (!albumSelectMode) enterAlbumSelectMode();
    if (tileEl && a) handleAlbumTileSelect(tileEl, a);
  };

  async function invokeAlbumMulti(kind) {
    if (!albumSelected.length) return;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return; }
    try {
      const r = await fetch("/api/play-multi", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          offsets: albumSelected.map(a => a.offset),
          zone_or_output_id: selectedZoneId,
          kind,
          filter_type:   activeFilter ? activeFilter.type   : "",
          filter_value:  activeFilter ? activeFilter.value  : "",
          filter_parent: activeFilter && activeFilter.parent ? activeFilter.parent : ""
        })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      // Report what actually happened, not what was asked for: a selection can
      // lose albums to a rescan between selecting and playing.
      const verb = kind === "play_now" ? "Playing" : "Queued";
      const n = Number.isFinite(j.queued) ? j.queued : albumSelected.length;
      const miss = Number.isFinite(j.failed) ? j.failed : 0;
      showToast(verb + " " + n + " album" + (n === 1 ? "" : "s") +
                (miss ? " (" + miss + " no longer in the library)" : "") +
                " → " + zoneName(selectedZoneId), miss ? "error" : undefined);
      exitAlbumSelectMode();
    } catch (e) {
      showToast(e.message, "error");
      updateAlbumActionBar();
    }
  }

  // The Options menu calls these directly — they are the actions, not click
  // handlers bound to buttons, so the menu can be rebuilt as often as it likes.
  function albumActPlaylist() {
    if (!albumSelected.length || !window.__addToPlaylistSheet) return;
    const n = albumSelected.length;
    window.__afterPlaylistAdd = exitAlbumSelectMode;
    window.__addToPlaylistSheet(
      { offsets: albumSelected.map(a => a.offset) },
      "Every track from " + n + " selected album" + (n === 1 ? "" : "s") + ".");
  }

  // Favourite the whole selection. Always ADDS rather than toggling — a mixed
  // selection should end up all-favourited, not flipped item by item into a
  // state nobody asked for.
  async function albumActFavourite(removing) {
    if (!albumSelected.length) return;
    try {
      const r = await fetch(removing ? "/api/favourites/remove-multi" : "/api/favourites/add-multi", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items: albumSelected.map(a => ({
          title: a.title, subtitle: a.subtitle || "", source: a.source || null,
          image_key: a.image_key || null, qobuz_id: a.qobuz_id || null,
        })) }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      showToast(removing
        ? "Removed " + j.removed + " from Favourites"
        : "Added " + j.added + " to Favourites");
      exitAlbumSelectMode();
      // Repaint the hearts on whatever grid is open without rebuilding it.
      if (window.__refreshFavKeys) window.__refreshFavKeys();
    } catch (e) { showToast(e.message, "error"); updateAlbumActionBar(); }
  }

  // Merge the selection into one album. The FIRST selected is the primary: it
  // supplies the merged title (minus any disc marker) and artist, and the
  // selection order is the disc order, since LMS gives no disc number on an
  // album row to infer it from.
  async function albumActMerge() {
    if (albumSelected.length < 2) return;
    const names = albumSelected.map(a => a.title).join("\u201d, \u201c");
    const go = await confirmDialog(
      "Merge " + albumSelected.length + " albums into one?\n\n\u201c" + names + "\u201d\n\n" +
      "They\u2019ll show as a single album, in this order. You can undo this from Merged albums in the menu.");
    if (!go) return;
    try {
      const r = await fetch("/api/albums/merge", {
        method: "POST", headers: { "Content-Type": "application/json" },
        // Send the offset too: the server resolves each item back to its index
        // record and keys the merge on the album's ORIGINAL LMS name, so a
        // later rename can't drop a disc out of its own merge. Title/subtitle
        // stay as a fallback for a stale offset.
        body: JSON.stringify({ items: albumSelected.map(a => ({ offset: a.offset, title: a.title, subtitle: a.subtitle || "" })) }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      showToast("Merged into \u201c" + (j.merge && j.merge.title) + "\u201d");
      exitAlbumSelectMode();
      // The index was rebuilt server-side, so whatever grid is open is stale.
      if (window.__refreshCurrentView) window.__refreshCurrentView();
    } catch (e) { showToast(e.message, "error"); updateAlbumActionBar(); }
  }
  if (albumActionCancelBtn) albumActionCancelBtn.addEventListener("click", exitAlbumSelectMode);

  window.__openAlbum = openAlbum;
  // Forward the optional onClick — callers that supply one (Live Playlists,
  // any future wall) need their tiles to open unfiltered, not fall back to
  // openAlbum's default filter handling.
  window.__buildAlbumTile = (a, onClick) => buildAlbumTile(a, onClick);
  // /api/services resolves after the first grids have painted, so drop any
  // hearts that were drawn optimistically before the answer arrived.
  window.__repaintServiceUI = () => {
    if (window.__serviceUsable && window.__serviceUsable("qobuz")) return;
    document.querySelectorAll(".album .album-fav-heart").forEach(el => el.remove());
  };
  window.__loadRandom = loadRandom;
  window.__showToast = (msg, kind, ms) => showToast(msg, kind, ms);
  window.__confirmDialog = (msg) => confirmDialog(msg);
  // Play or queue a set of album offsets — the same batch path the album
  // multi-select bar uses, so there is one place that talks to /api/play-multi.
  window.__playOffsets = async (offsets, kind, truncatedTotal) => {
    if (!offsets || !offsets.length) return false;
    if (!selectedZoneId) { showToast("Pick a zone first", "error"); return false; }
    try {
      const r = await fetch("/api/play-multi", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ offsets, zone_or_output_id: selectedZoneId, kind })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      const n = offsets.length;
      const verb = kind === "play_now" ? "Playing" : "Queued";
      showToast(verb + " " + n + " album" + (n === 1 ? "" : "s") +
        (truncatedTotal ? " (first " + n + " of " + truncatedTotal + ")" : "") +
        " → " + zoneName(selectedZoneId));
      return true;
    } catch (e) { showToast(e.message, "error"); return false; }
  };

  async function bootstrap() {
    // Instant open: paint the last Home from cache before we've reconnected, so
    // reopening the PWA shows content immediately instead of reloading the whole
    // screen. Skipped when a filtered wall is being restored (activeFilter), and
    // when there's nothing cached (first-ever launch) we fall back to the banner.
    const painted = !activeFilter && hydrateHomeFromCache();
    if (!painted) setBanner("Connecting to LMS…");
    for (let i = 0; i < 30; i++) {
      try {
        const r = await fetch("/api/status");
        const j = await r.json();
        if (j.paired) {
          setBanner(null);
          // Deliberately NOT awaited: zones and the Home rows are independent,
          // and awaiting this put a whole round trip in front of ANY content
          // appearing. showHome()'s loaders fire immediately; the zone picker
          // fills itself in a moment later.
          loadZones();

          // Home is the landing view; the album wall loads lazily when the
          // user enters it (menu → Random albums / a genre / filter / labels).
          // Exception: a genre/tag filter that survived a reload (restored from
          // localStorage above) means the user was mid-browse a filtered wall —
          // land back on it instead of silently discarding the filter, which is
          // what showHome() would otherwise do on its way to an unfiltered Home.
          if (activeFilter) showWall({ loadIfEmpty: true });
          else showHome();

          // Restore the album modal if it was open
          try {
            const m = sessionStorage.getItem("rra-modal");
            if (m) {
              const parsed = JSON.parse(m);
              if (parsed && parsed.album) {
                openAlbum(parsed.album, { source: parsed.source, zoneId: parsed.zoneId,
                                         filter: parsed.filter });
              }
            }
          } catch (e) {} // corrupt sessionStorage modal state — skip restore, open normally

          // The zone list only changes when a player connects/disconnects —
          // 30s is plenty (was 15s).
          setInterval(loadZones, 30000);
          return;
        }
      } catch (e) {} // /api/status fetch failed — server not ready yet, fall through to "Waiting" banner
      setBanner("Waiting for LMS. Check the server connection in Settings.");
      await new Promise(r => setTimeout(r, 2000));
    }
    setBanner("Still not connected to LMS. Check the server address in Settings.", true);
  }
  bootstrap();
})();

/* ------------------------------------------------------------------ */
/*  Mini transport (now-playing bar at the bottom)                     */
/* ------------------------------------------------------------------ */
(() => {
  const bar       = document.getElementById("mini-transport");
  const titleEl   = document.getElementById("mt-title");
  const artistEl  = document.getElementById("mt-artist");
  const btnPP     = document.getElementById("mt-playpause");
  const btnZone   = document.getElementById("mt-zone");
  const zonePop   = document.getElementById("mt-zone-popover");
  const zoneList  = document.getElementById("mt-zone-list");
  const progFill  = document.getElementById("mt-progress-fill");
  const btnVol    = document.getElementById("mt-vol-btn");
  const iconPlay  = document.getElementById("mt-icon-play");
  const iconPause = document.getElementById("mt-icon-pause");
  const iconVol   = document.getElementById("mt-icon-vol");
  const iconMute  = document.getElementById("mt-icon-mute");
  const volPop    = document.getElementById("mt-vol-popover");
  const volSlider = document.getElementById("mt-vol-slider");
  const volVal    = document.getElementById("mt-vol-value");

  // Now-playing screen (Roon-style) elements — shared modal, driven by the
  // same poll loop so there's a single source of truth.
  const modalEl     = document.getElementById("album-modal");
  const bigArt      = document.getElementById("modal-img");
  const npTrack     = document.getElementById("np-track");
  const npArtist    = document.getElementById("np-artist");
  const npShuffle   = document.getElementById("np-shuffle");
  const npRepeat    = document.getElementById("np-repeat");
  const npRepeatBadge = document.getElementById("np-repeat-badge");
  const npRadio     = document.getElementById("np-radio");
  const npRadioRow  = document.querySelector(".np-radio-row");

  // ---- Now Playing transport modes -------------------------------------
  // Painted FROM the poll, never from a local guess: another client (or LMS's
  // own web UI) can change these, and a toggle computed client-side would then
  // send the wrong value. Each control sends a concrete mode.
  // NOTE: in THIS IIFE `selectedZoneId` is a FUNCTION that reads #zone-select,
  // not the id variable of the main IIFE. Using it as a value made every call
  // here address a garbage player: JSON.stringify drops a function, so the
  // radio POST arrived with no zone at all (HTTP 400), and shuffle/repeat
  // silently went to a nonsense player id. Always CALL it.
  let npModeBusy = false;
  function paintTransportModes(zone) {
    if (!npShuffle || !npRepeat) return;
    if (npModeBusy) return;                 // don't fight an in-flight change
    const sh = Number(zone.shuffle) || 0;   // 0 off, 1 songs, 2 albums
    const rp = Number(zone.repeat)  || 0;   // 0 off, 1 track, 2 queue
    npShuffle.setAttribute("aria-pressed", sh > 0 ? "true" : "false");
    npShuffle.title = sh === 2 ? "Shuffle albums" : sh === 1 ? "Shuffle songs" : "Shuffle";
    npRepeat.setAttribute("aria-pressed", rp > 0 ? "true" : "false");
    npRepeat.title = rp === 1 ? "Repeat this track" : rp === 2 ? "Repeat the queue" : "Repeat";
    // LMS numbers repeat as 1=track, 2=queue. The badge marks the track case.
    if (npRepeatBadge) npRepeatBadge.classList.toggle("hidden", rp !== 1);
    if (npRadio) npRadio.setAttribute("aria-pressed", zone.radio ? "true" : "false");
  }

  async function setTransportMode(body) {
    const zid = selectedZoneId();
    if (!zid) return;
    npModeBusy = true;
    try {
      const r = await fetch("/api/lms/player/" + encodeURIComponent(zid) + "/mode", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!r.ok) throw new Error("HTTP " + r.status);
    } catch (e) { window.__showToast && window.__showToast(e.message, "error"); }
    finally { npModeBusy = false; }
  }

  if (npShuffle) npShuffle.addEventListener("click", async () => {
    const on = npShuffle.getAttribute("aria-pressed") === "true";
    // Off -> shuffle SONGS. Album shuffle is reachable from Player settings;
    // one tap here should do the thing people mean by "shuffle".
    npShuffle.setAttribute("aria-pressed", on ? "false" : "true");
    await setTransportMode({ shuffle: on ? 0 : 1 });
  });
  if (npRepeat) npRepeat.addEventListener("click", async () => {
    const pressed = npRepeat.getAttribute("aria-pressed") === "true";
    const badgeOn = npRepeatBadge && !npRepeatBadge.classList.contains("hidden");
    // off -> queue -> track -> off, matching the Roon build's cycle.
    const next = !pressed ? 2 : (badgeOn ? 0 : 1);
    npRepeat.setAttribute("aria-pressed", next ? "true" : "false");
    if (npRepeatBadge) npRepeatBadge.classList.toggle("hidden", next !== 1);
    await setTransportMode({ repeat: next });
  });
  if (npRadio) npRadio.addEventListener("click", async () => {
    const zid = selectedZoneId();
    if (!zid) return;
    const on = npRadio.getAttribute("aria-pressed") === "true";
    npRadio.setAttribute("aria-pressed", on ? "false" : "true");
    try {
      const r = await fetch("/api/radio", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone: zid, enabled: !on }),
      });
      if (!r.ok) throw new Error("HTTP " + r.status);
      window.__showToast && window.__showToast(!on ? "Random album radio on \u2014 whole albums keep coming"
                    : "Random album radio off");
    } catch (e) { window.__showToast && window.__showToast(e.message, "error"); npRadio.setAttribute("aria-pressed", on ? "true" : "false"); }
  });

  const npAlbum     = document.getElementById("np-album");
  const npSeek      = document.getElementById("np-seek");
  const npCur       = document.getElementById("np-cur");
  const npTot       = document.getElementById("np-tot");
  const npPrev      = document.getElementById("np-prev");
  const npPlayPause = document.getElementById("np-playpause");
  const npNext      = document.getElementById("np-next");
  const npIconPlay  = document.getElementById("np-icon-play");
  const npIconPause = document.getElementById("np-icon-pause");
  const npVolBtn    = document.getElementById("np-volbtn");
  const npVolPopover= document.getElementById("np-vol-popover");
  const npVolFixed  = document.getElementById("np-vol-fixed");
  const npIconVol   = document.getElementById("np-icon-vol");
  const npIconMute  = document.getElementById("np-icon-mute");
  const npVolSlider = document.getElementById("np-vol-slider");
  const npVolPanel  = document.getElementById("np-vol-panel");
  const npVolVal    = document.getElementById("np-vol-value");
  const npVolMinus  = document.getElementById("np-vol-minus");
  const npVolPlus   = document.getElementById("np-vol-plus");

  let currentZone = null;       // server-side zone state
  let pollTimer   = null;
  let lastNpImgKey = null;
  let userIsDraggingVolume = false;
  let userIsDraggingSeek   = false;
  let npLen = 0;                // current track length (s)
  let npPos = 0;                // local seek position (s), advanced between polls

  // Tap the album name on the now-playing screen to open that album's detail.
  // We must search the index first to find the album's offset — the now-playing
  // data alone doesn't carry it, and /api/album requires a valid numeric offset.
  if (npAlbum) {
    npAlbum.addEventListener("click", async () => {
      const np = currentZone && currentZone.now_playing;
      if (!np || typeof window.__openAlbum !== "function") return;
      const albumTitle = np.line3 || "";
      const artist     = np.line2 || "";
      if (!albumTitle) return;
      const norm = s => (s || "").toLowerCase().replace(/[^a-z0-9]/g, "");
      try {
        const r = await fetch("/api/search?q=" + encodeURIComponent(albumTitle) + "&limit=20");
        if (r.ok) {
          const j  = await r.json();
          const rs = j.results || [];
          const match =
            rs.find(a => norm(a.title) === norm(albumTitle) &&
                         artist && norm(a.subtitle).includes(norm(artist.split(" ")[0]))) ||
            rs.find(a => norm(a.title) === norm(albumTitle)) ||
            rs[0];
          if (match && typeof match.offset === "number") {
            window.__openAlbum(match, { source: "search" }); return;
          }
        }
      } catch (e) {} // sessionStorage/JSON parse error — fall through to "not indexed" toast
      if (window.__showToast) window.__showToast("Album not yet indexed — try again in a moment");
    });
  }

  // Is the Roon-style now-playing screen currently on view?
  function onNowPlayingScreen() {
    return modalEl
      && !modalEl.classList.contains("hidden")
      && modalEl.classList.contains("np-mode")
      && modalEl.classList.contains("tab-album");
  }

  function fmtTime(secs) {
    secs = Math.max(0, Math.floor(secs || 0));
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return m + ":" + String(s).padStart(2, "0");
  }

  function selectedZoneId() {
    // Read from the existing zone selector in the topbar
    const sel = document.getElementById("zone-select");
    return sel && sel.value || null;
  }

  let lastTransportSig = "";
  function saveTransportState(zone) {
    if (!zone || !zone.now_playing) return;
    const np = zone.now_playing;
    // The 1.5s poll calls this every tick — synchronous localStorage writes
    // are only worth paying when the persisted fields actually changed.
    const sig = [np.line1, np.line2, np.line3, np.image_key, zone.state].join("|");
    if (sig === lastTransportSig) return;
    lastTransportSig = sig;
    try {
      localStorage.setItem("rra-transport", JSON.stringify({
        line1: np.line1 || "", line2: np.line2 || "", line3: np.line3 || "",
        image_key: np.image_key || "", state: zone.state || "stopped"
      }));
    } catch (e) {} // localStorage optional — transport bar persistence is best-effort
  }

  function restoreTransportState() {
    try {
      const saved = JSON.parse(localStorage.getItem("rra-transport") || "null");
      if (!saved || !saved.line1) return;
      titleEl.textContent  = saved.line1;
      const sub = [saved.line2, saved.line3].filter(Boolean).join(" · ");
      artistEl.textContent = sub || "—";
      bar.classList.remove("hidden");
    } catch (e) {} // corrupt localStorage — transport bar stays hidden, no action needed
  }

  async function fetchState() {
    const zid = selectedZoneId();
    if (!zid) return;  // zone not selected yet — leave bar as-is
    try {
      const r = await fetch("/api/zone-state?zone=" + encodeURIComponent(zid), { cache: "no-store" });
      if (!r.ok) return;  // server/network error — keep current state
      const j = await r.json();
      renderZone(j.zone);
      saveTransportState(j.zone);
    } catch (e) {
      // network blip — keep what we have
    }
  }

  function renderZone(zone) {
    currentZone = zone;
    const np = zone && zone.now_playing;
    if (!np) {
      npLen = 0; npPos = 0;
      paintBarProgress();
      refreshVisibility();
      updateNpScreen();
      return;
    }

    // The static mini-bar bits (text, icons, volume) are skipped when nothing
    // changed — this runs every 1.5s, and unconditional text-node replacement
    // invalidated the fixed bar's paint on every tick even mid-scroll. The
    // seek baseline below always resyncs (it moves every tick by design).
    const volOutput = (zone.outputs || []).find(o => o.volume);
    const muted = (zone.outputs || []).some(o => o.is_muted);
    const playing = zone.state === "playing" || zone.state === "loading";
    paintTransportModes(zone);
    const barSig = [np.line1, np.line2, np.line3, zone.state, muted,
                    volOutput ? volOutput.volume.value : "novol"].join("|");
    if (barSig !== lastBarSig) {
      lastBarSig = barSig;

      // Title = track, subtitle = artist · album
      titleEl.textContent  = np.line1 || "—";
      const sub = [np.line2, np.line3].filter(Boolean).join(" · ");
      artistEl.textContent = sub || "—";

      // Play/pause state
      iconPlay .classList.toggle("hidden",  playing);
      iconPause.classList.toggle("hidden", !playing);
      btnPP.setAttribute("aria-label", playing ? "Pause" : "Play");

      // Volume: use the first output that has a volume control. A player set
      // to fixed 100% output has NO volume object (server strips it) — its
      // speaker button disappears entirely.
      if (volOutput) {
        const v = volOutput.volume;
        volSlider.min   = v.min   != null ? v.min  : 0;
        volSlider.max   = v.max   != null ? v.max  : 100;
        volSlider.step  = v.step  != null ? v.step : 1;
        if (!userIsDraggingVolume) {
          volSlider.value = v.value;
          volVal.textContent = Math.round(v.value);
          paintVolFill(volSlider);
        }
        btnVol.disabled = false;
        btnVol.classList.remove("hidden");
      } else {
        btnVol.disabled = true;
        btnVol.classList.add("hidden");
        volPop.classList.add("hidden");   // don't leave an orphaned popover up
      }

      iconVol .classList.toggle("hidden",  muted);
      iconMute.classList.toggle("hidden", !muted);
    }

    // Resync the local seek baseline used by the now-playing screen's ticker.
    npLen = np.length || 0;
    npPos = np.seek_position != null ? np.seek_position : 0;
    paintBarProgress();

    refreshVisibility();
    updateNpScreen();
  }

  // Mini bar shows whenever something is playing, EXCEPT on the now-playing
  // screen (which has its own transport). It returns on the Queue tab.
  function refreshVisibility() {
    const hasNP = !!(currentZone && currentZone.now_playing);
    bar.classList.toggle("hidden", !hasNP || onNowPlayingScreen());
  }

  // Last-rendered signature of the mini transport bar's static content —
  // renderZone skips its DOM writes while this is unchanged.
  let lastBarSig = "";

  // Track title with any trailing "(…)" detail broken onto its own line
  // (e.g. "Hangover Sex (with Viktoria Tolstoy)" → main line + sub-line).
  let lastNpTitle = null;
  function setNpTrack(title) {
    title = title || "—";
    if (title === lastNpTitle) return;   // poll runs every 1.5s — skip rebuilds
    lastNpTitle = title;
    npTrack.textContent = "";
    const m = /^(.*\S)\s*(\([^()]*\))$/.exec(title);
    if (m) {
      npTrack.append(m[1]);
      const sub = document.createElement("div");
      sub.className = "np-track-sub";
      sub.textContent = m[2];
      npTrack.appendChild(sub);
    } else {
      npTrack.textContent = title;
    }
  }

  // Populate the Roon-style now-playing screen from the live zone state.
  function updateNpScreen() {
    // Big art + ambient glow track the playing album on BOTH np-mode tabs —
    // the Queue tab hides the art but shows the glow — so update them BEFORE
    // the tab-album gate below (onNowPlayingScreen() is false on tab-queue,
    // which would otherwise leave the glow stale across album changes).
    const np = currentZone && currentZone.now_playing;
    const npModeVisible = modalEl
      && !modalEl.classList.contains("hidden")
      && modalEl.classList.contains("np-mode");
    if (npModeVisible && bigArt && np && np.image_key && np.image_key !== lastNpImgKey) {
      bigArt.src = "/api/image/" + encodeURIComponent(np.image_key) + "?size=800";
      lastNpImgKey = np.image_key;
      // Same URL as the big art, so the browser serves it from cache.
      if (window.__setModalAmbient) window.__setModalAmbient(bigArt.src);
    }

    if (!npTrack || !onNowPlayingScreen()) return;
    if (!np) { setNpTrack(null); npArtist.textContent = ""; npAlbum.textContent = ""; return; }

    setNpTrack(np.line1);
    // One clickable link per credited artist, the same renderer the album view
    // and track rows use — it was already here, just never wired to this line.
    npArtist.textContent = "";
    if (np.line2) {
      if (window.__artistLinkNodes) npArtist.appendChild(window.__artistLinkNodes(np.line2, "np-artist-link"));
      else npArtist.textContent = np.line2;
    }
    npAlbum.textContent  = np.line3 || "";
    if (npAlbum) npAlbum.setAttribute("aria-label", "Open album: " + (np.line3 || ""));

    const playing = currentZone.state === "playing" || currentZone.state === "loading";
    npIconPlay .classList.toggle("hidden",  playing);
    npIconPause.classList.toggle("hidden", !playing);
    npPlayPause.setAttribute("aria-label", playing ? "Pause" : "Play");
    npPrev.disabled = !currentZone.is_previous_allowed;
    npNext.disabled = !currentZone.is_next_allowed;

    // Progress / seek (blue fill before the thumb, like Roon)
    const seekable = !!currentZone.is_seek_allowed && npLen > 0;
    npSeek.disabled = !seekable;
    if (npLen > 0) {
      npSeek.max = npLen;
      if (!userIsDraggingSeek) {
        npSeek.value = Math.min(npPos, npLen);
        npCur.textContent = fmtTime(npPos);
      }
      npTot.textContent = fmtTime(npLen);
    } else {
      npSeek.max = 100; npSeek.value = 0;
      npCur.textContent = "0:00"; npTot.textContent = "0:00";
    }
    paintSeek();

    // Volume — show the panel only when the endpoint has a controllable
    // volume; otherwise show "Volume control is fixed" (matches Roon).
    const volOutput = (currentZone.outputs || []).find(o => o.volume);
    if (volOutput) {
      const v = volOutput.volume;
      npVolSlider.min  = v.min  != null ? v.min  : 0;
      npVolSlider.max  = v.max  != null ? v.max  : 100;
      npVolSlider.step = v.step != null ? v.step : 1;
      if (!userIsDraggingVolume) {
        npVolSlider.value = v.value;
        if (npVolVal) npVolVal.textContent = Math.round(v.value);
        paintVolFill(npVolSlider);
      }
      if (npVolPanel) npVolPanel.classList.remove("hidden");
      if (npVolFixed) npVolFixed.classList.add("hidden");
    } else {
      if (npVolPanel) npVolPanel.classList.add("hidden");
      if (npVolFixed) npVolFixed.classList.remove("hidden");
    }
    const muted = (currentZone.outputs || []).some(o => o.is_muted);
    npIconVol .classList.toggle("hidden",  muted);
    npIconMute.classList.toggle("hidden", !muted);
  }

  // Thin progress line along the top of the mini bar (Roon-style).
  function paintBarProgress() {
    if (!progFill) return;
    const pct = npLen > 0 ? Math.max(0, Math.min(100, (npPos / npLen) * 100)) : 0;
    progFill.style.width = pct + "%";
  }

  // Paint a volume slider's filled (accent) portion up to the thumb —
  // Roon-style track fill, shared by the mini-bar and now-playing sliders.
  function paintVolFill(slider) {
    if (!slider) return;
    const min = parseFloat(slider.min) || 0;
    const max = parseFloat(slider.max) || 100;
    const val = parseFloat(slider.value) || 0;
    const pct = max > min ? Math.max(0, Math.min(100, ((val - min) / (max - min)) * 100)) : 0;
    slider.style.setProperty("--vol-fill", pct + "%");
  }

  // Paint the elapsed portion of the scrubber blue (before the thumb).
  function paintSeek() {
    if (!npSeek) return;
    const max = parseFloat(npSeek.max) || 0;
    const val = parseFloat(npSeek.value) || 0;
    const pct = max > 0 ? Math.max(0, Math.min(100, (val / max) * 100)) : 0;
    npSeek.style.setProperty("--seek-fill",
      "linear-gradient(to right, var(--accent) 0%, var(--accent) " + pct + "%, " +
      "var(--border) " + pct + "%, var(--border) 100%)");
  }

  async function seek(seconds) {
    if (!currentZone) return;
    try {
      await fetch("/api/seek", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone_or_output_id: currentZone.zone_id, seconds })
      });
      setTimeout(fetchState, 200);
    } catch (e) { /* seek is best-effort; fetchState() already scheduled above */ }
  }

  async function control(command) {
    if (!currentZone) return;
    try {
      const r = await fetch("/api/control", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone_or_output_id: currentZone.zone_id, command })
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        console.warn("control failed:", j.error || r.status);
      }
      // Refresh quickly so the icon updates
      setTimeout(fetchState, 200);
    } catch (e) { /* transport control is best-effort; fetchState() already scheduled above */ }
  }

  async function setVolume(value) {
    if (!currentZone) return;
    try {
      await fetch("/api/volume", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone_or_output_id: currentZone.zone_id, value })
      });
    } catch (e) { /* ignore */ }
  }
  async function toggleMute() {
    if (!currentZone) return;
    const muted = (currentZone.outputs || []).some(o => o.is_muted);
    try {
      await fetch("/api/volume", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone_or_output_id: currentZone.zone_id, mute: !muted })
      });
      setTimeout(fetchState, 150);
    } catch (e) { /* mute is best-effort; fetchState() already scheduled above */ }
  }

  // Wire controls
  btnPP  .addEventListener("click", () => control("playpause"));

  // Now-playing screen transport (mirrors the mini bar's controls)
  if (npPlayPause) npPlayPause.addEventListener("click", () => control("playpause"));
  if (npPrev)      npPrev.addEventListener("click", () => control("previous"));
  if (npNext)      npNext.addEventListener("click", () => control("next"));

  // Volume popover: tap the speaker to reveal the slider (or the "fixed" note).
  if (npVolBtn && npVolPopover) {
    npVolBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      const dp = document.getElementById("np-device-popover");
      if (dp) dp.classList.add("hidden");
      const willShow = npVolPopover.classList.contains("hidden");
      npVolPopover.classList.toggle("hidden", !willShow);
      npVolBtn.setAttribute("aria-expanded", String(willShow));
    });
  }

  // Close the now-playing popovers when tapping outside the controls row.
  document.addEventListener("click", (e) => {
    if (e.target.closest && e.target.closest(".np-secondary")) return;
    if (npVolPopover) npVolPopover.classList.add("hidden");
    if (npVolBtn) npVolBtn.setAttribute("aria-expanded", "false");
    const dp = document.getElementById("np-device-popover");
    const db = document.getElementById("np-device");
    if (dp) dp.classList.add("hidden");
    if (db) db.setAttribute("aria-expanded", "false");
  });

  // Now-playing scrubber: show the dragged time live, seek on release.
  if (npSeek) {
    npSeek.addEventListener("input", () => {
      userIsDraggingSeek = true;
      npCur.textContent = fmtTime(parseFloat(npSeek.value));
      paintSeek();
    });
    npSeek.addEventListener("change", () => {
      const target = parseFloat(npSeek.value);
      userIsDraggingSeek = false;
      npPos = target;
      paintSeek();
      seek(target);
    });
  }

  // Now-playing volume slider (kept in sync with the mini bar)
  let npVolDebounce = null;
  if (npVolSlider) {
    npVolSlider.addEventListener("input", () => {
      userIsDraggingVolume = true;
      const v = parseFloat(npVolSlider.value);
      volSlider.value = v; volVal.textContent = Math.round(v);
      if (npVolVal) npVolVal.textContent = Math.round(v);
      paintVolFill(npVolSlider); paintVolFill(volSlider);
      clearTimeout(npVolDebounce);
      npVolDebounce = setTimeout(() => setVolume(v), 90);
    });
    npVolSlider.addEventListener("change", () => {
      userIsDraggingVolume = false;
      setVolume(parseFloat(npVolSlider.value));
    });
  }
  if (npVolMinus) npVolMinus.addEventListener("click", (e) => { e.stopPropagation(); stepVolume(-2); });
  if (npVolPlus)  npVolPlus .addEventListener("click", (e) => { e.stopPropagation(); stepVolume(+2); });

  // Advance the now-playing progress bar smoothly between 1.5s polls.
  setInterval(() => {
    if (!currentZone || !currentZone.now_playing || userIsDraggingSeek) return;
    const playing = currentZone.state === "playing" || currentZone.state === "loading";
    if (!playing || npLen <= 0 || npPos >= npLen) return;
    npPos += 1;
    paintBarProgress();
    if (onNowPlayingScreen()) {
      npSeek.value = Math.min(npPos, npLen);
      npCur.textContent = fmtTime(npPos);
      paintSeek();
    }
  }, 1000);

  // Let the modal code refresh bar visibility + the now-playing screen on open,
  // tab switch, and close.
  window.__refreshTransport = () => { refreshVisibility(); updateNpScreen(); };

  // Live getter for the share button: reads currentZone directly at call time
  // instead of relying on a mirrored global kept in sync by convention. This
  // is the third fix for "share card shows a stale album" (v1.5.89, v1.5.90,
  // and the Queue-tab case fixed alongside this getter) — a read-time getter
  // makes the whole class of "forgot to update the mirror" bug impossible.
  window.__getCurrentNp = () => currentZone && currentZone.now_playing;

  btnVol.addEventListener("click", (e) => {
    e.stopPropagation();
    volPop.classList.toggle("hidden");
    btnVol.setAttribute("aria-expanded", !volPop.classList.contains("hidden"));
  });
  // Long-press the speaker icon to mute (kept simple: shift-click also mutes on desktop)
  btnVol.addEventListener("dblclick", (e) => {
    e.preventDefault();
    toggleMute();
  });

  let volDebounce = null;
  volSlider.addEventListener("input", () => {
    userIsDraggingVolume = true;
    volVal.textContent = Math.round(parseFloat(volSlider.value));
    paintVolFill(volSlider);
    clearTimeout(volDebounce);
    volDebounce = setTimeout(() => setVolume(parseFloat(volSlider.value)), 90);
  });
  volSlider.addEventListener("change", () => {
    userIsDraggingVolume = false;
    setVolume(parseFloat(volSlider.value));
  });

  // Close volume popover when clicking outside it
  document.addEventListener("click", (e) => {
    if (volPop.classList.contains("hidden")) return;
    if (volPop.contains(e.target) || btnVol.contains(e.target)) return;
    volPop.classList.add("hidden");
    btnVol.setAttribute("aria-expanded", "false");
  });

  // Zone picker on the bar (Roon-style speaker button)
  async function renderBarZoneList() {
    if (!zoneList) return;
    let list = [];
    try {
      const r = await fetch("/api/zones", { cache: "no-store" });
      if (r.ok) { const j = await r.json(); if (Array.isArray(j.zones)) list = j.zones; }
    } catch (e) { /* zone list is non-critical; picker shows "No zones available" */ }
    zoneList.innerHTML = "";
    if (!list.length) {
      const empty = document.createElement("div");
      empty.className = "np-device-empty";
      empty.textContent = "No zones available";
      zoneList.appendChild(empty);
      return;
    }
    const sel = document.getElementById("zone-select");
    const cur = sel && sel.value;
    for (const z of list) {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "np-device-item" + (z.zone_id === cur ? " is-current" : "");
      item.textContent = z.display_name;
      item.addEventListener("click", (e) => {
        e.stopPropagation();
        zonePop.classList.add("hidden");
        btnZone.setAttribute("aria-expanded", "false");
        if (!sel || z.zone_id === cur) return;
        sel.value = z.zone_id;
        sel.dispatchEvent(new Event("change"));   // reuse the existing switch flow
      });
      zoneList.appendChild(item);
    }
  }
  if (btnZone && zonePop) {
    btnZone.addEventListener("click", async (e) => {
      e.stopPropagation();
      volPop.classList.add("hidden");
      btnVol.setAttribute("aria-expanded", "false");
      const willShow = zonePop.classList.contains("hidden");
      if (willShow) await renderBarZoneList();
      zonePop.classList.toggle("hidden", !willShow);
      btnZone.setAttribute("aria-expanded", String(willShow));
    });
    document.addEventListener("click", (e) => {
      if (zonePop.classList.contains("hidden")) return;
      if (zonePop.contains(e.target) || btnZone.contains(e.target)) return;
      zonePop.classList.add("hidden");
      btnZone.setAttribute("aria-expanded", "false");
    });
  }

  // Tap the info area (art + text) to open the now-playing album in the modal
  const infoArea = bar.querySelector(".mt-info");
  infoArea.addEventListener("click", () => {
    if (!currentZone || !currentZone.now_playing) return;
    if (typeof window.__openAlbum !== "function") return;
    const np = currentZone.now_playing;
    window.__openAlbum({
      title:     np.line3 || np.line1 || "",
      subtitle:  np.line2 || "",
      image_key: np.image_key
    }, { source: "now-playing", zoneId: currentZone.zone_id });
  });

  // Volume +/- buttons
  const stepMinus = document.getElementById("mt-vol-minus");
  const stepPlus  = document.getElementById("mt-vol-plus");
  function stepVolume(delta) {
    if (!currentZone) return;
    const cur = parseFloat(volSlider.value);
    const min = parseFloat(volSlider.min);
    const max = parseFloat(volSlider.max);
    const next = Math.max(min, Math.min(max, cur + delta));
    volSlider.value = next;
    volVal.textContent = Math.round(next);
    paintVolFill(volSlider);
    if (npVolSlider) { npVolSlider.value = next; paintVolFill(npVolSlider); }
    if (npVolVal) npVolVal.textContent = Math.round(next);
    setVolume(next);
  }
  if (stepMinus) stepMinus.addEventListener("click", (e) => { e.stopPropagation(); stepVolume(-2); });
  if (stepPlus)  stepPlus .addEventListener("click", (e) => { e.stopPropagation(); stepVolume(+2); });

  // Adaptive polling: progress is interpolated client-side (the 1s ticker), so
  // zone-state only needs to catch track changes and external play/pause/stop/
  // volume. Poll ~2s while actively playing, but back off to ~6s when paused or
  // stopped — nothing changes there, so the old fixed 1.5s hammered LMS for no
  // reason. A self-rescheduling timeout re-reads the interval from live state.
  let polling = false;
  function pollDelayMs() {
    const playing = currentZone && (currentZone.state === "playing" || currentZone.state === "loading");
    return playing ? 2000 : 6000;
  }
  function startPolling() {
    if (polling) return;
    polling = true;
    const loop = async () => {
      if (!polling) return;
      await fetchState();
      if (!polling) return;
      pollTimer = setTimeout(loop, pollDelayMs());
    };
    loop();
  }
  function stopPolling() {
    polling = false;
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) stopPolling();
    else startPolling();
  });

  // Refresh when zone selector changes
  const zoneSel = document.getElementById("zone-select");
  if (zoneSel) zoneSel.addEventListener("change", fetchState);

  // Boot — restore last known state instantly, then let the poll loop refresh it.
  restoreTransportState();
  startPolling();
})();

/* ------------------------------------------------------------------ */
/*  Settings info-icon toasts                                         */
/* ------------------------------------------------------------------ */
(() => {
  let toast = null;
  let dismissTimer = null;

  function getToast() {
    if (!toast) {
      toast = document.createElement("div");
      toast.className = "settings-info-toast";
      toast.setAttribute("role", "tooltip");
      document.body.appendChild(toast);
    }
    return toast;
  }

  function hideToast() {
    if (!toast) return;
    toast.classList.remove("visible");
    clearTimeout(dismissTimer);
  }

  function showToast(text) {
    const t = getToast();
    t.textContent = text;
    t.classList.add("visible");
    clearTimeout(dismissTimer);
    dismissTimer = setTimeout(hideToast, 5000);
  }

  document.addEventListener("click", (e) => {
    const btn = e.target.closest(".settings-info-btn");
    if (btn) {
      e.stopPropagation();
      showToast(btn.dataset.info || "");
      return;
    }
    hideToast();
  }, true);
})();

/* ------------------------------------------------------------------ */
/*  Share card overlay                                                 */
/* ------------------------------------------------------------------ */
(() => {
  const overlay   = document.getElementById("share-overlay");
  const frame     = document.getElementById("share-frame");
  const actions   = document.getElementById("share-actions");
  const hintEl    = document.getElementById("share-hint");
  const errEl     = document.getElementById("share-err");
  const modalBtn  = document.getElementById("modal-share-btn");

  async function ensureFont() {
    if (!document.fonts || !document.fonts.load) return;
    try {
      await Promise.all([
        document.fonts.load('700 42px Manrope'),
        document.fonts.load('400 28px Manrope'),
        document.fonts.load('700 16px Manrope'),
        document.fonts.load('400 22px Manrope')
      ]);
      await document.fonts.ready;
    } catch { /* fall back */ }
  }

  // ShareCard normally loads via the classic <script src="/sharecard.js">
  // tag in index.html. If that request was slow/dropped (e.g. a flaky
  // mobile connection to the LMS host), the bare `ShareCard` identifier
  // never gets bound and calling ShareCard.render() below throws a
  // ReferenceError. Detect that and (re)inject the script on demand.
  let shareCardLoadPromise = null;
  function ensureShareCard() {
    if (typeof ShareCard !== "undefined") return Promise.resolve();
    if (shareCardLoadPromise) return shareCardLoadPromise;
    shareCardLoadPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "/sharecard.js";
      const timer = setTimeout(() => {
        reject(new Error("Timed out loading the share-card component."));
      }, 8000);
      script.addEventListener("load", () => {
        clearTimeout(timer);
        if (typeof ShareCard === "undefined") {
          reject(new Error("Share-card component failed to initialize."));
        } else {
          resolve();
        }
      });
      script.addEventListener("error", () => {
        clearTimeout(timer);
        reject(new Error("Failed to load the share-card component."));
      });
      document.head.appendChild(script);
    }).catch((e) => {
      // Allow a future retry (e.g. next tap of Share) instead of caching
      // a permanent failure.
      shareCardLoadPromise = null;
      throw e;
    });
    return shareCardLoadPromise;
  }

  function close() {
    overlay.classList.add("hidden");
    frame.innerHTML =
      `<div class="share-placeholder"><div class="share-spinner"></div><div>Generating card…</div></div>`;
    actions.innerHTML = "";
    hintEl.textContent = "";
    errEl.textContent  = "";
  }
  overlay.addEventListener("click", (e) => {
    if (e.target.closest && e.target.closest("[data-share-close]")) close();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !overlay.classList.contains("hidden")) close();
  });

  // Public entry point — called from album modal share button + mini transport
  async function open(input) {
    const title  = input.title  || "";
    const artist = input.artist || "";
    if (!title) return;

    actions.innerHTML = "";
    hintEl.textContent = "";
    errEl.textContent  = "";
    frame.innerHTML =
      `<div class="share-placeholder"><div class="share-spinner"></div><div>Generating card…</div></div>`;
    overlay.classList.remove("hidden");

    try {
      await ensureFont();
      await ensureShareCard();

      // Best-effort release year + label + review via extras endpoint
      let releaseRaw = "";
      let labelText  = "";
      let reviewText = "";
      try {
        const params = new URLSearchParams({ title, artist });
        const r = await fetch("/api/album/extras?" + params, { cache: "no-store" });
        if (r.ok) {
          const j = await r.json();
          if (j.year) releaseRaw = j.year;
          if (j.album && j.album.year && !releaseRaw) releaseRaw = String(j.album.year);
          if (j.album && j.album.label) labelText = String(j.album.label);
          const desc = j.album && j.album.description;
          if (desc) {
            // Card height grows to fit, so show most of the review.
            // Cap generously (~10 sentences / 1400 chars) to avoid an
            // absurdly tall card from a very long Wikipedia article.
            let t = String(desc).trim();
            const sentences = t.match(/[^.!?]+[.!?]+/g);
            if (sentences && sentences.length > 10) {
              t = sentences.slice(0, 10).join(" ").trim();
            }
            if (t.length > 1400) t = t.slice(0, 1398).replace(/\s+\S*$/, "") + "…";
            reviewText = t;
          }
        }
      } catch { /* keep blank */ }

      const coverUrl = input.image_key
        ? `/api/image/${encodeURIComponent(input.image_key)}?size=1000&t=${Date.now()}`
        : "";

      const blob = await ShareCard.render({
        coverUrl,
        wordmarkUrl: null,
        title,
        artist,
        releaseRaw,
        label: labelText,
        review: reviewText
      });

      const dataUrl = await blobToDataUrl(blob);
      frame.innerHTML = `<img src="${dataUrl}" alt="Share card">`;
      buildActions(blob, title, artist);
    } catch (e) {
      frame.innerHTML = `<div class="share-placeholder">Could not generate the card.</div>`;
      errEl.textContent = (e && e.message) ? e.message : String(e);
    }
  }
  window.__openShareCard = open;

  function buildActions(blob, title, artist) {
    actions.innerHTML = "";
    const fileName =
      `${(artist || "artist").replace(/[^a-z0-9]+/gi, "_")}-` +
      `${(title  || "card"  ).replace(/[^a-z0-9]+/gi, "_")}.png`;

    const canShare = (() => {
      try {
        if (!navigator.share || !navigator.canShare) return false;
        const probe = new File([new Uint8Array([0])], "p.png", { type: "image/png" });
        return navigator.canShare({ files: [probe] });
      } catch { return false; }
    })();
    const canCopy = typeof window.ClipboardItem !== "undefined"
      && navigator.clipboard && typeof navigator.clipboard.write === "function";

    if (canCopy) {
      const b = mkBtn("ghost", icon("copy"), "Copy image");
      b.onclick = async () => {
        try {
          await navigator.clipboard.write([new window.ClipboardItem({ "image/png": blob })]);
          setLabel(b, "Copied!"); setTimeout(() => setLabel(b, "Copy image"), 2000);
        } catch (e) { errEl.textContent = e.message || String(e); }
      };
      actions.appendChild(b);
    }
    if (canShare) {
      const b = mkBtn("primary", icon("share"), "Share…");
      b.onclick = async () => {
        try {
          const file = new File([blob], fileName, { type: "image/png" });
          await navigator.share({ files: [file] });
        } catch (e) { if (e && e.name !== "AbortError") errEl.textContent = e.message || String(e); }
      };
      actions.appendChild(b);
    }
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = fileName;
    a.appendChild(document.createTextNode(""));
    a.innerHTML = `${icon("download")}<span>Download</span>`;
    actions.appendChild(a);

    hintEl.textContent = (canCopy || canShare)
      ? "Tap a button above, or long-press the card to save."
      : "Long-press the card to save, or tap Download.";
  }

  function blobToDataUrl(blob) {
    return new Promise((res, rej) => {
      const r = new FileReader();
      r.onload  = () => res(r.result);
      r.onerror = () => rej(new Error("read failed"));
      r.readAsDataURL(blob);
    });
  }
  function mkBtn(cls, iconSvg, label) {
    const b = document.createElement("button");
    b.className = cls;
    b.type = "button";
    b.innerHTML = `${iconSvg}<span>${label}</span>`;
    return b;
  }
  function setLabel(btn, text) {
    const s = btn.querySelector("span");
    if (s) s.textContent = text;
  }
  function icon(name) {
    const I = {
      share:    '<polyline points="16 6 12 2 8 6"/><line x1="12" y1="2" x2="12" y2="15"/><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/>',
      copy:     '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
      download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>'
    };
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${I[name] || ""}</svg>`;
  }

  // Wire the share button inside the album modal
  if (modalBtn) {
    modalBtn.addEventListener("click", () => {
      // On the now-playing screen read the live zone state directly via
      // window.__getCurrentNp() (not a mirrored global) so the card always
      // reflects the current track, not the album that was playing when the
      // modal first opened, regardless of which modal tab is active.
      const npModal = document.getElementById("album-modal");
      const isNp = npModal && npModal.classList.contains("np-mode");
      const np = isNp && window.__getCurrentNp && window.__getCurrentNp();
      if (np) {
        open({ title: np.line3 || "", artist: np.line2 || "", image_key: np.image_key });
        return;
      }
      const a = window.__currentAlbum;
      if (!a) return;
      open({ title: a.title || "", artist: a.subtitle || "", image_key: a.image_key });
    });
  }
})();

/* ------------------------------------------------------------------ */
/*  Self-update: poll status, show a toast, install on tap            */
/* ------------------------------------------------------------------ */
(function initUpdater() {
  const toast    = document.getElementById("update-toast");
  const textEl   = document.getElementById("update-text");
  const actions  = document.getElementById("update-actions");
  const btnNow   = document.getElementById("update-now");
  const btnLater = document.getElementById("update-later");
  const notesEl  = document.getElementById("update-notes");
  if (!toast || !btnNow) return;

  const PHASE = {
    checking:   "Preparing\u2026",
    downloading:"Downloading\u2026",
    extracting: "Unpacking\u2026",
    restarting: "Restarting\u2026"
  };
  const DISMISS_KEY = "rra-update-dismissed";
  let applying = false;
  let pollTimer = null;

  const dismissedVer = () => { try { return sessionStorage.getItem(DISMISS_KEY) || ""; } catch (e) { return ""; } };
  const setDismissed = (v) => { try { sessionStorage.setItem(DISMISS_KEY, v); } catch (e) {} };
  const show = (msg) => { textEl.textContent = msg; toast.classList.add("open"); };
  const hide = () => { toast.classList.remove("open"); if (notesEl) notesEl.classList.add("hidden"); };

  function showNotes(notes) {
    if (!notesEl || !notes) { if (notesEl) notesEl.classList.add("hidden"); return; }
    notesEl.textContent = notes;
    notesEl.classList.remove("hidden");
  }

  function showProgress(phase) {
    applying = true;
    actions.classList.add("busy");
    toast.classList.remove("is-error");
    if (notesEl) notesEl.classList.add("hidden");
    show(PHASE[phase] || "Updating\u2026");
  }

  async function check() {
    if (applying) return;
    try {
      const r = await fetch("/api/update/status", { cache: "no-store" });
      if (!r.ok) return;
      const s = await r.json();
      const ph = s.apply && s.apply.phase;
      if (ph === "downloading" || ph === "extracting" || ph === "restarting") {
        showProgress(ph); startPoll(s.latest); return;
      }
      if (s.available && s.latest && s.latest !== dismissedVer()) {
        actions.classList.remove("busy"); btnNow.disabled = false;
        toast.classList.remove("is-error");
        btnNow.classList.remove("hidden");
        const label = s.isDowngrade ? "Rollback to v" : "v";
        show((label) + s.latest + " available (you have v" + s.current + ")");
        showNotes(s.notes);
        btnNow.querySelector("span").textContent = s.isDowngrade ? "Roll back" : "Update";
      } else if (!applying) {
        hide();
      }
    } catch (e) { /* offline; try again next tick */ }
  }

  function startPoll(targetVer) {
    if (pollTimer) clearInterval(pollTimer);
    let wasDown = false;
    pollTimer = setInterval(async () => {
      try {
        const r = await fetch("/api/update/status", { cache: "no-store" });
        if (!r.ok) throw new Error("bad");
        const s = await r.json();
        if (wasDown && ((targetVer && s.current === targetVer) || !s.available)) {
          clearInterval(pollTimer); location.reload(); return;
        }
        const ph = s.apply && s.apply.phase;
        if (ph === "error") {
          clearInterval(pollTimer); applying = false;
          actions.classList.remove("busy"); btnNow.disabled = false;
          toast.classList.add("is-error");
          show("Update failed: " + ((s.apply && s.apply.error) || "unknown") + ". Tap Update to retry.");
          return;
        }
        if (PHASE[ph]) show(PHASE[ph]);
      } catch (e) {
        wasDown = true;                 // server is restarting
        show(PHASE.restarting);
      }
    }, 1500);
    setTimeout(() => {
      if (pollTimer && applying) {
        clearInterval(pollTimer);
        show("Update is taking a while \u2014 if the app doesn't come back on its own, restart the extension to finish.");
      }
    }, 180000);
  }

  btnNow.addEventListener("click", async () => {
    if (applying) return;
    btnNow.disabled = true;
    showProgress("checking");
    try {
      const r = await fetch("/api/update/apply", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
      const s = await r.json().catch(() => null);
      if (!r.ok) {
        applying = false; actions.classList.remove("busy"); btnNow.disabled = false;
        toast.classList.add("is-error");
        show("Couldn't start update: " + ((s && s.error) || ("HTTP " + r.status)));
        return;
      }
      startPoll(s && s.status && s.status.latest);
    } catch (e) {
      startPoll(null);                  // request cut off by restart — keep polling
    }
  });

  btnLater.addEventListener("click", async () => {
    try {
      const r = await fetch("/api/update/status", { cache: "no-store" });
      const s = await r.json();
      if (s && s.latest) setDismissed(s.latest);
    } catch (e) {} // network error dismissing update — banner stays hidden, safe to ignore
    hide();
  });

  // Settings' "Check for updates" flow hands off here after its own check:
  // applying through the banner keeps a single implementation of the
  // download/unpack/restart progress UI (the banner sits behind the Settings
  // sheet, so the caller closes Settings first). Clearing the "Later"
  // dismissal lets the banner's error/retry states show normally afterwards.
  window.__applyUpdateNow = () => { setDismissed(""); btnNow.click(); };

  check();
  setInterval(check, 15 * 60 * 1000);
})();

/* ------------------------------------------------------------------ */
/*  Settings sheet: theme toggle (lives here now), version, repo link  */
/* ------------------------------------------------------------------ */
(function initSettings() {
  const openBtn    = document.getElementById("settings-toggle");
  const overlay    = document.getElementById("settings-overlay");
  const versionEl  = document.getElementById("settings-version");

  const zoneSelect  = document.getElementById("zone-select");
  const labelOrderSelect = document.getElementById("label-order-select");
  const labelMinSelect   = document.getElementById("label-min-select");
  if (!openBtn || !overlay) return;

  // Label album order (alphabetical default). Persisted in localStorage and
  // read by the labels browser when it loads a label's albums.
  if (labelOrderSelect) {
    labelOrderSelect.value =
      localStorage.getItem("rra-label-order") === "random" ? "random" : "alpha";
    labelOrderSelect.addEventListener("change", () => {
      const v = labelOrderSelect.value === "random" ? "random" : "alpha";
      localStorage.setItem("rra-label-order", v);
    });
  }

  // Minimum albums per label — hides one-off outliers from the labels grid.
  if (labelMinSelect) {
    const stored = localStorage.getItem("rra-label-min");
    labelMinSelect.value = (stored === "1" || stored === "5" || stored === "10") ? stored : "2";
    labelMinSelect.addEventListener("change", () => {
      localStorage.setItem("rra-label-min", labelMinSelect.value);
    });
  }

  // Theme picker — four themes (dark/light x classic/copper). The list comes
  // from window.__themes so it can't drift from the CSS token blocks. Each row
  // shows a two-tone swatch rendered by momentarily resolving that theme's
  // tokens, so the preview can never disagree with the real stylesheet.
  // Appearance -> Show sample rate on artwork. A pure class flip on <body>:
  // every tile already carries its badge, so nothing is refetched or rebuilt.
  {
    const qt = document.getElementById("quality-toggle");
    if (qt) {
      qt.checked = !!(window.__showQuality && window.__showQuality());
      qt.addEventListener("change", () => {
        if (window.__setShowQuality) window.__setShowQuality(qt.checked);
      });
    }
  }

  const themePicker = document.getElementById("theme-picker");
  function swatchFor(t) {
    const probe = document.createElement("div");
    probe.dataset.theme = t.theme;
    probe.dataset.palette = t.palette;
    probe.style.display = "none";
    document.body.appendChild(probe);
    const cs = getComputedStyle(probe);
    const bg = cs.getPropertyValue("--bg").trim() || "#000";
    const ac = cs.getPropertyValue("--accent").trim() || "#888";
    probe.remove();
    return { bg, ac };
  }
  function renderThemePicker() {
    if (!themePicker || !window.__themes) return;
    themePicker.innerHTML = "";
    for (const t of window.__themes) {
      const on = window.__currentTheme === t.id;
      const row = document.createElement("button");
      row.type = "button";
      row.className = "lib-sort-row" + (on ? " is-on" : "");
      row.setAttribute("role", "radio");
      row.setAttribute("aria-checked", on ? "true" : "false");

      const sw = document.createElement("span");
      sw.className = "theme-swatch";
      const { bg, ac } = swatchFor(t);
      const a = document.createElement("i"), b = document.createElement("i");
      a.style.background = bg; b.style.background = ac;
      sw.appendChild(a); sw.appendChild(b);

      const label = document.createElement("span");
      label.className = "lib-sort-label";
      label.textContent = t.label;

      const tick = document.createElement("span");
      tick.className = "lib-sort-arrow";
      tick.textContent = on ? "✓" : "";

      row.appendChild(sw); row.appendChild(label); row.appendChild(tick);
      row.addEventListener("click", () => {
        if (window.__applyTheme) window.__applyTheme(t.id);
        renderThemePicker();
      });
      themePicker.appendChild(row);
    }
  }
  renderThemePicker();
  // Keep the picker honest if the topbar toggle flips the theme behind its back.
  document.addEventListener("themechange", renderThemePicker);

  // Don't Stop The Music is configured in Settings → Player settings (per
  // player, LMS-backed) — deliberately NOT duplicated on this pane.

  let versionLoaded = false;
  async function loadVersion() {
    if (versionLoaded || !versionEl) return;
    try {
      const r = await fetch("/api/update/status", { cache: "no-store" });
      if (r.ok) {
        const s = await r.json();
        if (s && s.current) {
          const parts = (s.current || "").split(".");
          versionEl.textContent = parts.length >= 3
            ? "MusicD Remote v" + parts[0] + "." + parts[1] + " (Build " + parts[2] + ")"
            : "MusicD Remote v" + s.current;
          versionLoaded = true;
        }
      }
    } catch (e) {} // network error loading version — settings panel shows without version, non-critical
  }

  const forceRescanBtn    = document.getElementById("force-rescan-btn");
  const forceRescanStatus = document.getElementById("force-rescan-status");
  if (forceRescanBtn) {
    forceRescanBtn.addEventListener("click", async () => {
      if (forceRescanBtn.disabled) return;
      forceRescanBtn.disabled = true;
      forceRescanBtn.textContent = "Starting…";
      if (forceRescanStatus) forceRescanStatus.classList.add("hidden");
      try {
        const r = await fetch("/api/labels/rescan-force", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
        const j = await r.json();
        if (!r.ok) throw new Error(j.error || "HTTP " + r.status);
        forceRescanBtn.textContent = "Rescan started";
        if (forceRescanStatus) { forceRescanStatus.textContent = "Full rescan started — this may take several minutes. Label data will update as results come in."; forceRescanStatus.classList.remove("hidden"); }
        setTimeout(() => {
          forceRescanBtn.disabled = false;
          forceRescanBtn.textContent = "Force rescan";
        }, 5000);
      } catch (e) {
        forceRescanBtn.disabled = false;
        forceRescanBtn.textContent = "Force rescan";
        if (forceRescanStatus) { forceRescanStatus.textContent = "Error: " + e.message; forceRescanStatus.classList.remove("hidden"); }
      }
    });
  }

  const discogsTokenInput  = document.getElementById("discogs-token-input");
  const discogsTokenSave   = document.getElementById("discogs-token-save");
  const discogsTokenStatus = document.getElementById("discogs-token-status");

  async function loadDiscogsToken() {
    try {
      const r = await fetch("/api/settings/discogs-token");
      const j = await r.json();
      if (discogsTokenStatus) {
        discogsTokenStatus.textContent = j.set ? ("Current: " + j.masked) : "Not set";
      }
    } catch (_) { /* display-only status — if the fetch fails, silence is fine; status just stays stale */ }
  }

  if (discogsTokenSave) {
    discogsTokenSave.addEventListener("click", async () => {
      const token = discogsTokenInput ? discogsTokenInput.value.trim() : "";
      if (!token) return;
      discogsTokenSave.disabled = true;
      try {
        const r = await fetch("/api/settings/discogs-token", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token })
        });
        const j = await r.json();
        if (j.ok) {
          if (discogsTokenInput) discogsTokenInput.value = "";
          showToast(j.saved === false ? "Token set but file write failed — won't persist after restart" : "Discogs token saved", j.saved === false ? "error" : "ok");
          loadDiscogsToken();
        } else {
          showToast(j.error || "Failed to save token", "error");
        }
      } catch (e) {
        showToast("Failed: " + e.message, "error");
      } finally {
        discogsTokenSave.disabled = false;
      }
    });
  }

  const fanartKeyInput  = document.getElementById("fanart-key-input");
  const fanartKeySave   = document.getElementById("fanart-key-save");
  const fanartKeyStatus = document.getElementById("fanart-key-status");

  async function loadFanartKey() {
    try {
      const r = await fetch("/api/settings/fanart-key");
      const j = await r.json();
      if (fanartKeyStatus) {
        fanartKeyStatus.textContent = j.set ? ("Current: " + j.masked) : "Not set";
      }
    } catch (_) { /* display-only status — if the fetch fails, silence is fine; status just stays stale */ }
  }

  if (fanartKeySave) {
    fanartKeySave.addEventListener("click", async () => {
      const key = fanartKeyInput ? fanartKeyInput.value.trim() : "";
      if (!key) return;
      fanartKeySave.disabled = true;
      try {
        const r = await fetch("/api/settings/fanart-key", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ key })
        });
        const j = await r.json();
        if (j.ok) {
          if (fanartKeyInput) fanartKeyInput.value = "";
          showToast(j.saved === false ? "Key set but file write failed — won't persist after restart" : "FanArt.tv key saved", j.saved === false ? "error" : "ok");
          loadFanartKey();
        } else {
          showToast(j.error || "Failed to save key", "error");
        }
      } catch (e) {
        showToast("Failed: " + e.message, "error");
      } finally {
        fanartKeySave.disabled = false;
      }
    });
  }

  // ----- Wall display (/display): toggle + rotation interval + YouTube key -----
  const displayToggle    = document.getElementById("display-toggle");
  const displaySeconds   = document.getElementById("display-seconds");
  const displaySecsValue = document.getElementById("display-seconds-value");
  const youtubeKeyInput  = document.getElementById("youtube-key-input");
  const youtubeKeySave   = document.getElementById("youtube-key-save");
  const youtubeKeyStatus = document.getElementById("youtube-key-status");

  async function loadDisplaySettings() {
    try {
      const r = await fetch("/api/settings/display");
      const j = await r.json();
      if (displayToggle) displayToggle.checked = !!j.enabled;
      if (displaySeconds && Number.isFinite(parseInt(j.seconds, 10))) {
        displaySeconds.value = j.seconds;
        if (displaySecsValue) displaySecsValue.textContent = j.seconds + "s";
      }
    } catch (_) { /* display-only status — if the fetch fails, the sheet just shows defaults */ }
    try {
      const r = await fetch("/api/settings/youtube-key");
      const j = await r.json();
      if (youtubeKeyStatus) youtubeKeyStatus.textContent = j.set ? ("Current: " + j.masked) : "Not set (video slides off)";
    } catch (_) { /* same — status stays stale */ }
  }

  async function saveDisplaySettings() {
    try {
      const r = await fetch("/api/settings/display", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: displayToggle ? displayToggle.checked : false,
          seconds: displaySeconds ? parseInt(displaySeconds.value, 10) : 10
        })
      });
      const j = await r.json();
      if (!j.ok) showToast("Display settings didn't persist — check the data volume", "error");
    } catch (e) {
      showToast("Failed: " + e.message, "error");
    }
  }

  if (displayToggle) displayToggle.addEventListener("change", saveDisplaySettings);
  if (displaySeconds) {
    // Live value while dragging; persist on release.
    displaySeconds.addEventListener("input", () => {
      if (displaySecsValue) displaySecsValue.textContent = displaySeconds.value + "s";
    });
    displaySeconds.addEventListener("change", saveDisplaySettings);
  }
  if (youtubeKeySave) {
    youtubeKeySave.addEventListener("click", async () => {
      const key = youtubeKeyInput ? youtubeKeyInput.value.trim() : "";
      if (!key) return;
      youtubeKeySave.disabled = true;
      try {
        const r = await fetch("/api/settings/youtube-key", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ key })
        });
        const j = await r.json();
        if (j.ok) {
          if (youtubeKeyInput) youtubeKeyInput.value = "";
          showToast("YouTube key saved", "ok");
          loadDisplaySettings();
        } else {
          showToast(j.error || "Failed to save key", "error");
        }
      } catch (e) {
        showToast("Failed: " + e.message, "error");
      } finally {
        youtubeKeySave.disabled = false;
      }
    });
  }

  const lfdInput  = document.getElementById("label-folder-depth-input");
  const lfdSave   = document.getElementById("label-folder-depth-save");
  const lfdStatus = document.getElementById("label-folder-depth-status");

  async function loadLabelFolderDepth() {
    try {
      const r = await fetch("/api/settings/label-folder-depth");
      const j = await r.json();
      if (lfdInput && document.activeElement !== lfdInput) lfdInput.value = j.depth || 0;
      if (lfdStatus) lfdStatus.textContent = j.depth ? ("Using folder depth " + j.depth) : "Off — using file label tags";
    } catch (_) { /* display-only status — stale on failure is fine */ }
  }

  if (lfdSave) {
    lfdSave.addEventListener("click", async () => {
      const depth = parseInt(lfdInput ? lfdInput.value : "0", 10) || 0;
      lfdSave.disabled = true;
      try {
        const r = await fetch("/api/settings/label-folder-depth", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ depth })
        });
        const j = await r.json();
        if (j.ok) {
          showToast(j.rescanning ? "Saved — re-scanning labels…" : "Saved", "ok");
          loadLabelFolderDepth();
        } else {
          showToast(j.error || "Failed to save", "error");
        }
      } catch (e) {
        showToast("Failed: " + e.message, "error");
      } finally {
        lfdSave.disabled = false;
      }
    });
  }


  // Settings is a two-level view: a category home list and one pane per
  // category. Only one .settings-view is visible at a time. The controls and
  // their IDs are unchanged — they just live inside panes now — so all the
  // load*/save* wiring above still resolves against the same elements.
  const sheet = overlay.querySelector(".settings-sheet");
  const views = sheet ? sheet.querySelectorAll(".settings-view") : [];
  const showView = (name) => {
    let matched = false;
    views.forEach(v => {
      const isHome = v.getAttribute("data-view") === "home";
      const key    = isHome ? "home" : v.getAttribute("data-pane");
      const on     = key === name;
      v.classList.toggle("hidden", !on);
      if (on) matched = true;
    });
    // Fall back to home if an unknown pane was requested.
    if (!matched) views.forEach(v => v.classList.toggle("hidden", v.getAttribute("data-view") !== "home"));
    // Each level starts scrolled to the top, like a pushed page.
    if (sheet) sheet.scrollTop = 0;
  };
  const atHome = () => {
    const home = sheet && sheet.querySelector('.settings-view[data-view="home"]');
    return !home || !home.classList.contains("hidden");
  };

  if (sheet) {
    sheet.addEventListener("click", (e) => {
      const nav = e.target.closest(".settings-nav-item");
      if (nav) {
        const pane = nav.getAttribute("data-pane");
        showView(pane);
        if (pane === "lms") loadLmsPane();
        if (pane === "player") loadPlayerPane();
        return;
      }
      if (e.target.closest("[data-settings-back]")) { showView("home"); return; }
    });
  }

  /* ---- Player settings pane (native per-player LMS settings) ------------ */
  const psPlayer = document.getElementById("ps-player");
  const psModel  = document.getElementById("ps-model");
  const psBody   = document.getElementById("ps-body");
  const psStat   = document.getElementById("ps-status");
  let psCurrent  = null;
  let psStatTimer = null;

  function psStatus(msg, isError) {
    if (!psStat) return;
    psStat.textContent = msg;
    psStat.style.color = isError ? "var(--danger)" : "";
    clearTimeout(psStatTimer);
    if (!isError) psStatTimer = setTimeout(() => { psStat.textContent = ""; }, 2500);
  }
  async function psPost(url, body, okMsg) {
    try {
      const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || `HTTP ${r.status}`); }
      psStatus(okMsg || "Saved ✓");
      return true;
    } catch (e) { psStatus("Couldn't save: " + e.message, true); return false; }
  }
  const psSavePref = (name, value) =>
    psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/pref/${encodeURIComponent(name)}`, { value });

  // Row builders following the pane's existing markup patterns.
  function psRowToggle(label, info, checked, onChange) {
    const row = document.createElement("div");
    row.className = "settings-row";
    row.innerHTML =
      `<span class="settings-label">${label} ${info ? `<button class="settings-info-btn" data-info="${info.replace(/"/g, "&quot;")}" aria-label="Info">ⓘ</button>` : ""}</span>` +
      `<label class="switch"><input type="checkbox"><span class="switch-track"><span class="switch-thumb"></span></span></label>`;
    const input = row.querySelector("input");
    input.checked = !!checked;
    input.addEventListener("change", () => onChange(input.checked, input));
    return row;
  }
  function psRowSelect(label, info, options, value, onChange) {
    const row = document.createElement("div");
    row.className = "settings-row";
    row.innerHTML =
      `<span class="settings-label">${label} ${info ? `<button class="settings-info-btn" data-info="${info.replace(/"/g, "&quot;")}" aria-label="Info">ⓘ</button>` : ""}</span>` +
      `<div class="settings-select-wrap"><select class="settings-select"></select>` +
      `<svg class="settings-caret" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg></div>`;
    const sel = row.querySelector("select");
    for (const [v, text] of options) {
      const o = document.createElement("option");
      o.value = String(v); o.textContent = text;
      sel.appendChild(o);
    }
    sel.value = String(value);
    if (sel.value !== String(value)) { // current value outside the list — show it raw
      const o = document.createElement("option");
      o.value = String(value); o.textContent = String(value);
      sel.appendChild(o); sel.value = String(value);
    }
    sel.addEventListener("change", () => onChange(sel.value, sel));
    return row;
  }
  function psRowNumber(label, info, value, min, max, step, onChange) {
    const row = document.createElement("div");
    row.className = "settings-row";
    row.innerHTML =
      `<span class="settings-label">${label} ${info ? `<button class="settings-info-btn" data-info="${info.replace(/"/g, "&quot;")}" aria-label="Info">ⓘ</button>` : ""}</span>` +
      `<input type="number" class="settings-token-input ps-num" min="${min}" max="${max}" step="${step}">`;
    const input = row.querySelector("input");
    input.value = value != null ? String(value) : "";
    input.addEventListener("change", () => onChange(input.value, input));
    return row;
  }
  function psBlock(...rows) {
    const b = document.createElement("div");
    b.className = "settings-block";
    for (const r of rows) if (r) b.appendChild(r);
    return b.children.length ? b : null;
  }

  async function loadPlayerPane() {
    if (!psPlayer) return;
    try {
      const r = await fetch("/api/zones");
      const j = await r.json();
      const zones = (j.zones || []);
      psPlayer.innerHTML = "";
      for (const z of zones) {
        const o = document.createElement("option");
        o.value = z.zone_id; o.textContent = z.display_name;
        psPlayer.appendChild(o);
      }
      if (!zones.length) {
        psBody.innerHTML = '<div class="settings-sub">No players found.</div>';
        return;
      }
      const keep = psCurrent && zones.some(z => z.zone_id === psCurrent) ? psCurrent : zones[0].zone_id;
      psPlayer.value = keep;
      await loadPlayerSettings(keep);
    } catch (e) {
      psBody.innerHTML = '<div class="settings-sub"></div>';
      psBody.firstChild.textContent = "Couldn't load players: " + e.message;
    }
  }
  if (psPlayer) psPlayer.addEventListener("change", () => loadPlayerSettings(psPlayer.value));

  async function loadPlayerSettings(id) {
    psCurrent = id;
    psModel.textContent = "";
    psBody.innerHTML = '<div class="settings-sub">Loading…</div>';
    try {
      const r = await fetch(`/api/lms/player/${encodeURIComponent(id)}/settings`);
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      if (psCurrent !== id) return;   // switched players while loading
      renderPlayerSettings(j);
    } catch (e) {
      if (psCurrent === id) {
        psBody.innerHTML = '<div class="settings-sub"></div>';
        psBody.firstChild.textContent = "Couldn't load settings: " + e.message;
      }
    }
  }

  function renderPlayerSettings(j) {
    const p = j.prefs || {};
    const has = (k) => p[k] !== null && p[k] !== undefined;
    psModel.textContent = j.model ? j.model : "";
    psBody.innerHTML = "";
    const frag = document.createDocumentFragment();
    const divider = () => { const d = document.createElement("div"); d.className = "settings-divider"; return d; };
    const append = (block) => { if (block) { if (frag.children.length) frag.appendChild(divider()); frag.appendChild(block); } };

    // Identity: rename + power
    {
      const b = document.createElement("div");
      b.className = "settings-block";
      b.innerHTML =
        `<div class="settings-row"><span class="settings-label">Player name <button class="settings-info-btn" data-info="The name this player shows everywhere — here, in LMS and to other controllers." aria-label="Info">ⓘ</button></span></div>` +
        `<div class="settings-token-row"><input id="ps-name" type="text" class="settings-token-input" autocomplete="off" spellcheck="false">` +
        `<button id="ps-name-save" class="settings-update-btn" type="button">Save</button></div>`;
      b.querySelector("#ps-name").value = j.name || "";
      b.querySelector("#ps-name-save").addEventListener("click", async () => {
        const name = b.querySelector("#ps-name").value.trim();
        if (!name) return;
        if (await psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/name`, { name }, "Renamed ✓")) {
          const opt = psPlayer.querySelector(`option[value="${CSS.escape(psCurrent)}"]`);
          if (opt) opt.textContent = name;
        }
      });
      b.appendChild(psRowToggle("Power", "Soft power for this player.", j.power, (on) =>
        psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/power`, { on }, on ? "Powered on" : "Powered off")));
      append(b);
    }

    // Playback modes + Don't Stop The Music (LMS's own keep-playing feature)
    const dstm = j.dstm && j.dstm.options && j.dstm.options.length ? j.dstm : null;
    append(psBlock(
      j.modes && j.modes.shuffle != null ? psRowSelect("Shuffle", "Shuffle mode for this player's queue.",
        [[0, "Off"], [1, "By song"], [2, "By album"]], j.modes.shuffle,
        (v) => psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/mode`, { shuffle: v })) : null,
      j.modes && j.modes.repeat != null ? psRowSelect("Repeat", "Repeat mode for this player's queue.",
        [[0, "Off"], [1, "One song"], [2, "All"]], j.modes.repeat,
        (v) => psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/mode`, { repeat: v })) : null,
      dstm ? psRowSelect("Don't Stop The Music", "When the queue runs out, LMS keeps playing using the selected mix (Random Album, Random Artist, …). This is LMS's built-in feature — it replaces the old app-side Random album radio.",
        dstm.options.map(o => [o.key, o.text]), dstm.current,
        (v) => psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/dstm`, { provider: v })) : null
    ));

    // Audio
    append(psBlock(
      has("transitionType") ? psRowSelect("Crossfade", "How one track blends into the next.",
        [[0, "None"], [1, "Crossfade"], [2, "Fade in"], [3, "Fade out"], [4, "Fade in & out"]],
        p.transitionType, (v) => psSavePref("transitionType", v)) : null,
      has("transitionDuration") ? psRowNumber("Crossfade seconds", "Length of the crossfade/fade (0–10 s).",
        p.transitionDuration, 0, 10, 1, (v) => psSavePref("transitionDuration", v)) : null,
      has("transitionSmart") ? psRowToggle("Smart crossfade", "Skip the crossfade between consecutive tracks of the same album (gapless stays gapless).",
        p.transitionSmart === "1" || p.transitionSmart === 1, (on) => psSavePref("transitionSmart", on ? "1" : "0")) : null,
      has("replayGainMode") ? psRowSelect("Volume levelling", "Use ReplayGain tags to even out loudness between tracks/albums.",
        [[0, "Off"], [1, "Track gain"], [2, "Album gain"], [3, "Smart gain"]],
        p.replayGainMode, (v) => psSavePref("replayGainMode", v)) : null,
      has("remoteReplayGain") ? psRowNumber("Remote stream gain (dB)", "Fixed gain applied to remote/streamed tracks that have no ReplayGain tags.",
        p.remoteReplayGain, -20, 20, 1, (v) => psSavePref("remoteReplayGain", v)) : null,
      // LMS's own Volume Control setting, with its exact two options.
      has("digitalVolumeControl") ? psRowSelect("Volume control", "Fix the output at 100% if your amplifier/DAC controls loudness or you need perfect digital passthrough. Affects both digital and analog volume.",
        [["0", "Output level is fixed at 100%"], ["1", "Volume controls adjust outputs"]],
        p.digitalVolumeControl, (v) => psSavePref("digitalVolumeControl", v)) : null
    ));

    // Power behaviour
    append(psBlock(
      has("powerOnResume") ? psRowSelect("On power on", "What playback does when the player is switched off and back on.",
        [["PauseOff-PlayOn",     "Pause when off · resume when on"],
         ["PauseOff-NoneOn",     "Pause when off · stay paused"],
         ["StopOff-PlayOn",      "Stop when off · play when on"],
         ["StopOff-NoneOn",      "Stop when off · do nothing"],
         ["StopOff-ResetPlayOn", "Stop & reset · play when on"],
         ["StopOff-ResetOn",     "Stop & reset · do nothing"]],
        p.powerOnResume, (v) => psSavePref("powerOnResume", v)) : null,
      has("fadeInDuration") ? psRowNumber("Fade in on play (s)", "Volume ramp when playback starts or resumes.",
        p.fadeInDuration, 0, 30, 1, (v) => psSavePref("fadeInDuration", v)) : null
    ));

    // Alarms
    append(psBlock(
      has("alarmsEnabled") ? psRowToggle("Alarms enabled", "Master switch for all of this player's alarms (set the alarms themselves in LMS).",
        p.alarmsEnabled === "1" || p.alarmsEnabled === 1, (on) => psSavePref("alarmsEnabled", on ? "1" : "0")) : null,
      has("alarmDefaultVolume") ? psRowNumber("Alarm volume", "Default volume for alarms (0–100).",
        p.alarmDefaultVolume, 0, 100, 1, (v) => psSavePref("alarmDefaultVolume", v)) : null
    ));

    // Sync group
    {
      const sync = j.sync || { members: [], others: [] };
      const rows = [];
      if (sync.others.length) {
        rows.push(psRowSelect("Sync with", "Group this player with another for synchronous playback everywhere.",
          [["", "Not synced"], ...sync.others.map(o => [o.id, o.name])],
          sync.members[0] || "",
          async (v) => {
            if (await psPost(`/api/lms/player/${encodeURIComponent(psCurrent)}/sync`, { with: v || null }, v ? "Synced ✓" : "Unsynced ✓")) {
              loadPlayerSettings(psCurrent);   // group membership changed — refresh
            }
          }));
      }
      if (has("syncVolume")) rows.push(psRowToggle("Sync volume", "Volume changes apply to the whole sync group.",
        p.syncVolume === "1" || p.syncVolume === 1, (on) => psSavePref("syncVolume", on ? "1" : "0")));
      if (has("syncPower")) rows.push(psRowToggle("Sync power", "Power state follows the sync group.",
        p.syncPower === "1" || p.syncPower === 1, (on) => psSavePref("syncPower", on ? "1" : "0")));
      if (has("maintainSync")) rows.push(psRowToggle("Maintain sync", "Continuously correct timing drift within the group.",
        p.maintainSync === "1" || p.maintainSync === 1, (on) => psSavePref("maintainSync", on ? "1" : "0")));
      append(psBlock(...rows));
    }

    // Network / streaming
    append(psBlock(
      has("maxBitrate") ? psRowSelect("Bitrate limit", "Cap this player's stream bitrate (transcodes on the fly). Useful for remote/slow links.",
        [[0, "No limit"], [64, "64 kbps"], [96, "96 kbps"], [128, "128 kbps"], [160, "160 kbps"], [192, "192 kbps"], [256, "256 kbps"], [320, "320 kbps"]],
        p.maxBitrate, (v) => psSavePref("maxBitrate", v)) : null
    ));

    psBody.appendChild(frag);
  }

  /* ---- LMS server pane: embedded server settings + rescan actions ------- */
  const lmsPane = {
    status:  document.getElementById("lms-conn-status"),
    note:    document.getElementById("lms-settings-note"),
    open:    document.getElementById("lms-open-settings"),
    rescanMode:   document.getElementById("lms-rescan-mode"),
    rescanGo:     document.getElementById("lms-rescan-go"),
    rescanStatus: document.getElementById("lms-rescan-status"),
    overlay: document.getElementById("lmsset-overlay"),
    frame:   document.getElementById("lmsset-frame"),
    close:   document.getElementById("lmsset-close"),
    newtab:  document.getElementById("lmsset-newtab")
  };
  let lmsSettingsUrl = null;

  async function loadLmsPane() {
    if (!lmsPane.status) return;
    lmsPane.status.textContent = "…";
    try {
      const r = await fetch("/api/lms/settings-info");
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      // Same-origin path: the app reverse-proxies the LMS settings pages
      // (and patches their theme CSS variables), so this works regardless of
      // how the browser can reach LMS, and over HTTPS too.
      lmsSettingsUrl = j.settings_path;
      lmsPane.status.textContent = j.host + ":" + j.port + (j.scanning ? " · scanning…" : " · connected");
      lmsPane.note.textContent = j.material
        ? "Material Skin detected — opens Material's styled settings pages."
        : "Opens Lyrion's classic settings pages. Install the Material Skin plugin on LMS for its styled version.";
      lmsPane.open.disabled = false;
    } catch (e) {
      lmsPane.status.textContent = "Not connected";
      lmsPane.note.textContent = e.message;
      lmsPane.open.disabled = true;
    }
  }

  function closeLmsFrame() {
    if (!lmsPane.overlay) return;
    lmsPane.overlay.classList.add("hidden");
    lmsPane.frame.src = "about:blank";   // stop the page, drop its polling
    document.body.style.overflow = "";
  }
  if (lmsPane.open) lmsPane.open.addEventListener("click", () => {
    if (!lmsSettingsUrl) return;
    lmsPane.newtab.href = lmsSettingsUrl;
    lmsPane.frame.src = lmsSettingsUrl;
    lmsPane.overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
  });
  if (lmsPane.close) lmsPane.close.addEventListener("click", closeLmsFrame);

  // Topbar / side-menu "Browse Qobuz on your server" → open the LMS Material
  // skin in the same embedded frame. Browsing the catalogue and adding albums
  // happens through the server's own Qobuz/Tidal plugin (this replaced the
  // app's former in-app Qobuz tab). Needs the Material Skin plugin on LMS.
  const serverBrowseBtn = document.getElementById("server-browse-toggle");
  if (serverBrowseBtn) {
    // Native Qobuz browser (walks the LMS Qobuz plugin's menu in the app's own
    // UI). Replaces the old Material-frame deep-link — Material can't jump to the
    // Qobuz app anyway (no app deep-link param), so we navigate the menu here.
    serverBrowseBtn.addEventListener("click", () => {
      if (window.__openQobuzBrowse) window.__openQobuzBrowse();
    });
  }

  // Rescan actions. After LMS finishes, /api/reindex refreshes this app's own
  // album index so new music shows up without waiting for the 12h staleness.
  let lmsScanPoll = null;
  // Shared with the side-menu rescan shortcut so both paths reindex on finish.
  window.__watchLmsScan = () => watchLmsScan();
  function watchLmsScan() {
    if (lmsScanPoll) clearInterval(lmsScanPoll);
    let polls = 0;
    lmsScanPoll = setInterval(async () => {
      if (++polls > 150) { clearInterval(lmsScanPoll); lmsScanPoll = null; return; }
      try {
        const j = await (await fetch("/api/lms/settings-info")).json();
        if (!j.scanning && polls >= 2) {
          clearInterval(lmsScanPoll); lmsScanPoll = null;
          await fetch("/api/reindex", { method: "POST" }).catch(() => {});
          if (lmsPane.rescanStatus) lmsPane.rescanStatus.textContent = "Scan finished — library refreshed.";
        }
      } catch (e) { /* keep polling */ }
    }, 4000);
  }
  // One Rescan button; the dropdown chooses what LMS scans. Mirrors LMS's own
  // "Rescan Media Library" control. "full" wipes and rebuilds the library, so
  // it gets a confirm first.
  if (lmsPane.rescanGo) lmsPane.rescanGo.addEventListener("click", async () => {
    const sel = lmsPane.rescanMode;
    const mode = sel ? sel.value : "";
    const label = sel ? sel.options[sel.selectedIndex].text : "Rescan";
    if (mode === "full" &&
        !confirm("Clear the LMS library and rescan everything? The library is temporarily empty while it rebuilds.")) return;
    lmsPane.rescanGo.disabled = true;
    if (lmsPane.rescanStatus) lmsPane.rescanStatus.textContent = "“" + label + "” started…";
    try {
      const r = await fetch("/api/lms/rescan", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(mode ? { mode } : {})
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || `HTTP ${r.status}`); }
      watchLmsScan();
    } catch (e) {
      if (lmsPane.rescanStatus) lmsPane.rescanStatus.textContent = "Couldn't start: " + e.message;
    }
    setTimeout(() => { lmsPane.rescanGo.disabled = false; }, 3000);
  });

  const open = () => { showView("home"); loadVersion(); loadDiscogsToken(); loadFanartKey(); loadDisplaySettings(); loadLabelFolderDepth(); overlay.classList.remove("hidden"); };
  const close = () => {
    overlay.classList.add("hidden");
  };

  openBtn.addEventListener("click", open);
  overlay.addEventListener("click", (e) => {
    if (e.target.hasAttribute("data-settings-close")) close();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || overlay.classList.contains("hidden")) return;
    // Escape steps back one level: pane → home, home → closed.
    if (atHome()) close();
    else showView("home");
  });
})();


/* ------------------------------------------------------------------ */
/*  Pitchfork magazine — full-page overlay (side menu → Pitchfork)     */
/*                                                                     */
/*  A self-contained module (does NOT reuse initServiceBrowser, so it  */
/*  can't regress Qobuz/Tidal). It mirrors that factory's proven       */
/*  history-aware back mechanics — every close/back goes through       */
/*  history.back(), and a popstate handler reconciles the view stack   */
/*  against history.state[HKEY] — so the Android/browser back button   */
/*  behaves naturally. Two views deep: a magazine list (tab) → a       */
/*  review detail. Handler no-ops while the overlay is closed, so the  */
/*  rest of the app is unaffected.                                     */
(function initPitchfork() {
  const overlay  = document.getElementById("pitchfork-overlay");
  const trigger  = document.getElementById("pitchfork-toggle");
  const tabsEl   = document.getElementById("pitchfork-tabs");
  const statusEl = document.getElementById("pitchfork-status");
  const listEl   = document.getElementById("pitchfork-list");
  const detailEl = document.getElementById("pitchfork-detail");
  if (!overlay || !trigger || !listEl || !detailEl) return;

  const HKEY = "pf";
  let viewStack = [];          // [{kind:'tab',tab}] then optionally {kind:'detail',item}
  let reqSeq = 0;              // monotonic guard so a late fetch can't repaint a newer view
  let activeTab = "latest";
  const listCache = { latest: null, best: null };  // per-tab items, cached for the session

  const visible     = () => !overlay.classList.contains("hidden");
  const currentView = () => viewStack[viewStack.length - 1];
  const setStatus   = (m) => { if (statusEl) statusEl.textContent = m || ""; };

  function fmtScore(n) { return Number(n).toFixed(1); }   // toFixed already rounds to 1 dp

  function hideOverlay() {
    overlay.classList.add("hidden");
    viewStack = [];
    reqSeq++;                 // orphan any in-flight fetch
    listEl.innerHTML = "";
    detailEl.classList.add("hidden");
    detailEl.innerHTML = "";
    if (tabsEl) tabsEl.classList.remove("hidden");
    setStatus("");
  }

  const goBack = () => history.back();
  overlay.querySelectorAll("[data-pitchfork-close]").forEach(el => el.addEventListener("click", goBack));
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && visible()) goBack();
  });

  window.addEventListener("popstate", (e) => {
    if (!visible()) return;
    const depth = (e.state && Number.isFinite(e.state[HKEY])) ? e.state[HKEY] : 0;
    if (depth >= viewStack.length) {
      if (depth > viewStack.length) history.go(viewStack.length - depth);
      return;
    }
    const popped = currentView();
    viewStack.length = depth;
    if (!viewStack.length) { hideOverlay(); return; }
    // Leaving the detail: the list underneath is still in the DOM (detail only
    // hid it), so just restore it — no refetch. Exception: a detail opened as a
    // DEEP LINK (global search result) never rendered its list, so the grid is
    // empty — render it now instead of unhiding a blank page.
    if (popped && popped.kind === "detail") {
      reqSeq++;                          // orphan the detail's in-flight fetch, if any
      detailEl.classList.add("hidden");
      detailEl.innerHTML = "";
      if (!listEl.children.length) { render(currentView()); return; }
      listEl.classList.remove("hidden");
      if (tabsEl) tabsEl.classList.remove("hidden");   // tabs return with the list
      updateTabActive();
      return;
    }
    render(currentView());
  });

  function pushView(view) {
    viewStack.push(view);
    history.pushState({ [HKEY]: viewStack.length }, "");
    render(view);
  }

  // Leave the overlay entirely (unwinding its history entries) and then run a
  // follow-up — used by the detail's "open in library" / "find on <service>"
  // actions. history.go(-n) fires a single popstate that the handler above
  // turns into hideOverlay(). The follow-up must run only AFTER that close has
  // actually happened, otherwise a follow-up that opens ANOTHER history-managed
  // overlay (Qobuz/Tidal) would race the pending unwind and get torn down by
  // the stray popstate. A bare setTimeout doesn't guarantee that ordering
  // (flaky on iOS Safari), so we run fn from a one-shot popstate listener once
  // the overlay is confirmed hidden.
  function closeAndThen(fn) {
    const n = viewStack.length;
    if (!visible() || n <= 0) { hideOverlay(); fn(); return; }
    const once = () => {
      if (visible()) return;                       // not fully closed yet — wait for the next
      window.removeEventListener("popstate", once);
      fn();
    };
    window.addEventListener("popstate", once);
    history.go(-n);
  }

  function updateTabActive() {
    if (!tabsEl) return;
    const top = currentView();
    const tab = top && top.kind === "tab" ? top.tab : activeTab;
    tabsEl.querySelectorAll(".qobuz-tab").forEach(t =>
      t.classList.toggle("is-active", t.dataset.pftab === tab));
  }

  if (tabsEl) {
    tabsEl.querySelectorAll(".qobuz-tab").forEach(t => t.addEventListener("click", () => {
      const tab = t.dataset.pftab;
      if (!tab || !viewStack.length) return;
      activeTab = tab;
      const top = currentView();
      if (top.kind === "tab" && top.tab === tab) { updateTabActive(); return; }
      // Replace the top view (tab siblings never push history, keeping the
      // viewStack ↔ history 1:1 invariant).
      viewStack[viewStack.length - 1] = { kind: "tab", tab };
      render(currentView());
    }));
  }

  trigger.addEventListener("click", () => {
    if (visible()) return;
    activeTab = "latest";
    viewStack = [{ kind: "tab", tab: "latest" }];
    history.pushState({ [HKEY]: 1 }, "");   // a back press from the root closes the overlay
    overlay.classList.remove("hidden");
    render(currentView());
  });

  // Deep link from the global search: open the overlay straight to one review's
  // detail. Seeds the root list frame WITHOUT rendering it (rendering would be
  // orphaned by the detail's reqSeq bump anyway); the popstate leaving-detail
  // branch self-heals the empty list by rendering it on Back.
  window.__openPitchforkReview = (item) => {
    if (!item || !item.url) return;
    if (!visible()) {
      activeTab = "latest";
      viewStack = [{ kind: "tab", tab: "latest" }];
      history.pushState({ [HKEY]: 1 }, "");
      overlay.classList.remove("hidden");
    }
    pushView({ kind: "detail", item });
  };

  function render(view) {
    if (!view) return;
    if (view.kind === "detail") renderDetail(view.item);
    else renderList(view.tab);
  }

  async function renderList(tab) {
    const mySeq = ++reqSeq;
    detailEl.classList.add("hidden");
    detailEl.innerHTML = "";
    listEl.classList.remove("hidden");
    if (tabsEl) tabsEl.classList.remove("hidden");
    updateTabActive();
    if (listCache[tab]) { paintList(listCache[tab]); return; }
    listEl.innerHTML = "";
    setStatus("Loading…");
    let data;
    try {
      const r = await fetch("/api/pitchfork/reviews?type=" + encodeURIComponent(tab));
      if (mySeq !== reqSeq) return;
      data = await r.json();
      if (!r.ok) throw new Error(data.error || ("HTTP " + r.status));
    } catch (e) {
      if (mySeq !== reqSeq) return;
      setStatus("");
      listEl.innerHTML = '<div class="pf-empty">Couldn’t load Pitchfork right now. Try again in a little while.</div>';
      return;
    }
    if (mySeq !== reqSeq) return;
    const items = data.items || [];
    // Session-cache only a NON-EMPTY success (mirrors the backend's rule):
    // an empty response is a parse miss upstream — retry it next visit rather
    // than pinning "No reviews" for the whole session.
    if (items.length) listCache[tab] = items;
    paintList(items);
  }

  function paintList(items) {
    setStatus("");
    listEl.innerHTML = "";
    if (!items.length) {
      listEl.innerHTML = '<div class="pf-empty">No reviews to show right now.</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    for (const it of items) frag.appendChild(buildCard(it));
    listEl.appendChild(frag);
  }

  function buildCard(it) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "pf-card";

    const art = document.createElement("div");
    art.className = "pf-card-art";
    if (it.cover) {
      const img = document.createElement("img");
      img.loading = "lazy"; img.alt = ""; img.src = it.cover;
      img.addEventListener("error", () => { art.classList.add("pf-art-fallback"); img.remove(); });
      art.appendChild(img);
    } else {
      art.classList.add("pf-art-fallback");
    }
    if (it.score != null) {
      const s = document.createElement("span");
      s.className = "pf-score" + (it.isBestNewMusic ? " pf-score-bnm" : "");
      s.textContent = fmtScore(it.score);
      art.appendChild(s);
    }
    if (it.isBestNewMusic) {
      const b = document.createElement("span");
      b.className = "pf-bnm";
      b.textContent = "BNM";
      art.appendChild(b);
    }
    // Album/artist overlaid on the bottom of the cover so tiles stay square and
    // pack cleanly in the woven mosaic (no below-tile text breaking the grid).
    const meta = document.createElement("div");
    meta.className = "pf-card-meta";
    const al = document.createElement("div"); al.className = "pf-card-album";  al.textContent = it.album || "";
    const ar = document.createElement("div"); ar.className = "pf-card-artist"; ar.textContent = it.artist || "";
    meta.appendChild(al);
    meta.appendChild(ar);
    art.appendChild(meta);
    card.appendChild(art);

    card.addEventListener("click", () => pushView({ kind: "detail", item: it }));
    return card;
  }

  async function renderDetail(it) {
    const mySeq = ++reqSeq;
    listEl.classList.add("hidden");
    // Hide the tab chips while reading a review — switching tabs from within a
    // detail would leave a phantom stack entry (back would land on the wrong
    // list). You return to the list (tabs reappear) via Back first.
    if (tabsEl) tabsEl.classList.add("hidden");
    detailEl.classList.remove("hidden");
    detailEl.scrollTop = 0;
    detailEl.innerHTML =
      '<button class="pf-back" type="button">‹ Back</button>' +
      '<div class="pf-detail-head">' +
        (it.cover ? '<img class="pf-detail-art" src="' + esc(it.cover) + '" alt="">'
                  : '<div class="pf-detail-art pf-art-fallback"></div>') +
        '<div class="pf-detail-headmeta">' +
          '<div class="pf-detail-album">' + esc(it.album) + '</div>' +
          '<div class="pf-detail-artist">' + esc(it.artist) + '</div>' +
          '<div class="pf-detail-scorerow">' +
            (it.score != null ? '<span class="pf-score' + (it.isBestNewMusic ? ' pf-score-bnm' : '') + '">' + fmtScore(it.score) + '</span>' : '') +
            (it.isBestNewMusic ? '<span class="pf-bnm">Best New Music</span>' : '') +
          '</div>' +
        '</div>' +
      '</div>' +
      '<div class="pf-detail-body"><div class="pf-loading">Loading review…</div></div>' +
      '<div class="pf-detail-actions"></div>';
    detailEl.querySelector(".pf-back").addEventListener("click", goBack);
    // Match the card behaviour: a dead cover URL falls back to the ♪ tile
    // instead of the browser's broken-image glyph. (::after doesn't render on a
    // replaced <img>, so swap in a div that does.)
    const headImg = detailEl.querySelector("img.pf-detail-art");
    if (headImg) headImg.addEventListener("error", () => {
      const ph = document.createElement("div");
      ph.className = "pf-detail-art pf-art-fallback";
      headImg.replaceWith(ph);
    });
    const bodyEl = detailEl.querySelector(".pf-detail-body");
    const actEl  = detailEl.querySelector(".pf-detail-actions");

    // COMPLIANCE (UK law): the written review is never displayed in-app.
    // Paint the note and the actions (led by "Read on Pitchfork") IMMEDIATELY
    // — nothing they need is remote. The only async piece is the library
    // match, fetched after, which just upgrades the actions with an
    // "Open in your library" button when it lands.
    bodyEl.innerHTML =
      '<p class="pf-detail-note">The written review can’t be shown here — ' +
      'tap <strong>Read on Pitchfork</strong> to read it on pitchfork.com.</p>';
    buildActions(actEl, it, null);

    try {
      const qs = "?url=" + encodeURIComponent(it.url) +
                 "&album="  + encodeURIComponent(it.album  || "") +
                 "&artist=" + encodeURIComponent(it.artist || "");
      const r = await fetch("/api/pitchfork/review" + qs);
      if (mySeq !== reqSeq) return;
      const data = await r.json();
      if (r.ok && data.match) buildActions(actEl, it, data.match);
    } catch (e) { /* library match is optional — the actions already shown work */ }
  }

  function buildActions(container, it, match) {
    container.innerHTML = "";

    // Reading happens on pitchfork.com now — make that the first action.
    const link = document.createElement("a");
    link.className = "pf-action pf-action-link";
    link.href = it.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = "Read on Pitchfork ↗";
    container.appendChild(link);

    // Owned? → open the existing album modal (play/queue live there).
    if (match) {
      const play = document.createElement("button");
      play.type = "button";
      play.className = "pf-action pf-action-primary";
      play.textContent = "▶ Open in your library";
      play.addEventListener("click", () => {
        closeAndThen(() => {
          if (window.__openAlbum) window.__openAlbum(match, { source: "pitchfork", filter: null });
        });
      });
      container.appendChild(play);
    }

  }

})();

/* ------------------------------------------------------------------ */
/*  Check for updates button in settings                               */
/* ------------------------------------------------------------------ */
(function initCheckUpdate() {
  const btn      = document.getElementById("check-update-btn");
  const notesDiv = document.getElementById("settings-release-notes");
  if (!btn) return;
  // After a check finds an update, the button itself becomes the install
  // action (the old copy said "tap Update below", but the update banner sits
  // BEHIND the Settings sheet — there was no visible button to tap).
  let pendingUpdate = false;

  btn.addEventListener("click", async () => {
    if (btn.disabled) return;

    if (pendingUpdate) {
      // Second tap: install. Close Settings so the update banner (which owns
      // the download/unpack/restart progress UI) is visible, then hand off.
      pendingUpdate = false;
      btn.classList.remove("is-update-ready");
      const closer = document.querySelector("#settings-overlay [data-settings-close]");
      if (closer) closer.click();
      if (window.__applyUpdateNow) window.__applyUpdateNow();
      // The banner owns all progress/error/retry state from here — reset this
      // button so a reopened Settings offers a fresh check (on success the
      // page reloads anyway; on failure the banner shows the retry, and a
      // disabled "Updating…" here would strand with no reset path).
      btn.textContent = "Check for updates";
      return;
    }

    btn.disabled = true;
    btn.textContent = "Checking…";
    if (notesDiv) notesDiv.classList.add("hidden");
    try {
      await fetch("/api/update/check", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
      const r = await fetch("/api/update/status", { cache: "no-store" });
      const s = await r.json();
      if (s && s.available && s.latest) {
        pendingUpdate = true;
        btn.disabled = false;
        btn.classList.add("is-update-ready");
        btn.textContent = s.isDowngrade
          ? "Roll back to v" + s.latest
          : "Update to v" + s.latest;
        if (notesDiv && s.notes) {
          notesDiv.textContent = s.notes;
          notesDiv.classList.remove("hidden");
        }
      } else {
        btn.textContent = "Up to date (v" + (s && s.current || "?") + ")";
        setTimeout(() => { btn.disabled = false; btn.textContent = "Check for updates"; }, 4000);
      }
    } catch (e) {
      btn.textContent = "Check failed";
      setTimeout(() => { btn.disabled = false; btn.textContent = "Check for updates"; }, 3000);
    }
  });
})();

/* ------------------------------------------------------------------ */
/*  Play Unheard — topbar compass button with 2-second spin           */
/* ------------------------------------------------------------------ */
(function initPlayUnheard() {
  const btn        = document.getElementById("play-unheard-topbar");
  const zoneSelect = document.getElementById("zone-select");
  if (!btn) return;
  btn.addEventListener("click", async () => {
    const zone = zoneSelect && zoneSelect.value;
    if (!zone) { if (window.__showToast) window.__showToast("Select a zone first"); return; }
    if (btn.classList.contains("spinning")) return;

    // Spin the compass for 2 seconds, then fetch
    btn.classList.add("spinning");
    await new Promise(r => setTimeout(r, 2000));

    try {
      const r = await fetch("/api/play-unheard", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zone })
      });
      const j = await r.json();
      if (!r.ok) {
        if (window.__showToast) window.__showToast(j.error || "Could not start playback", "error");
      } else {
        if (window.__showToast) window.__showToast("Playing: " + (j.album || "random album"));
      }
    } catch (e) {
      if (window.__showToast) window.__showToast("Request failed", "error");
    } finally {
      btn.classList.remove("spinning");
    }
  });
})();

/* ------------------------------------------------------------------ */
/*  Artist albums view                                                 */
/* ------------------------------------------------------------------ */
(() => {
  const grid         = document.getElementById("album-grid");
  const countBar     = document.getElementById("content-count");
  const homeView     = document.getElementById("home-view");
  const homeSections = document.getElementById("home-sections");
  const topbarBack    = document.getElementById("topbar-back");
  const topbarRefresh = document.getElementById("topbar-refresh");
  const topbarSearch  = document.getElementById("topbar-search");

  let artistViewActive = false;
  let saved            = null;   // snapshot of the screen we came from
  let currentArtistHeader = null;   // which artist the in-flight header fetch is for

  // Full-width header at the top of the artist grid: photo, bio (clamped,
  // tap to expand) and band membership as tappable artist links.
  function renderArtistHeader(info) {
    const head = document.createElement("div");
    head.className = "artist-head";

    if (info.photo) {
      const img = document.createElement("img");
      img.className = "artist-head-photo";
      img.alt = "";
      img.src = info.photo;
      img.addEventListener("error", () => img.remove());
      head.appendChild(img);
    }

    const body = document.createElement("div");
    body.className = "artist-head-body";

    const memberRow = (label, names) => {
      if (!names || !names.length) return null;
      const row = document.createElement("div");
      row.className = "artist-head-members";
      const lab = document.createElement("span");
      lab.className = "artist-head-members-label";
      lab.textContent = label;
      row.appendChild(lab);
      names.forEach((nm, i) => {
        if (i > 0) row.appendChild(document.createTextNode(" · "));
        const b = document.createElement("button");
        b.type = "button";
        b.className = "artist-member-link";
        b.textContent = nm;
        b.addEventListener("click", () => showArtistAlbums(nm));
        row.appendChild(b);
      });
      return row;
    };
    const members  = memberRow("Members:", info.members);
    const memberOf = memberRow("Member of:", info.memberOf);
    if (members)  body.appendChild(members);
    if (memberOf) body.appendChild(memberOf);

    if (info.bio && info.bio.text) {
      const bio = document.createElement("div");
      bio.className = "artist-head-bio";
      bio.textContent = info.bio.text;
      const attrib = document.createElement("div");
      attrib.className = "artist-head-attrib";
      attrib.textContent = "Bio: " + (info.bio.attribution || "");
      const toggle = document.createElement("button");
      toggle.type = "button";
      toggle.className = "artist-head-toggle";
      toggle.textContent = "Show more";
      toggle.addEventListener("click", () => {
        const open = bio.classList.toggle("expanded");
        toggle.textContent = open ? "Show less" : "Show more";
      });
      body.appendChild(bio);
      body.appendChild(toggle);
      if (info.bio.attribution) body.appendChild(attrib);
      // Only offer the toggle when the clamped text actually overflows.
      requestAnimationFrame(() => {
        if (bio.scrollHeight <= bio.clientHeight + 4) toggle.remove();
      });
    }

    head.appendChild(body);
    grid.prepend(head);
  }

  // Move all of an element's children into a fragment (detaching them but
  // keeping the LIVE nodes, with their event listeners, intact). Restoring the
  // grid via innerHTML string would re-parse dead nodes — album tiles attach
  // their open handler per node, so a string round-trip left every restored
  // tile inert (tapping did nothing) until a grid refresh rebuilt real nodes.
  function detachChildren(el) {
    const frag = document.createDocumentFragment();
    while (el.firstChild) frag.appendChild(el.firstChild);
    return frag;
  }

  function exitArtistView() {
    if (!artistViewActive) return;
    artistViewActive = false;
    // The restored grid is the SAME live nodes, so any .is-selected classes
    // would come back with it — clear the selection rather than restoring a
    // highlighted set the action bar no longer describes.
    if (window.__exitAlbumSelectMode) window.__exitAlbumSelectMode();
    // Restore exactly the screen the artist view was opened from (the Home
    // landing, or an album wall) so Back doesn't dump the user somewhere else.
    if (saved) {
      grid.innerHTML = "";
      if (saved.gridNodes) grid.appendChild(saved.gridNodes);   // live nodes → listeners survive
      grid.classList.toggle("hidden", saved.gridHidden);
      if (homeView)     homeView.classList.toggle("hidden", saved.homeViewHidden);
      if (homeSections) homeSections.classList.toggle("hidden", saved.homeSectionsHidden);
      if (countBar) { countBar.innerHTML = saved.countHtml; countBar.classList.toggle("hidden", saved.countHidden); }
      if (topbarBack)    topbarBack.classList.toggle("hidden", saved.topbarBackHidden);
      if (topbarRefresh) topbarRefresh.classList.toggle("hidden", saved.topbarRefreshHidden);
      if (topbarSearch)  topbarSearch.classList.toggle("hidden", saved.topbarSearchHidden);
    }
    saved = null;
  }

  async function showArtistAlbums(artistName) {
    if (!artistName) return;
    // Drop any active/pending search (incl. the delayed external-sources fetch)
    // — reachable from the album-modal artist link with a search still live,
    // which would otherwise append external rows under this view's grid. The
    // search artist-chip stops the search itself; this covers every other path.
    if (window.__clearSearchIfActive) window.__clearSearchIfActive();
    if (artistViewActive) exitArtistView();
    // Snapshot the screen we're leaving (Home landing or an album wall) so the
    // "← Back" button restores it exactly.
    saved = {
      // Detach the wall's LIVE tiles (with their click/long-press listeners)
      // rather than serialising to an HTML string — a string restore produces
      // fresh, listener-less nodes and the tiles stop opening (the reported
      // "can't open another album" bug). This also empties the grid, so the
      // grid.innerHTML = "" below is a harmless no-op.
      gridNodes:          detachChildren(grid),
      gridHidden:         grid.classList.contains("hidden"),
      homeViewHidden:     homeView     ? homeView.classList.contains("hidden")     : true,
      homeSectionsHidden: homeSections ? homeSections.classList.contains("hidden") : true,
      countHtml:          countBar ? countBar.innerHTML : "",
      countHidden:        countBar ? countBar.classList.contains("hidden") : true,
      topbarBackHidden:    topbarBack    ? topbarBack.classList.contains("hidden")    : true,
      topbarRefreshHidden: topbarRefresh ? topbarRefresh.classList.contains("hidden") : true,
      topbarSearchHidden:  topbarSearch  ? topbarSearch.classList.contains("hidden")  : true,
    };
    artistViewActive = true;
    // Reveal the shared album grid and leave the Home landing / search results.
    // The search artist-chip calls stopSearch() first, which hides the grid and
    // re-shows the Home sections; without this the artist albums would render
    // into a hidden grid behind the Home rows (the reported bug).
    if (homeView)     homeView.classList.add("hidden");
    if (homeSections) homeSections.classList.add("hidden");
    grid.classList.remove("hidden");
    // Hide the shared topbar nav — this view has its own "← Back" button in
    // countBar, so leaving the shared Back/Refresh/Search visible (whatever the
    // previous screen set them to) would show a second, redundant back control.
    if (topbarBack)    topbarBack.classList.add("hidden");
    if (topbarRefresh) topbarRefresh.classList.add("hidden");
    if (topbarSearch)  topbarSearch.classList.add("hidden");

    // Show loading state
    if (countBar) {
      countBar.classList.remove("hidden");
      countBar.innerHTML = `
        <button class="artist-view-back" id="artist-back-btn">← Back</button>
        <span class="count-text">Loading…</span>`;
      document.getElementById("artist-back-btn").addEventListener("click", exitArtistView);
    }
    grid.innerHTML = "";

    // Photo + bio + band-membership header, fetched in PARALLEL with the
    // album list (external sources are slower than the local index) and
    // rendered as a full-width block at the top of the grid when it arrives.
    // A stale response (user already navigated on) is dropped.
    currentArtistHeader = artistName;
    fetch("/api/artist-info?artist=" + encodeURIComponent(artistName))
      .then(r => (r.ok ? r.json() : null))
      .then(info => {
        if (!info || !artistViewActive || currentArtistHeader !== artistName) return;
        if (!(info.photo || info.bio || (info.members || []).length || (info.memberOf || []).length)) return;
        renderArtistHeader(info);
      })
      .catch(() => { /* header is enrichment — the album grid stands alone */ });

    try {
      const r = await fetch("/api/artist-albums?artist=" + encodeURIComponent(artistName));
      if (!r.ok) throw new Error("HTTP " + r.status);
      const j = await r.json();
      const total = j.primary.length + j.featured.length;

      if (countBar) {
        countBar.innerHTML = `
          <button class="artist-view-back" id="artist-back-btn">← Back</button>
          <span class="count-text">${total} album${total !== 1 ? "s" : ""} · ${esc(artistName)}</span>`;
        document.getElementById("artist-back-btn").addEventListener("click", exitArtistView);
      }

      if (!total) {
        grid.innerHTML = `<div class="artist-view-empty">No albums found for "${esc(artistName)}"</div>`;
        return;
      }

      const frag = document.createDocumentFragment();

      if (j.primary.length) {
        if (j.featured.length) {
          const hdr = document.createElement("div");
          hdr.className = "artist-section-header";
          hdr.textContent = "Albums";
          frag.appendChild(hdr);
        }
        for (const a of j.primary) {
          frag.appendChild(window.__buildAlbumTile(a));
        }
      }

      if (j.featured.length) {
        const hdr = document.createElement("div");
        hdr.className = "artist-section-header";
        hdr.textContent = "Also appears on";
        frag.appendChild(hdr);
        for (const a of j.featured) {
          frag.appendChild(window.__buildAlbumTile(a));
        }
      }

      grid.appendChild(frag);
    } catch (e) {
      if (countBar) {
        countBar.innerHTML = `
          <button class="artist-view-back" id="artist-back-btn">← Back</button>
          <span class="count-text" style="color:var(--danger)">Error: ${esc(e.message)}</span>`;
        document.getElementById("artist-back-btn").addEventListener("click", exitArtistView);
      }
    }
  }

  window.__showArtistAlbums = showArtistAlbums;
  window.__exitArtistView   = exitArtistView;
})();

/* ------------------------------------------------------------------ */
/*  Docker migration banner (shown to native installs only)           */
/* ------------------------------------------------------------------ */
(function initDockerMigration() {
  const banner  = document.getElementById("docker-migration-banner");
  const dismiss = document.getElementById("docker-migration-dismiss");
  if (!banner) return;
  const DISMISS_KEY = "rra-docker-migrated";
  if (localStorage.getItem(DISMISS_KEY)) return;
  fetch("/api/update/status", { cache: "no-store" })
    .then((r) => r.json())
    .then((s) => { if (!s.is_docker) banner.classList.remove("hidden"); })
    .catch(() => { /* migration banner is non-critical; stays hidden on error */ });
  if (dismiss) {
    dismiss.addEventListener("click", () => {
      localStorage.setItem(DISMISS_KEY, "1");
      banner.classList.add("hidden");
    });
  }
})();

/* ------------------------------------------------------------------ */
/*  Side menu (hamburger drawer)                                        */
/*  Items with data-target trigger the hidden top-bar button of that   */
/*  id; data-action items switch the main view (home / random wall).   */
/* ------------------------------------------------------------------ */
(function initMenuDrawer() {
  const overlay = document.getElementById("menu-overlay");
  const toggle  = document.getElementById("menu-toggle");
  if (!overlay || !toggle) return;

  // Streaming services the SERVER can actually use. A plugin the owner removed,
  // or one they've signed out of, must leave no trace in the UI — an entry that
  // only fails when tapped is worse than no entry. /api/services distinguishes
  // "not installed" from "not signed in"; both hide.
  let servicesReady = false;
  const usable = new Set();
  window.__serviceUsable = (tag) => !servicesReady || usable.has(tag);
  async function loadServices(force) {
    try {
      const r = await fetch("/api/services" + (force ? "?refresh=1" : ""), { cache: "no-store" });
      if (!r.ok) return;
      const j = await r.json();
      usable.clear();
      for (const s of (j.services || [])) if (s.usable) usable.add(s.tag);
      servicesReady = true;
      paintServiceItems(j.services || []);
    } catch (e) { /* leave everything shown rather than hiding on a blip */ }
  }
  function paintServiceItems(list) {
    const browse = overlay.querySelector('.menu-item[data-target="server-browse-toggle"]');
    if (browse) {
      browse.classList.toggle("hidden", !usable.has("qobuz"));
      // Name it after whatever the server actually runs, so a future Tidal
      // build doesn't need this string changed in two places.
      const q = list.find(s => s.tag === "qobuz");
      const label = browse.querySelector("span");
      if (label && q) label.textContent = "Browse " + q.name;
    }
    if (window.__repaintServiceUI) window.__repaintServiceUI();
  }
  window.__refreshServices = () => loadServices(true);
  loadServices(false);

  const openMenu  = () => overlay.classList.remove("hidden");
  const closeMenu = () => overlay.classList.add("hidden");

  toggle.addEventListener("click", openMenu);
  overlay.addEventListener("click", (e) => {
    if (e.target.closest && e.target.closest("[data-menu-close]")) closeMenu();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !overlay.classList.contains("hidden")) closeMenu();
  });

  overlay.querySelectorAll(".menu-item").forEach((item) => {
    item.addEventListener("click", () => {
      const action = item.dataset.action;
      const target = item.dataset.target;
      closeMenu();

      if (action === "home") {
        if (window.__showHome) window.__showHome();
        return;
      }
      if (action === "rescan") {
        // Shortcut for the common case only. The destructive "clear and rescan
        // everything" stays in Settings -> LMS server, where it has its own
        // confirm — a two-tap path must not be able to wipe the library.
        (async () => {
          const go = await window.__confirmDialog(
            "Scan the LMS library for new and changed music?");
          if (!go) return;
          try {
            const r = await fetch("/api/lms/rescan", {
              method: "POST", headers: { "Content-Type": "application/json" }, body: "{}"
            });
            if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || ("HTTP " + r.status)); }
            window.__showToast("Rescan started — new music appears as it's found.");
            if (window.__watchLmsScan) window.__watchLmsScan();
          } catch (e) { window.__showToast("Couldn't start rescan: " + e.message, "error"); }
        })();
        return;
      }
      if (action === "shuffle") {
        // Clear any active filter/labels so "Random albums" is a fresh wall.
        // applyFilter(null) reveals the wall and loads it.
        if (window.__applyFilter) window.__applyFilter(null);
        else if (window.__loadRandom) window.__loadRandom();
        return;
      }

      // Everything else just triggers the original control; each one manages
      // its own view — Filter/Labels reveal the wall when they render,
      // Settings opens an overlay over Home, Play-unheard just plays.
      if (target) {
        const btn = document.getElementById(target);
        if (btn) btn.click();
      }
    });
  });
})();

/* ------------------------------------------------------------------ */
/*  Playlist sharing — the MDRP1 interchange format.                     */
/*                                                                      */
/*  A share describes the MUSIC, not the files: whoever imports it gets  */
/*  whatever their own library can match. The same format is read and    */
/*  written by the sibling Roon build, so a playlist moves between the   */
/*  two — see lib/share.js for the wire contract.                        */
/* ------------------------------------------------------------------ */
(function initPlaylistShare() {
  const toast = (m, k) => window.__showToast && window.__showToast(m, k);

  // ---- Share -------------------------------------------------------------
  window.__sharePlaylist = async function (name, playlistId, btn) {
    if (btn) btn.disabled = true;
    let j;
    try {
      const r = await fetch("/api/share/encode", {
        method: "POST", headers: { "Content-Type": "application/json" },
        // The server reads the playlist itself — it has the album, track number
        // and duration that a rendered row doesn't.
        body: JSON.stringify({ name, playlist_id: playlistId })
      });
      j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { toast(e.message, "error"); return; }
    finally { if (btn) btn.disabled = false; }
    openShareSheet(name, j);
  };

  function openShareSheet(name, j) {
    window.__openLibSheet("Share " + name, (body) => {
      const n = j.track_count || 0;
      const sum = document.createElement("div");
      sum.className = "share-sum";
      sum.textContent = n + " track" + (n === 1 ? "" : "s");
      body.appendChild(sum);

      const warn = (t) => {
        const w = document.createElement("div");
        w.className = "share-warn"; w.textContent = t;
        body.appendChild(w);
      };
      if (j.truncated) warn("This playlist is longer than one share can carry — the end was left out.");
      if (j.skipped)   warn(j.skipped + " entr" + (j.skipped === 1 ? "y" : "ies") + " had no title and were left out.");
      // Past this size a paste gets mangled by chat apps far more often than
      // not, so say so before they try rather than after it fails.
      if (j.bytes > 40000) {
        warn("This is " + Math.round(j.bytes / 1024) + " KB — too big to paste reliably. Use Download and send the file.");
      }

      const note = document.createElement("div");
      note.className = "share-note";
      note.textContent = "This describes the music, not the music itself. Whoever imports it " +
                         "gets whatever their own library can match.";
      body.appendChild(note);

      const ta = document.createElement("textarea");
      ta.className = "share-blob"; ta.id = "share-blob";
      ta.readOnly = true; ta.rows = 4;
      // The payload is base64url and CASE-SIGNIFICANT; autocorrect must not
      // touch it.
      ta.setAttribute("autocapitalize", "none");
      ta.setAttribute("autocorrect", "off");
      ta.spellcheck = false;
      ta.value = j.blob || "";
      body.appendChild(ta);
    }, (foot, close) => {
      const copy = document.createElement("button");
      copy.type = "button"; copy.className = "action-btn primary"; copy.textContent = "Copy";
      copy.addEventListener("click", async () => {
        // navigator.clipboard is a SECURE-CONTEXT api and this app is served
        // over plain http on the LAN, so on most devices it does not exist —
        // the "modern" path would never be taken and every user gets pushed to
        // hand-selecting the blob, which is how a copy comes back short.
        // execCommand still works on http, so it is tried FIRST and the async
        // API is the fallback, not the other way round.
        const el = document.getElementById("share-blob");
        if (el) {
          el.focus();
          el.setSelectionRange(0, (j.blob || "").length);
          try {
            if (document.execCommand && document.execCommand("copy")) {
              toast("Copied — paste it to whoever you're sharing with");
              return;
            }
          } catch (e) { /* falls through to the async API */ }
        }
        try {
          await navigator.clipboard.writeText(j.blob || "");
          toast("Copied — paste it to whoever you're sharing with");
        } catch (e) {
          // Both refused. The text is selected, so a manual copy still works —
          // say so rather than failing silently.
          toast("Couldn't copy automatically — the text is selected, copy it by hand", "error");
        }
      });
      const dl = document.createElement("button");
      dl.type = "button"; dl.className = "action-btn"; dl.textContent = "Download";
      dl.addEventListener("click", () => {
        const file = String(name || "playlist").replace(/[^a-z0-9]+/gi, "_").slice(0, 60) + ".musicd";
        const url = URL.createObjectURL(new Blob([j.blob || ""], { type: "text/plain" }));
        const a = document.createElement("a");
        a.href = url; a.download = file;
        document.body.appendChild(a); a.click(); a.remove();
        // Revoking immediately can race the download on some browsers.
        setTimeout(() => URL.revokeObjectURL(url), 10000);
      });
      const done = document.createElement("button");
      done.type = "button"; done.className = "action-btn"; done.textContent = "Done";
      done.addEventListener("click", close);
      foot.appendChild(copy); foot.appendChild(dl); foot.appendChild(done);
    });
  }

  // ---- Import ------------------------------------------------------------
  window.__openImportSheet = function () {
    window.__openLibSheet("Import a playlist", (body) => {
      const note = document.createElement("div");
      note.className = "share-note";
      note.textContent = "Paste a playlist someone shared with you. It describes the music, " +
                         "so you'll get the tracks your own library can match — the rest are " +
                         "listed so you know what's missing.";
      body.appendChild(note);

      const ta = document.createElement("textarea");
      ta.className = "share-blob"; ta.id = "import-blob";
      ta.rows = 4; ta.placeholder = "MDRP1:…";
      // iOS autocorrect treats MDRP1 as a word it doesn't know and lowercases
      // it on paste. The marker survives that (it is matched case-insensitively)
      // but the payload is base64url and case-SENSITIVE, so this must be off.
      ta.setAttribute("autocapitalize", "none");
      ta.setAttribute("autocorrect", "off");
      ta.setAttribute("autocomplete", "off");
      ta.spellcheck = false;
      body.appendChild(ta);

      const pick = document.createElement("label");
      pick.className = "action-btn import-file";
      pick.textContent = "Choose a file…";
      const file = document.createElement("input");
      file.type = "file"; file.id = "import-file"; file.className = "visually-hidden";
      file.accept = ".musicd,text/plain";
      file.addEventListener("change", () => {
        const f = file.files && file.files[0];
        if (!f) return;
        const rd = new FileReader();
        rd.onload = () => {
          ta.value = String(rd.result || "");
          const res = document.getElementById("import-result");
          if (res) res.textContent = "Loaded " + f.name + " — press Import.";
        };
        rd.readAsText(f);
      });
      pick.appendChild(file);
      body.appendChild(pick);

      const result = document.createElement("div");
      result.className = "import-result"; result.id = "import-result";
      body.appendChild(result);
    }, (foot, close) => {
      const imp = document.createElement("button");
      imp.type = "button"; imp.className = "action-btn primary"; imp.textContent = "Import";
      imp.addEventListener("click", () => runImport(imp));
      const done = document.createElement("button");
      done.type = "button"; done.className = "action-btn"; done.textContent = "Close";
      done.addEventListener("click", close);
      foot.appendChild(imp); foot.appendChild(done);
    });
  };

  async function runImport(btn) {
    const ta = document.getElementById("import-blob");
    const out = document.getElementById("import-result");
    if (!ta || !out) return;
    out.textContent = "Matching against your library…";
    btn.disabled = true;
    try {
      const r = await fetch("/api/share/import", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ blob: ta.value })
      });
      const j = await r.json().catch(() => ({}));
      // The server's messages are written for the person holding the blob
      // ("it may have been cut short in transit"); show them verbatim.
      if (!r.ok) { out.textContent = j.error || ("HTTP " + r.status); return; }
      renderImportResult(out, j);
    } catch (e) {
      out.textContent = "Couldn't reach the server";
    } finally { btn.disabled = false; }
  }

  function renderImportResult(out, j) {
    out.innerHTML = "";
    const found = (j.resolved || []).length;
    const miss  = j.missing || [];

    const sum = document.createElement("div");
    sum.className = "share-sum";
    sum.textContent = found + " of " + j.total + " track" + (j.total === 1 ? "" : "s") +
                      " found in your library";
    out.appendChild(sum);

    const warn = (t) => {
      const w = document.createElement("div");
      w.className = "share-warn"; w.textContent = t;
      out.appendChild(w);
    };
    if (j.truncated) warn("That playlist is longer than one import can take — the end was left out.");

    // Reported, never silently dropped: "38 of 45" is the honest outcome and
    // the missing 7 are the interesting part.
    if (miss.length) {
      warn(miss.length + " couldn't be matched:");
      const ul = document.createElement("ul");
      ul.className = "import-missing";
      // Capped: a share with 500 unmatched tracks would bury the save button.
      for (const m of miss.slice(0, 25)) {
        const li = document.createElement("li");
        li.textContent = [m.title, m.artist, m.album].filter(Boolean).join(" · ");
        ul.appendChild(li);
      }
      if (miss.length > 25) {
        const li = document.createElement("li");
        li.textContent = "…and " + (miss.length - 25) + " more";
        ul.appendChild(li);
      }
      out.appendChild(ul);
    }

    if (!found) { warn("Nothing here matched, so there's nothing to save."); return; }

    const save = document.createElement("button");
    save.type = "button"; save.className = "action-btn primary import-save";
    save.textContent = "Save " + found + " track" + (found === 1 ? "" : "s") + " as a playlist";
    save.addEventListener("click", async () => {
      const name = window.prompt("Name this playlist", j.name || "Shared playlist");
      if (name === null) return;
      const trimmed = String(name).trim();
      if (!trimmed) { toast("Give it a name first", "error"); return; }
      save.disabled = true;
      try {
        const r = await fetch("/api/share/save", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: trimmed, items: j.resolved })
        });
        const res = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(res.error || ("HTTP " + r.status));
        save.textContent = "Saved";
        toast("Added " + res.added + " track" + (res.added === 1 ? "" : "s") + " to “" + trimmed + "”" +
              // LMS creates nothing on a name collision and reports the existing
              // id, so say which playlist the tracks actually went into.
              (res.created === false ? " (the playlist that already had that name)" : "") +
              (res.skipped ? " — " + res.skipped + " couldn't be stored" : ""));
      } catch (e) {
        save.disabled = false;
        toast(e.message, "error");
      }
    });
    out.appendChild(save);
  }
})();

/* ------------------------------------------------------------------ */
/*  Playlists — LMS stored playlists, plus the shared "add selection to */
/*  a playlist" sheet used by both the album and track action bars.     */
/*                                                                      */
/*  Lyrion has no bulk add: `playlists edit cmd:add` appends ONE track   */
/*  by title+url, so the server loops. That's why adding a big selection */
/*  reports how many landed rather than pretending it's atomic.          */
/* ------------------------------------------------------------------ */
(function initPlaylists() {
  const openBtn = document.getElementById("playlists-toggle");
  const overlay = document.getElementById("playlists-overlay");
  const body    = document.getElementById("pl-body");
  const titleEl = document.getElementById("pl-title");
  const backBtn = document.getElementById("pl-back");
  if (!openBtn || !overlay || !body) return;

  {
    const imp = document.getElementById("pl-import");
    if (imp) imp.addEventListener("click", () => window.__openImportSheet());
  }

  let stack = [];     // [{kind:"list"} | {kind:"playlist", id, title}]
  let seq = 0;

  const msg = (cls, text) => {
    body.innerHTML = "";
    const d = document.createElement("div"); d.className = cls; d.textContent = text;
    body.appendChild(d);
  };
  function close() { overlay.classList.add("hidden"); document.body.style.overflow = ""; }
  overlay.querySelectorAll("[data-pl-close]").forEach(el => el.addEventListener("click", close));
  backBtn.addEventListener("click", () => { if (stack.length > 1) { stack.pop(); render(); } else close(); });

  function open() {
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    stack = [{ kind: "list", title: "Playlists" }];
    render();
  }
  openBtn.addEventListener("click", open);
  window.__openPlaylists = open;

  function render() {
    const f = stack[stack.length - 1] || { kind: "list", title: "Playlists" };
    titleEl.textContent = f.title || "Playlists";
    backBtn.hidden = stack.length <= 1;
    body.scrollTop = 0;
    if (f.kind === "playlist") loadPlaylist(f);
    else loadList();
  }

  async function loadList() {
    msg("qb-loading", "Loading…");
    const mine = ++seq;
    let j;
    try {
      const r = await fetch("/api/playlists", { cache: "no-store" });
      j = await r.json();
      if (mine !== seq) return;
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { if (mine === seq) msg("qb-empty", "Couldn’t load: " + e.message); return; }
    const list = (j && j.playlists) || [];
    body.innerHTML = "";
    if (!list.length) {
      msg("qb-empty", "No playlists yet. Select some albums or tracks and use “Add to playlist”.");
      return;
    }
    // Album-grid tiles, not a list of rows: a playlist reads as a thing with
    // artwork, and its cover is a mosaic of the albums its tracks come from.
    const grid = document.createElement("div"); grid.className = "album-grid";
    for (const pl of list) {
      const n = pl.tracks;
      const tile = window.__buildAlbumTile({
        title: pl.title || "Untitled",
        subtitle: n == null ? "" : n + (n === 1 ? " track" : " tracks"),
        art: pl.art || [],
      }, () => { stack.push({ kind: "playlist", id: pl.id, title: pl.title }); render(); });
      tile.classList.add("is-playlist");
      grid.appendChild(tile);
    }
    body.appendChild(grid);
  }

  async function loadPlaylist(f) {
    msg("qb-loading", "Loading…");
    const mine = ++seq;
    let j;
    try {
      const r = await fetch("/api/playlist/tracks?playlist_id=" + encodeURIComponent(f.id), { cache: "no-store" });
      j = await r.json();
      if (mine !== seq) return;
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { if (mine === seq) msg("qb-empty", "Couldn’t load: " + e.message); return; }
    const tracks = (j && j.tracks) || [];
    body.innerHTML = "";

    const acts = document.createElement("div");
    acts.className = "modal-actions";
    const mk = (label, kind, primary) => {
      const b = document.createElement("button");
      b.type = "button"; b.className = "action-btn" + (primary ? " primary" : "");
      b.textContent = label;
      b.addEventListener("click", () => playPlaylist(f, kind, b));
      return b;
    };
    acts.appendChild(mk("Play Now", "play_now", true));
    acts.appendChild(mk("Queue", "queue", false));
    {
      const sh = document.createElement("button");
      sh.type = "button"; sh.className = "action-btn"; sh.textContent = "Share";
      // The server reads the playlist itself — it has the album, track number
      // and duration that a rendered row doesn't.
      sh.addEventListener("click", () => window.__sharePlaylist(f.title || "Playlist", f.id, sh));
      acts.appendChild(sh);
    }
    body.appendChild(acts);

    if (!tracks.length) {
      const e = document.createElement("div"); e.className = "qb-empty";
      e.textContent = "This playlist is empty.";
      body.appendChild(e);
      return;
    }
    const ol = document.createElement("ol"); ol.className = "track-list";
    for (const t of tracks) {
      const li = document.createElement("li");
      const tx = document.createElement("div"); tx.className = "t-text";
      const ti = document.createElement("span"); ti.className = "t-title"; ti.textContent = t.title || "";
      tx.appendChild(ti);
      if (t.subtitle) {
        const su = document.createElement("span"); su.className = "t-sub"; su.textContent = t.subtitle;
        tx.appendChild(su);
      }
      li.appendChild(tx);
      ol.appendChild(li);
    }
    body.appendChild(ol);
  }

  async function playPlaylist(f, kind, btn) {
    const zone = window.__selectedZoneId && window.__selectedZoneId();
    if (!zone) { if (window.__showToast) window.__showToast("Pick a zone first", "error"); return; }
    btn.disabled = true;
    try {
      const r = await fetch("/api/playlist/play", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ playlist_id: f.id, zone_or_output_id: zone, kind })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      if (window.__showToast) window.__showToast((kind === "play_now" ? "Playing " : "Queued ") + (f.title || "playlist"));
    } catch (e) {
      if (window.__showToast) window.__showToast(e.message, "error");
    } finally { btn.disabled = false; }
  }

  // ---- Shared "add this selection to a playlist" sheet ----
  // payload is whatever /api/playlists/add accepts: {offsets:[…]} for albums,
  // or {offset, tracks:[…]} for tracks within one album.
  window.__addToPlaylistSheet = function (payload, describe) {
    let close = null;
    close = window.__openLibSheet("Add to playlist", (sheetBody) => {
      const note = document.createElement("div");
      note.className = "lib-facet-note";
      note.textContent = describe || "";
      note.style.marginBottom = "10px";
      sheetBody.appendChild(note);

      const newRow = document.createElement("button");
      newRow.type = "button"; newRow.className = "lib-sort-row";
      const plus = document.createElement("span"); plus.className = "lib-sort-arrow"; plus.textContent = "+";
      const nl = document.createElement("span"); nl.className = "lib-sort-label"; nl.textContent = "New playlist…";
      newRow.appendChild(plus); newRow.appendChild(nl);
      newRow.addEventListener("click", () => {
        const name = window.prompt("Name the new playlist");
        if (name == null) return;
        const trimmed = String(name).trim();
        if (!trimmed) return;
        send({ ...payload, name: trimmed }, close);
      });
      sheetBody.appendChild(newRow);

      const listWrap = document.createElement("div");
      sheetBody.appendChild(listWrap);
      (async () => {
        try {
          const r = await fetch("/api/playlists", { cache: "no-store" });
          const j = await r.json();
          const list = (j && j.playlists) || [];
          if (!list.length) return;
          const lbl = document.createElement("div");
          lbl.className = "lib-sheet-section-label";
          lbl.style.marginTop = "14px";
          lbl.textContent = "Existing";
          listWrap.appendChild(lbl);
          for (const pl of list) {
            const row = document.createElement("button");
            row.type = "button"; row.className = "lib-sort-row";
            const sp = document.createElement("span"); sp.className = "lib-sort-arrow"; sp.textContent = "";
            const t = document.createElement("span"); t.className = "lib-sort-label"; t.textContent = pl.title || "Untitled";
            row.appendChild(sp); row.appendChild(t);
            row.addEventListener("click", () => send({ ...payload, playlist_id: pl.id }, close));
            listWrap.appendChild(row);
          }
        } catch (e) { /* the New-playlist path still works */ }
      })();
    });
  };

  async function send(bodyObj, close) {
    if (close) close();
    try {
      const r = await fetch("/api/playlists/add", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(bodyObj)
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      // created:false means the name already existed — LMS appends to it rather
      // than making a second playlist, so say so instead of implying a new one.
      const where = bodyObj.name
        ? (j.created ? "new playlist “" + bodyObj.name + "”" : "existing “" + bodyObj.name + "”")
        : "playlist";
      const skipped = j.skipped ? " (" + j.skipped + " skipped)" : "";
      if (window.__showToast) window.__showToast("Added " + j.added + " track" + (j.added === 1 ? "" : "s") + " to " + where + skipped);
    } catch (e) {
      if (window.__showToast) window.__showToast(e.message, "error", 9000);
    } finally {
      // ALWAYS, not just on success. The teardown used to sit after the
      // throw, so a failed add left the user stranded in multi-select with
      // their selection still lit and no obvious way back — which is exactly
      // how a server-side failure was reported: as the UI being stuck. The
      // selection has served its purpose either way; the toast carries the
      // outcome.
      const after = window.__afterPlaylistAdd;
      window.__afterPlaylistAdd = null;   // never let a stale one fire later
      if (after) { try { after(); } catch (e) { /* teardown must not mask the result */ } }
    }
  }
})();

/* ------------------------------------------------------------------ */
/*  Live Playlists — rule-based playlists that re-evaluate on open.     */
/*                                                                     */
/*  A Live Playlist stores the Library's sort+focus QUERY, never a      */
/*  track list, so it gains and loses albums by itself as the library   */
/*  and the play history change. Creating/editing the rules happens in  */
/*  the Library's Focus sheet (see __editLivePlaylist); this overlay is */
/*  the browse/play/manage surface.                                     */
/*                                                                     */
/*  Albums route through the shared window.__openAlbum, exactly as the  */
/*  Library wall does, so there is no second album-detail path to drift */
/*  out of step with the real one.                                      */
/* ------------------------------------------------------------------ */
(function initLivePlaylists() {
  const openBtn = document.getElementById("live-playlists-toggle");
  const overlay = document.getElementById("live-playlists-overlay");
  const body    = document.getElementById("lp-body");
  const titleEl = document.getElementById("lp-title");
  const backBtn = document.getElementById("lp-back");
  if (!openBtn || !overlay || !body) return;

  let stack = [];      // [{kind:"list"} | {kind:"detail", id, name}]
  let seq = 0;
  const PAGE = 60;

  const msg = (cls, text) => {
    body.innerHTML = "";
    const d = document.createElement("div");
    d.className = cls; d.textContent = text;
    body.appendChild(d);
  };
  function closeOverlay() { overlay.classList.add("hidden"); document.body.style.overflow = ""; }
  overlay.querySelectorAll("[data-lp-close]").forEach(el => el.addEventListener("click", closeOverlay));
  backBtn.addEventListener("click", () => { if (stack.length > 1) { stack.pop(); render(); } else closeOverlay(); });

  function open() {
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    stack = [{ kind: "list" }];
    render();
  }
  openBtn.addEventListener("click", open);
  window.__openLivePlaylists = open;
  // The Focus sheet calls this after a save so the wall is never stale behind
  // an open overlay.
  window.__refreshLivePlaylists = () => {
    if (!overlay.classList.contains("hidden") && stack.length && stack[stack.length - 1].kind === "list") render();
  };

  function render() {
    const f = stack[stack.length - 1] || { kind: "list" };
    backBtn.hidden = stack.length <= 1;
    titleEl.textContent = f.kind === "detail" ? (f.name || "Live Playlist") : "Live Playlists";
    body.scrollTop = 0;
    if (f.kind === "detail") loadDetail(f);
    else loadList();
  }

  // ---- The wall of playlists ----
  async function loadList() {
    msg("qb-loading", "Loading…");
    const mine = ++seq;
    let j;
    try {
      const r = await fetch("/api/live-playlists", { cache: "no-store" });
      j = await r.json();
      if (mine !== seq) return;
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { if (mine === seq) msg("qb-empty", "Couldn’t load: " + e.message); return; }

    const list = (j && j.playlists) || [];
    body.innerHTML = "";
    const intro = document.createElement("div");
    intro.className = "lp-intro";
    intro.textContent = list.length
      ? "These update themselves — albums join and leave as your library and listening change."
      : "A Live Playlist is a saved set of rules, not a fixed list, so it keeps itself up to date.";
    body.appendChild(intro);

    if (!list.length) {
      const how = document.createElement("div");
      how.className = "qb-empty";
      how.textContent = "To make one: open Library, set Sort and Focus how you want, then tap “Save as Live Playlist”.";
      body.appendChild(how);
      return;
    }
    const grid = document.createElement("div");
    grid.className = "album-grid";
    for (const pl of list) grid.appendChild(tile(pl));
    body.appendChild(grid);
  }

  // A 2x2 mosaic of the first four covers — a Live Playlist has no artwork of
  // its own, so it borrows from whatever it currently resolves to.
  // The same album tile the rest of the app uses, so Live Playlists sit in a
  // real album grid rather than a lookalike of one.
  function tile(pl) {
    const btn = window.__buildAlbumTile({
      title: pl.name,
      // "100 of 1,179 albums" when the size cap is doing something, plain
      // "N albums" when it isn't — the two numbers only differ when the cap
      // actually left something out, and that is the interesting case.
      subtitle: (pl.matched != null && pl.matched > pl.total
                  ? pl.total + " of " + pl.matched.toLocaleString() + " albums"
                  : pl.total + (pl.total === 1 ? " album" : " albums"))
                + (pl.order === "random" ? " · random" : "")
                + " · " + ruleSummary(pl.view),
      art: pl.art || [],
    }, () => { stack.push({ kind: "detail", id: pl.id, name: pl.name }); render(); });
    btn.classList.add("is-playlist");
    return btn;
  }

  // Human-readable rules, so a tile says what it actually does.
  const SORT_LABEL = { album: "A–Z", artist: "by artist", genre: "by genre", year: "by year",
                       plays: "most played", lastplayed: "last played", random: "shuffled" };
  const PLAYED_LABEL = { never: "never played", 6: "not in 6 months", 12: "not in 12 months" };
  function ruleSummary(v) {
    if (!v) return "";
    const bits = [];
    if (v.genre && v.genre.length) bits.push(v.genre.join("/"));
    if (v.decade && v.decade.length) bits.push(v.decade.slice().sort().map(d => d + "s").join("/"));
    if (v.source && v.source.length) bits.push(v.source.join("/"));
    if (v.played && v.played !== "any") bits.push(PLAYED_LABEL[v.played] || v.played);
    bits.push(SORT_LABEL[v.sort] || v.sort);
    return bits.join(", ");
  }

  // ---- One playlist ----
  async function loadDetail(frame) {
    msg("qb-loading", "Loading…");
    const mine = ++seq;
    let j;
    try {
      const r = await fetch("/api/live-playlist?id=" + encodeURIComponent(frame.id) + "&offset=0&count=" + PAGE, { cache: "no-store" });
      j = await r.json();
      if (mine !== seq) return;
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { if (mine === seq) msg("qb-empty", "Couldn’t load: " + e.message); return; }

    body.innerHTML = "";
    const head = document.createElement("div");
    head.className = "lp-detail-head";
    const rules = document.createElement("div");
    rules.className = "lp-rules";
    rules.textContent = ruleSummary(j.view);
    const count = document.createElement("div");
    count.className = "lp-count";
    count.textContent = j.total + (j.total === 1 ? " album" : " albums") + " right now";
    head.appendChild(count); head.appendChild(rules);
    body.appendChild(head);

    const acts = document.createElement("div");
    acts.className = "modal-actions lp-actions";
    acts.appendChild(actionBtn("Play Now", true, () => playAll(frame.id, "play_now")));
    acts.appendChild(actionBtn("Queue", false, () => playAll(frame.id, "queue")));
    acts.appendChild(actionBtn("Edit rules", false, () => {
      closeOverlay();
      if (window.__editLivePlaylist) window.__editLivePlaylist({ id: frame.id, name: j.name, view: j.view });
    }));
    acts.appendChild(actionBtn("Delete", false, () => del(frame.id, j.name)));
    body.appendChild(acts);

    const grid = document.createElement("div");
    grid.className = "album-grid lp-albums";
    body.appendChild(grid);

    const state = { loaded: 0, total: j.total, busy: false };
    const appendAlbums = (albums) => {
      for (const a of albums) {
        const tileEl = window.__buildAlbumTile
          ? window.__buildAlbumTile(a, () => window.__openAlbum(a, { source: "home", filter: null }))
          : null;
        if (tileEl) grid.appendChild(tileEl);
      }
      state.loaded += albums.length;
    };
    appendAlbums(j.albums || []);

    // Same sentinel-driven paging as the Qobuz browser.
    const sentinel = document.createElement("div");
    sentinel.className = "qb-sentinel";
    body.appendChild(sentinel);
    const io = new IntersectionObserver(async (entries) => {
      if (!entries[0].isIntersecting || state.busy || state.loaded >= state.total) return;
      if (mine !== seq) { io.disconnect(); return; }
      state.busy = true;
      try {
        const r = await fetch("/api/live-playlist?id=" + encodeURIComponent(frame.id) +
          "&offset=" + state.loaded + "&count=" + PAGE, { cache: "no-store" });
        const more = await r.json();
        if (mine !== seq) { io.disconnect(); return; }
        if (r.ok) appendAlbums(more.albums || []);
      } catch (e) { /* keep what we have */ }
      finally { state.busy = false; }
    });
    io.observe(sentinel);

    if (!j.total) {
      const none = document.createElement("div");
      none.className = "qb-empty";
      none.textContent = "Nothing matches these rules right now. That can change as your library grows.";
      body.appendChild(none);
    }
  }

  function actionBtn(label, primary, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "action-btn" + (primary ? " primary" : "");
    b.textContent = label;
    b.addEventListener("click", onClick);
    return b;
  }

  async function playAll(id, kind) {
    try {
      const r = await fetch("/api/live-playlist/albums?id=" + encodeURIComponent(id) + "&max=200", { cache: "no-store" });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
      const offsets = j.offsets || [];
      if (!offsets.length) { window.__showToast("Nothing matches these rules right now", "error"); return; }
      if (window.__playOffsets) await window.__playOffsets(offsets, kind, j.truncated ? j.total : 0);
    } catch (e) { window.__showToast(e.message, "error"); }
  }

  async function del(id, name) {
    const prompt = "Delete “" + name + "”? Only the rules go — your albums and files are untouched.";
    const yes = window.__confirmDialog ? await window.__confirmDialog(prompt) : window.confirm(prompt);
    if (!yes) return;
    try {
      const r = await fetch("/api/live-playlists/delete", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id })
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || ("HTTP " + r.status)); }
      window.__showToast("Deleted “" + name + "”");
      stack = [{ kind: "list" }];
      render();
    } catch (e) { window.__showToast(e.message, "error"); }
  }
})();

/* ------------------------------------------------------------------ */
/*  Favourites (this app's own) + the album context sheet              */
/* ------------------------------------------------------------------ */
(function initFavourites() {
  const overlay = document.getElementById("favourites-overlay");
  const body    = document.getElementById("fav-body");
  const openBtn = document.getElementById("favourites-toggle");
  if (!overlay || !body || !openBtn) return;

  // Keys of everything favourited, so tiles can be marked without asking per
  // tile. Refreshed whenever we change something or open the screen.
  let favKeys = new Set();
  const keyFor = (title, artist) => {
    const norm = (x) => String(x || "").toLowerCase().normalize("NFKD")
      .replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, " ").replace(/\s+/g, " ").trim();
    const t = norm(title);
    return t ? t + "|" + norm(artist) : null;
  };
  async function refreshKeys() {
    try {
      const r = await fetch("/api/favourites/keys", { cache: "no-store" });
      if (r.ok) favKeys = new Set((await r.json()).keys || []);
    } catch (e) { /* keep what we have */ }
  }
  refreshKeys().then(() => repaintMarks());

  const isFav = (a) => { const k = keyFor(a && a.title, a && (a.subtitle || a.artist)); return !!k && favKeys.has(k); };
  window.__isFavourite = isFav;
  window.__favKeyOf = keyFor;
  // Repaint every tile already on screen from the current key set, so marks
  // appear without rebuilding any grid.
  function repaintMarks() {
    document.querySelectorAll(".album[data-fav-key]").forEach(el => {
      el.classList.toggle("is-app-fav", favKeys.has(el.dataset.favKey));
    });
  }
  window.__repaintFavMarks = repaintMarks;
  window.__refreshFavKeys = async () => { await refreshKeys(); repaintMarks(); };

  async function toggleFav(a, want) {
    const r = await fetch("/api/favourites/toggle", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: a.title, subtitle: a.subtitle || a.artist || "", source: a.source || null,
        image_key: a.image_key || null, qobuz_id: a.qobuz_id || null,
        favourite: want,
      }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    const k = keyFor(a.title, a.subtitle || a.artist);
    if (k) { if (j.favourite) favKeys.add(k); else favKeys.delete(k); }
    repaintMarks();
    return j.favourite;
  }
  window.__toggleFavourite = toggleFav;

  // ---- the album context sheet ------------------------------------------
  // Long-press on a LIBRARY album enters multi-select (v1.0.50) — every batch
  // action then lives in the top bar's Options menu. This sheet is what an
  // album with no library offset gets instead: a Qobuz catalogue album can't
  // be selected (nothing keys off), so the sheet is the only place it can be
  // favourited from the screen you found it on.
  function openAlbumSheet(a, opts) {
    opts = opts || {};
    const many = opts.items && opts.items.length > 1 ? opts.items : null;
    const title = many ? many.length + " albums" : (a.title || "Album");
    const subtitle = many ? "" : (a.subtitle || "");
    if (!window.__openLibSheet) return;
    window.__openLibSheet(title, (sheetBody, close) => {
      if (subtitle) {
        const sub = document.createElement("div");
        sub.className = "lib-sheet-note";
        sub.style.marginTop = "0";
        sub.textContent = subtitle;
        sheetBody.appendChild(sub);
      }
      const row = (label, note, onClick) => {
        const b = document.createElement("button");
        b.type = "button"; b.className = "lib-sort-row";
        const txt = document.createElement("span");
        const l = document.createElement("div"); l.className = "lib-sort-label"; l.textContent = label;
        txt.appendChild(l);
        if (note) { const n = document.createElement("div"); n.className = "lib-sort-note"; n.textContent = note; txt.appendChild(n); }
        b.appendChild(txt);
        b.addEventListener("click", async () => { close(); try { await onClick(); } catch (e) { window.__showToast(e.message, "error"); } });
        sheetBody.appendChild(b);
        return b;
      };

      const targets = many || [a];
      const allFav = targets.every(isFav);
      row(allFav && !many ? "Remove from Favourites" : "Add to Favourites",
          "Kept in this app, separate from your Qobuz favourites",
          async () => {
            if (many) {
              const r = await fetch("/api/favourites/add-multi", {
                method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ items: many }),
              });
              const j = await r.json().catch(() => ({}));
              if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
              await refreshKeys();
              repaintMarks();
              window.__showToast("Added " + j.added + " to Favourites");
              if (window.__exitAlbumSelectMode) window.__exitAlbumSelectMode();
            } else {
              const on = await toggleFav(a, allFav ? false : true);
              window.__showToast(on ? "Added to Favourites" : "Removed from Favourites");
              if (opts.onFavChange) opts.onFavChange(on);
            }
          });

      row("Play now", null, () => window.__albumAction(targets, "play_now"));
      row("Add to end of queue", null, () => window.__albumAction(targets, "queue"));
      if (!many && opts.allowSelect !== false) {
        row("Select", "Choose several albums", () => {
          if (window.__enterAlbumSelect) window.__enterAlbumSelect(a, opts.tileEl);
        });
      }
    });
  }
  window.__openAlbumSheet = openAlbumSheet;

  // ---- the Favourites screen --------------------------------------------
  const close = () => { overlay.classList.add("hidden"); document.body.style.overflow = ""; };
  overlay.querySelectorAll("[data-fav-close]").forEach(el => el.addEventListener("click", close));
  const msg = (cls, text) => { body.innerHTML = ""; const d = document.createElement("div"); d.className = cls; d.textContent = text; body.appendChild(d); };

  async function open() {
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    msg("qb-loading", "Loading\u2026");
    await refreshKeys();
    let j;
    try {
      const r = await fetch("/api/favourites", { cache: "no-store" });
      j = await r.json();
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { msg("qb-empty", "Couldn\u2019t load: " + e.message); return; }
    const albums = (j && j.albums) || [];
    if (!albums.length) {
      msg("qb-empty", "No favourites yet. Long-press any album and choose \u201cAdd to Favourites\u201d.");
      return;
    }
    body.innerHTML = "";
    const grid = document.createElement("div");
    grid.className = "album-grid";
    for (const a of albums) {
      const tile = window.__buildAlbumTile(a, () => {
        if (a.offset != null) { close(); window.__openAlbum(a, { source: "home", filter: null }); }
        // An album that has left the library (or was only ever a catalogue
        // album) has no offset to open — say so rather than doing nothing.
        else window.__showToast("That album isn\u2019t in your library right now", "error");
      });
      grid.appendChild(tile);
    }
    body.appendChild(grid);
  }
  openBtn.addEventListener("click", open);
  window.__openFavourites = open;
})();

/* ------------------------------------------------------------------ */
/*  Merged albums — review and undo multi-disc merges                  */
/* ------------------------------------------------------------------ */
(function initMergedAlbums() {
  const overlay = document.getElementById("merged-overlay");
  const body    = document.getElementById("merged-body");
  const openBtn = document.getElementById("merged-albums-toggle");
  if (!overlay || !body || !openBtn) return;

  const close = () => { overlay.classList.add("hidden"); document.body.style.overflow = ""; };
  overlay.querySelectorAll("[data-merged-close]").forEach(el => el.addEventListener("click", close));
  const msg = (cls, text) => { body.innerHTML = ""; const d = document.createElement("div"); d.className = cls; d.textContent = text; body.appendChild(d); };

  async function render() {
    msg("qb-loading", "Loading\u2026");
    let j;
    try {
      const r = await fetch("/api/albums/merges", { cache: "no-store" });
      j = await r.json();
      if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
    } catch (e) { msg("qb-empty", "Couldn\u2019t load: " + e.message); return; }
    const merges = (j && j.merges) || [];
    if (!merges.length) {
      msg("qb-empty", "No merged albums. Select two or more albums, then choose Merge \u2014 useful when your server splits a multi-disc set.");
      return;
    }
    body.innerHTML = "";
    for (const m of merges) {
      const rowEl = document.createElement("div");
      rowEl.className = "merged-row";

      if (m.image_key) {
        const img = document.createElement("img");
        img.className = "merged-row-art"; img.loading = "lazy"; img.alt = "";
        img.src = "/api/image/" + encodeURIComponent(m.image_key) + "?size=200";
        img.onerror = () => img.remove();
        rowEl.appendChild(img);
      }

      const txt = document.createElement("div");
      txt.className = "merged-row-txt";
      const t = document.createElement("div"); t.className = "merged-row-title"; t.textContent = m.title || "Untitled";
      const sub = document.createElement("div"); sub.className = "merged-row-sub"; sub.textContent = m.artist || "";
      const parts = document.createElement("div");
      parts.className = "merged-row-parts";
      // Say plainly when some parts aren't in the library, rather than showing
      // a count that doesn't match what's listed.
      const missing = m.part_count - (m.present || 0);
      parts.textContent = m.parts.map(p => p.title).join("  \u00b7  ") +
        (missing > 0 ? "  \u2014  " + missing + " not in the library" : "");
      txt.appendChild(t); txt.appendChild(sub); txt.appendChild(parts);
      rowEl.appendChild(txt);

      const un = document.createElement("button");
      un.type = "button"; un.className = "action-btn";
      un.style.flex = "none";
      un.textContent = "Unmerge";
      un.addEventListener("click", async () => {
        un.disabled = true;
        try {
          const r = await fetch("/api/albums/merge/" + encodeURIComponent(m.id), { method: "DELETE" });
          const jj = await r.json().catch(() => ({}));
          if (!r.ok) throw new Error(jj.error || ("HTTP " + r.status));
          window.__showToast("Unmerged \u201c" + (m.title || "album") + "\u201d");
          await render();
          if (window.__refreshCurrentView) window.__refreshCurrentView();
        } catch (e) { un.disabled = false; window.__showToast(e.message, "error"); }
      });
      rowEl.appendChild(un);
      body.appendChild(rowEl);
    }
  }

  async function open() {
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    await render();
  }
  openBtn.addEventListener("click", open);
  window.__openMergedAlbums = open;
})();
