/*
 * touch-nav.js — the row along the foot of the screen on a phone or a tablet (owner: the web page and Mallard are
 * exact copies; Mallard's TouchNav.kt, Mandarin's touchnav.js): Menu, Home, Library, Shelf while it is switched on
 * (shelf-entry.js shows it), and Search. A desktop never shows it (style.css's "touch layout", the same media test as
 * Mandarin's). Each button works the page's own control, so there is one way to do each thing: Menu is #menu-toggle's,
 * Library is Home's "Library" heading's, Shelf is the side menu's own, Search is Home's field.
 *
 * It tells the layout, on <html>, where the page is (tn-home, tn-lib: that button in brass), when something covers
 * the screen (tn-over: an album's page or Now playing; the row steps aside, as the mini player then does), and when the
 * search is open (tn-search: Home's field shown, which otherwise stays out of sight on a touch screen).
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const nav = document.getElementById("touch-nav");
  if (!nav) return;
  const root = document.documentElement;
  const byId = (id) => document.getElementById(id);
  const home = byId("home-view"), lib = byId("library-controls"), modal = byId("album-modal");
  const shown = (el) => !!el && !el.classList.contains("hidden");

  function sync() {
    root.classList.toggle("tn-home", shown(home));
    root.classList.toggle("tn-lib", shown(lib));
    root.classList.toggle("tn-over", shown(modal));
  }
  for (const el of [home, lib, modal]) if (el) new MutationObserver(sync).observe(el, { attributes: true, attributeFilter: ["class"] });
  sync();

  const toTop = () => { const m = document.querySelector("main"); if (m) m.scrollTo({ top: 0, behavior: "smooth" }); };
  const on = (id, fn) => { const b = byId(id); if (b) b.addEventListener("click", fn); };
  on("tnav-menu", () => { const m = byId("menu-toggle"); if (m) m.click(); });
  on("tnav-home", () => { if (shown(home)) toTop(); else if (window.__showHome) window.__showHome(); });
  on("tnav-library", () => {
    if (shown(lib)) { toTop(); return; }
    const heading = byId("home-library-title");
    if (heading) heading.click(); else if (window.__showLibraryWall) window.__showLibraryWall();
  });
  on("tnav-shelf", () => { location.href = "/shelf"; });
  // Search (owner: "the search bar isn't to show permanently — that's what the magnifying glass is for"): hidden until
  // the row's glass opens it (tn-search) with the cursor in it; its ✕ clears the words, and with none closes it; a tap
  // anywhere else closes it while it is empty. A desktop keeps its field in the bar as before.
  const touch = window.matchMedia("(hover: none), (pointer: coarse)");
  const field = () => byId("search-input");
  const openSearch = () => {
    if (!shown(home) && window.__showHome) window.__showHome();
    root.classList.add("tn-search");
    const f = field();
    if (f) f.focus();
  };
  const closeSearch = () => { root.classList.remove("tn-search"); const f = field(); if (f) f.blur(); };
  on("tnav-search", (e) => {
    e.stopPropagation();   // the page closes its search on a tap outside it: this one opens it
    if (root.classList.contains("tn-search")) { const f = field(); if (f) f.focus(); } else openSearch();
  });
  const clearBtn = byId("search-clear");
  if (clearBtn) clearBtn.addEventListener("click", (e) => {
    const f = field();
    if (!touch.matches || !f || f.value !== "") return;   // words in it: the page's own ✕ clears them
    e.stopImmediatePropagation();
    closeSearch();
  }, true);
  document.addEventListener("pointerdown", (e) => {
    if (!touch.matches || !root.classList.contains("tn-search")) return;
    const f = field();
    if (f && f.value !== "") return;
    const t = e.target;
    if (t instanceof Element && (t.closest("#topbar-search") || t.closest("#tnav-search"))) return;
    closeSearch();
  }, true);
})();
