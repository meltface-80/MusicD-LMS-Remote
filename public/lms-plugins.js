/*
 * lms-plugins.js — LMS's plugins from the web app (Settings → LMS server → Plugins): what is installed, what each
 * repository offers, updates, and LMS restarted to apply them. The twin of Mallard's Plugins pane; both draw the
 * server's /api/lms/plugins (lib/lms-settings-routes.js), which drives LMS's own plugin page, so the two apps and
 * LMS's page always agree. The server says what each plugin can do (`can`); this file only draws it.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const overlay = document.getElementById("plugins-overlay");
  const body = document.getElementById("plugins-body");
  const tabsEl = document.getElementById("plugins-tabs");
  const filterEl = document.getElementById("plugins-filter");
  const openBtn = document.getElementById("lms-open-plugins");
  if (!overlay || !body) return;

  let tab = "installed";
  let list = null;        // the server's last answer
  let busy = "";          // the plugin being changed
  let restarting = false;
  let lockWas = "";
  const toast = (msg, type) => { if (window.__showToast) window.__showToast(msg, type); };
  const imgSrc = (p) => (p.image_key ? `/api/image/${encodeURIComponent(p.image_key)}?size=96` : p.image_path || "");
  const VERB = { install: "Install", update: "Update", remove: "Remove", enable: "Enable", disable: "Disable" };
  const DOING = { install: "Installing", update: "Updating", remove: "Removing", enable: "Enabling", disable: "Disabling" };

  async function call(url, payload) {
    const r = await fetch(url, payload ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) } : { cache: "no-store" });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) { const e = new Error(j.error || `HTTP ${r.status}`); e.risk = !!j.risk; throw e; }
    return j;
  }

  function open() {
    lockWas = document.body.style.overflow;
    overlay.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    if (filterEl) filterEl.value = "";
    load();
  }
  function close() {
    overlay.classList.add("hidden");
    document.body.style.overflow = lockWas;
  }
  overlay.addEventListener("click", (e) => { if (e.target.closest && e.target.closest("[data-plugins-close]")) close(); });
  document.addEventListener("keydown", (e) => {
    const frame = document.getElementById("lmsset-overlay");
    if (e.key === "Escape" && !overlay.classList.contains("hidden") && (!frame || frame.classList.contains("hidden"))) close();
  });
  if (openBtn) openBtn.addEventListener("click", open);
  if (tabsEl) tabsEl.addEventListener("click", (e) => { const b = e.target.closest("[data-tab]"); if (b) { tab = b.dataset.tab; draw(); } });
  if (filterEl) filterEl.addEventListener("input", () => draw());

  /** A line of words: alone ([clear]), or under what is drawn already (the restart banner must survive an empty list). */
  function say(text, keep) { if (!keep) body.innerHTML = ""; const p = document.createElement("p"); p.className = "qb-notice"; p.textContent = text; body.appendChild(p); }

  async function load() {
    say("Reading LMS’s plugins…");
    try { list = await call("/api/lms/plugins"); } catch (e) { list = null; say(e.message || "LMS didn’t answer"); return; }
    draw();
  }

  /** LMS's own warning, in its words, before it shows a plugin: accepted here for a year, as on LMS's page. */
  function drawRisk() {
    body.innerHTML = "";
    const box = document.createElement("div"); box.className = "plugins-banner";
    const h = document.createElement("strong"); h.textContent = "Plugins are third-party software";
    const p = document.createElement("p");
    p.textContent = "LMS plugins are written by other people, not by the LMS team, and run inside your server: they can reach the network, read your library and settings, and affect how well LMS runs. Install the ones you trust, check what they do, and remove any you stop using.";
    const b = document.createElement("button"); b.type = "button"; b.className = "action-btn primary"; b.textContent = "I understand — show plugins";
    b.addEventListener("click", async () => {
      b.disabled = true;
      try { list = await call("/api/lms/plugins/accept-risk", { accept: true }); draw(); } catch (e) { b.disabled = false; toast(e.message, "error"); }
    });
    box.append(h, p, b);
    body.appendChild(box);
  }

  function restartBanner() {
    const box = document.createElement("div"); box.className = "plugins-banner";
    const p = document.createElement("p");
    p.textContent = restarting ? "LMS is restarting…" : "LMS applies plugin changes when it restarts.";
    box.appendChild(p);
    if (!restarting) {
      const b = document.createElement("button"); b.type = "button"; b.className = "action-btn primary"; b.textContent = "Restart LMS";
      b.addEventListener("click", restart);
      box.appendChild(b);
    }
    return box;
  }

  async function restart() {
    if (!confirm("Restart LMS now? Music stops for a moment while it starts again.")) return;
    restarting = true; draw();
    try { await call("/api/lms/restart", {}); } catch (e) { restarting = false; draw(); toast(e.message, "error"); return; }
    // LMS is gone for a while (it reloads every plugin): ask again until it answers, for up to two minutes.
    const until = Date.now() + 120000;
    await new Promise((r) => setTimeout(r, 6000));
    while (Date.now() < until) {
      try { list = await call("/api/lms/plugins"); restarting = false; draw(); toast("LMS restarted"); return; } catch (e) { /* not back yet */ }
      await new Promise((r) => setTimeout(r, 3000));
    }
    restarting = false; draw(); toast("LMS hasn’t come back yet — try again in a minute", "error");
  }

  function draw() {
    if (!list) return;
    if (list.risk) { if (tabsEl) tabsEl.hidden = true; if (filterEl) filterEl.hidden = true; drawRisk(); return; }
    if (tabsEl) {
      tabsEl.hidden = false;
      const n = (list.updates || []).length;
      for (const b of tabsEl.querySelectorAll("[data-tab]")) {
        b.setAttribute("aria-pressed", String(b.dataset.tab === tab));
        if (b.dataset.tab === "installed") b.textContent = n ? `Installed · ${n} update${n === 1 ? "" : "s"}` : "Installed";
      }
    }
    if (filterEl) filterEl.hidden = false;
    body.innerHTML = "";
    if (list.restart || restarting) body.appendChild(restartBanner());
    const q = ((filterEl && filterEl.value) || "").trim().toLowerCase();
    const match = (p) => !q || `${p.title} ${p.desc} ${p.creator} ${p.name}`.toLowerCase().includes(q);
    if (tab === "installed") {
      const on = (list.enabled || []).filter(match), off = (list.disabled || []).filter(match);
      // Updates first: they are what needs doing.
      on.sort((a, b) => Number(b.update) - Number(a.update));
      group("Active", on);
      group("Inactive", off);
      if (!on.length && !off.length) say(q ? "No installed plugin matches." : "No plugins installed.", true);
    } else {
      const byRepo = new Map();
      for (const p of (list.available || []).filter(match)) {
        if (!byRepo.has(p.repo)) byRepo.set(p.repo, []);
        byRepo.get(p.repo).push(p);
      }
      for (const [repo, ps] of byRepo) group(repo || "Other", ps);
      if (!byRepo.size) say(q ? "Nothing available matches." : "LMS’s repositories offer nothing more to install.", true);
    }
  }

  function group(title, ps) {
    if (!ps.length) return;
    const h = document.createElement("h3"); h.className = "plugins-group"; h.textContent = title;
    body.appendChild(h);
    for (const p of ps) body.appendChild(row(p));
  }

  function row(p) {
    const r = document.createElement("div"); r.className = "plugin-row"; r.dataset.plugin = p.name;
    const src = imgSrc(p);
    const ico = document.createElement(src ? "img" : "span");
    ico.className = "app-ico plugin-ico";
    if (src) { ico.alt = ""; ico.loading = "lazy"; ico.src = src; ico.onerror = () => { ico.removeAttribute("src"); ico.classList.add("is-blank"); }; }
    else { ico.classList.add("is-blank"); ico.textContent = (p.title || "?").trim().charAt(0).toUpperCase(); }
    const meta = document.createElement("div"); meta.className = "plugin-meta";
    const t = document.createElement("div"); t.className = "plugin-title";
    t.textContent = p.title;
    const v = document.createElement("span"); v.className = "plugin-ver"; v.textContent = p.version ? " v" + p.version : "";
    t.appendChild(v);
    meta.appendChild(t);
    if (p.error) { const e = document.createElement("div"); e.className = "plugin-error"; e.textContent = p.error; meta.appendChild(e); }
    if (p.desc && p.desc !== p.error) { const d = document.createElement("div"); d.className = "plugin-desc"; d.textContent = p.desc; meta.appendChild(d); }
    const by = [p.creator, p.builtIn ? "Part of LMS" : ""].filter(Boolean).join(" · ");
    if (by) { const s = document.createElement("div"); s.className = "app-sub"; s.textContent = by; meta.appendChild(s); }
    const btns = document.createElement("div"); btns.className = "plugin-btns";
    if (busy === p.name) {
      const s = document.createElement("span"); s.className = "app-sub"; s.textContent = "Working…"; btns.appendChild(s);
    } else {
      for (const a of p.can || []) {
        const b = document.createElement("button"); b.type = "button";
        b.className = "action-btn" + (a === "install" || a === "update" ? " primary" : "");
        b.textContent = VERB[a] || a;
        b.disabled = !!busy || restarting;
        b.addEventListener("click", () => change(p, a));
        btns.appendChild(b);
      }
      if (p.settings && p.state === "enabled") {
        const b = document.createElement("button"); b.type = "button"; b.className = "action-btn"; b.textContent = "Settings";
        b.addEventListener("click", () => openSettings(p));
        btns.appendChild(b);
      }
      if (p.homepage) {
        const a = document.createElement("a"); a.className = "plugin-link"; a.href = p.homepage; a.target = "_blank"; a.rel = "noopener"; a.textContent = "About";
        btns.appendChild(a);
      }
    }
    meta.appendChild(btns);
    r.append(ico, meta);
    return r;
  }

  async function change(p, action) {
    if (action === "remove" && !confirm(`Remove ${p.title} from LMS?`)) return;
    if (action === "disable" && !confirm(`Switch ${p.title} off? It stays installed; switch it on again any time.`)) return;
    busy = p.name; draw();
    toast(`${DOING[action] || action} ${p.title}…`);
    try {
      list = await call("/api/lms/plugins", { name: p.name, action });
      toast(list.restart ? `${p.title}: done — restart LMS to apply it` : `${p.title}: done`);
    } catch (e) {
      if (e.risk) { busy = ""; return load(); }
      toast(e.message || "LMS didn’t take that", "error");
    }
    busy = ""; draw();
  }

  /** A plugin's own settings page, in the same frame as LMS's settings (served through this server's LMS proxy). */
  function openSettings(p) {
    const frameOverlay = document.getElementById("lmsset-overlay");
    const frame = document.getElementById("lmsset-frame");
    if (!frameOverlay || !frame) { window.open(p.settings, "_blank", "noopener"); return; }
    frame.src = p.settings;
    frameOverlay.classList.remove("hidden");
  }

  window.__openPlugins = open;
})();
