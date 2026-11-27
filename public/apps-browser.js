/*
 * apps-browser.js — every LMS plugin's menus in the web app: the side menu's "Apps & radio", the twin of Mallard's
 * Apps and Radio screens (AppsScreen.kt), over the same server engine (lib/apps.js; /api/apps, /api/apps/browse,
 * /api/apps/act, /api/apps/search). The page holds only the engine's opaque tokens, never an LMS command.
 *
 * A menu's rows, as the engine shapes them (lib/apps.js shapeRow):
 *   node      opens its menu                       playable  tap plays (a touch-to-play row) or opens; ▶ and ⋯
 *   search    asks for a term in its own words    choice    a radio button or checkbox, set on a tap
 *   link      opens in a new tab                   text      just words
 * An expired token (410: menus live 30 minutes, under LMS's own cache) goes back to the list and says so; a plugin
 * that didn't answer (503) says that, rather than failing silently.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const overlay = document.getElementById("apps-overlay");
  const body = document.getElementById("apps-body");
  const titleEl = document.getElementById("apps-title");
  const backBtn = document.getElementById("apps-back");
  const tabsEl = document.getElementById("apps-tabs");
  const row = document.getElementById("menu-item-apps");
  if (!overlay || !body) return;

  const PAGE = 100;
  // The menus opened, deepest last: { title, token, term, start, menu } — Back pops one.
  let stack = [];
  let kind = "app";
  let lockWas = "";

  const zone = () => { const z = document.getElementById("zone-select"); return (z && z.value) || ""; };
  const toast = (msg, type) => { if (window.__showToast) window.__showToast(msg, type); };
  const imgSrc = (r, size) => (r.image_key ? `/api/image/${encodeURIComponent(r.image_key)}?size=${size}` : r.image_path || "");
  const CHEVRON = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="9 18 15 12 9 6"/></svg>';
  const PLAY = '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>';
  const MORE = '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>';

  async function call(url, body) {
    const r = await fetch(url, body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : { cache: "no-store" });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) { const e = new Error(j.error || `HTTP ${r.status}`); e.status = r.status; e.expired = !!j.expired; throw e; }
    return j;
  }
  /** A failure said in words: an expired menu goes back to the list. */
  function failed(e) {
    if (e.status === 410 || e.expired) { toast("That menu has expired — opening it again"); showList(); return; }
    toast(e.message || "The plugin didn’t answer", "error");
  }

  function open() {
    lockWas = document.body.style.overflow;
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    showList();
  }
  function close() {
    overlay.classList.add("hidden");
    document.body.style.overflow = lockWas;
    stack = [];
  }
  overlay.addEventListener("click", (e) => { if (e.target.closest && e.target.closest("[data-apps-close]")) close(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !overlay.classList.contains("hidden")) { if (stack.length) back(); else close(); } });
  if (backBtn) backBtn.addEventListener("click", back);
  if (row) row.addEventListener("click", () => { const m = document.getElementById("menu-overlay"); if (m) m.querySelector("[data-menu-close]")?.click(); open(); });
  if (tabsEl) tabsEl.addEventListener("click", (e) => { const b = e.target.closest("[data-kind]"); if (b) { kind = b.dataset.kind; showList(); } });

  function head(title) {
    titleEl.textContent = title;
    if (backBtn) backBtn.hidden = !stack.length;
    if (tabsEl) {
      tabsEl.hidden = !!stack.length;
      for (const b of tabsEl.querySelectorAll("[data-kind]")) b.setAttribute("aria-pressed", String(b.dataset.kind === kind));
    }
  }
  function say(text) { body.innerHTML = ""; const p = document.createElement("p"); p.className = "qb-notice"; p.textContent = text; body.appendChild(p); }

  /** The plugins LMS offers this player (apps) or its radio. */
  async function showList() {
    stack = [];
    head("Apps & radio");
    say("Reading your server’s apps…");
    let j;
    try { j = await call("/api/apps?zone=" + encodeURIComponent(zone())); } catch (e) { say(e.message || "Couldn’t read the apps"); return; }
    const list = (kind === "radio" ? j.radio : j.apps) || [];
    body.innerHTML = "";
    if (!list.length) { say(kind === "radio" ? "LMS offers no radio here." : "No apps yet — install plugins in Settings → LMS server → Server settings, then come back."); return; }
    const box = document.createElement("div"); box.className = "qb-nodes";
    for (const a of list) box.appendChild(nodeRow(a.name, imgSrc(a, 96), () => push(a.name, a.go)));
    body.appendChild(box);
  }

  function nodeRow(title, src, onTap) {
    const b = document.createElement("button"); b.type = "button"; b.className = "qb-node app-node";
    if (src) { const i = document.createElement("img"); i.className = "app-ico"; i.alt = ""; i.loading = "lazy"; i.src = src; i.onerror = () => i.remove(); b.appendChild(i); }
    const t = document.createElement("span"); t.className = "qb-node-title"; t.textContent = title || "…";
    const c = document.createElement("span"); c.className = "qb-chevron"; c.innerHTML = CHEVRON;
    b.append(t, c);
    b.addEventListener("click", onTap);
    return b;
  }

  /** Open a menu (with a search term, for a search row), one level deeper. */
  async function push(title, token, term) {
    stack.push({ title, token, term: term || "", start: 0, menu: null });
    await load(false);
  }
  async function back() { stack.pop(); if (!stack.length) return showList(); await load(false); }

  /** The top menu read (again, with [more]: its next page added). */
  async function load(more) {
    const top = stack[stack.length - 1];
    head(top.title);
    if (!more) say("Reading…");
    const start = more ? top.menu.rows.length : 0;
    let j;
    try {
      j = top.term ? await call("/api/apps/search", { token: top.token, term: top.term, zone_or_output_id: zone(), start, count: PAGE })
        : await call(`/api/apps/browse?token=${encodeURIComponent(top.token)}&zone=${encodeURIComponent(zone())}&start=${start}&count=${PAGE}`);
    } catch (e) { if (!more) stack.pop(); failed(e); if (!more && stack.length) load(false); else if (!more) showList(); return; }
    const m = j.menu || j;
    if (more && top.menu) top.menu.rows = top.menu.rows.concat(m.rows || []); else top.menu = m;
    if (m.title && !more) head(m.title);
    draw(top);
  }

  function draw(top) {
    const m = top.menu;
    body.innerHTML = "";
    if (m.notice) { const p = document.createElement("p"); p.className = "qb-notice"; p.textContent = m.notice; body.appendChild(p); }
    const rows = m.rows || [];
    if (!rows.length && !m.notice) say("Nothing here.");
    const grid = m.layout === "grid";
    const box = document.createElement("div"); box.className = grid ? "qb-grid" : "qb-nodes";
    for (const r of rows) box.appendChild(grid && (r.kind === "playable" || r.kind === "node") ? tile(r) : line(r));
    body.appendChild(box);
    if (rows.length < (m.total || 0)) {
      const b = document.createElement("button"); b.type = "button"; b.className = "qb-act-btn app-more"; b.textContent = `More (${rows.length} of ${m.total})`;
      b.addEventListener("click", () => load(true));
      body.appendChild(b);
    }
  }

  /** A row's tap: open it, play it, ask for a term, set it, or follow its link. */
  function tap(r) {
    if (r.kind === "node" && r.go) return push(r.title, r.go);
    if (r.kind === "playable") return r.tap_plays || !r.go ? act(r.play || r.add, "Playing") : push(r.title, r.go);
    if (r.kind === "search" && r.search) {
      const term = window.prompt(r.prompt || r.title || "Search");
      if (term && term.trim()) push(`“${term.trim()}”`, r.search, term.trim());
      return;
    }
    if (r.kind === "choice" && r.do) return act(r.do, null).then(() => load(false));
    if (r.kind === "link" && r.weblink) window.open(r.weblink, "_blank", "noopener");
    if (r.more) return push(r.title, r.more);
  }

  function line(r) {
    const b = document.createElement("div"); b.className = "qb-node app-row" + (r.kind === "text" ? " is-text" : "");
    b.setAttribute("role", r.kind === "text" ? "note" : "button");
    if (r.kind !== "text") b.tabIndex = 0;
    const src = imgSrc(r, 120);
    if (src) { const i = document.createElement("img"); i.className = "app-ico"; i.alt = ""; i.loading = "lazy"; i.src = src; i.onerror = () => i.remove(); b.appendChild(i); }
    if (r.kind === "choice") { const c = document.createElement("span"); c.className = "app-choice" + (r.choice === "checkbox" ? " box" : "") + (r.selected ? " on" : ""); b.appendChild(c); }
    const meta = document.createElement("span"); meta.className = "app-meta";
    const t = document.createElement("span"); t.className = "qb-node-title"; t.textContent = r.title || "…"; meta.appendChild(t);
    if (r.subtitle) { const s = document.createElement("span"); s.className = "app-sub"; s.textContent = r.subtitle; meta.appendChild(s); }
    b.appendChild(meta);
    if (r.kind === "playable") b.appendChild(playButtons(r));
    else if (r.kind === "node" || r.kind === "search" || r.kind === "link") { const c = document.createElement("span"); c.className = "qb-chevron"; c.innerHTML = CHEVRON; b.appendChild(c); }
    if (r.kind !== "text") {
      b.addEventListener("click", (e) => { if (!e.target.closest(".app-btn")) tap(r); });
      b.addEventListener("keydown", (e) => { if (e.key === "Enter") tap(r); });
    }
    return b;
  }

  function tile(r) {
    const b = document.createElement("button"); b.type = "button"; b.className = "qb-tile app-tile";
    const art = document.createElement("span"); art.className = "app-tile-art";
    const src = imgSrc(r, 300);
    if (src) { const i = document.createElement("img"); i.alt = ""; i.loading = "lazy"; i.src = src; i.onerror = () => i.remove(); art.appendChild(i); }
    const t = document.createElement("span"); t.className = "app-tile-t"; t.textContent = r.title || "";
    b.append(art, t);
    if (r.subtitle) { const s = document.createElement("span"); s.className = "app-sub"; s.textContent = r.subtitle; b.appendChild(s); }
    b.addEventListener("click", () => tap(r));
    return b;
  }

  /** ▶ plays now; ⋯ offers Play next, Add to queue and the plugin's own More. */
  function playButtons(r) {
    const box = document.createElement("span"); box.className = "app-btns";
    if (r.play) {
      const p = document.createElement("button"); p.type = "button"; p.className = "app-btn"; p.setAttribute("aria-label", "Play now"); p.innerHTML = PLAY;
      p.addEventListener("click", () => act(r.play, "Playing"));
      box.appendChild(p);
    }
    const items = [];
    if (r.insert) items.push({ label: "Play next", act: () => act(r.insert, "Playing next") });
    if (r.add) items.push({ label: "Add to queue", act: () => act(r.add, "Added to the queue") });
    if (r.more) items.push({ label: "More…", act: () => push(r.title, r.more) });
    if (items.length) {
      const m = document.createElement("button"); m.type = "button"; m.className = "app-btn"; m.setAttribute("aria-label", "More"); m.innerHTML = MORE;
      m.addEventListener("click", () => menu(m, items));
      box.appendChild(m);
    }
    return box;
  }

  /** The app's one dropdown (app.js openOptionsMenu: placed off the button, a backdrop, closed with the modal). */
  function menu(anchor, items) {
    if (!window.__openOptionsMenu) return items[0] && items[0].act();
    window.__openOptionsMenu(anchor, (m) => {
      for (const it of items) {
        const b = document.createElement("button"); b.type = "button"; b.className = "lib-sort-row"; b.setAttribute("role", "menuitem");
        const l = document.createElement("div"); l.className = "lib-sort-label"; l.textContent = it.label;
        const t = document.createElement("span"); t.appendChild(l); b.appendChild(t);
        b.addEventListener("click", () => { if (window.__closeOptionsMenu) window.__closeOptionsMenu(); it.act(); });
        m.appendChild(b);
      }
    }, "Play options");
  }

  /** Run a row's action on the chosen player; its answer said, and where the plugin asks to go next, gone. */
  async function act(token, said) {
    if (!token) return;
    if (!zone()) { toast("Pick a player first"); return; }
    try {
      const j = await call("/api/apps/act", { token, zone_or_output_id: zone() });
      if (j.menu) { stack.push({ title: j.menu.title || "", token, term: "", start: 0, menu: j.menu }); head(j.menu.title || ""); draw(stack[stack.length - 1]); return; }
      if (j.message || said) toast(j.message || said);
      if (j.next_window === "parent" || j.next_window === "grandparent") back();
      else if (j.next_window === "refresh" || j.next_window === "refreshOrigin") load(false);
      else if (j.next_window === "home") close();
    } catch (e) { failed(e); }
  }

  window.__openApps = open;
})();
