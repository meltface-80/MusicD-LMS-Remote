/*
 * away-settings.js — Settings → Away from home: Tailscale built into the server (lib/tailscale.js, /api/tailscale…),
 * ported from Mandarin's settings-away.js. Sign in once and the server is a machine on your tailnet by itself; the
 * phone (Mallard, or any device with Tailscale) then reaches it anywhere. Mallard's Away from home pane is its twin.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const body = document.getElementById("away-pane-body");
  const pane = document.querySelector('.settings-pane[data-pane="away"]');
  const navItem = document.querySelector('.settings-nav-item[data-pane="away"]');
  if (!body || !pane || !navItem) return;
  const esc = (v) => String(v == null ? "" : v).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  let st = null, busy = false, err = "";

  async function load() {
    try {
      const r = await fetch("/api/tailscale", { cache: "no-store" });
      if (!r.ok) throw new Error("HTTP " + r.status);
      st = await r.json();
    } catch (e) { st = null; }
    render();
  }

  async function act(what, payload) {
    if (busy) return;
    busy = true; err = ""; render();
    try {
      const r = await fetch("/api/tailscale/" + what, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload || {}) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || "HTTP " + r.status);
      st = j;
      if (what === "login" && j.auth_url) window.open(j.auth_url, "_blank", "noopener");
    } catch (e) { err = e.message; }
    busy = false; render();
  }

  const row = (label, value) => '<div class="settings-row"><span class="settings-label">' + label + '</span><span class="settings-sub">' + value + "</span></div>";
  const note = (text) => '<div class="settings-note">' + text + "</div>";

  function render() {
    if (!st) { body.innerHTML = note("Couldn’t ask the server."); return; }
    if (!st.available) {
      body.innerHTML = note("Tailscale isn’t built into this install (it is in the Docker image from this version on). " +
        "Tailscale installed on the computer the server runs on works as before — see the README’s Away from home.");
      return;
    }
    const toggle = '<label class="switch"><input type="checkbox" aria-label="Built-in Tailscale" data-away-enable' + (st.enabled ? " checked" : "") + (busy ? " disabled" : "") + '>' +
      '<span class="switch-track"><span class="switch-thumb"></span></span></label>';
    let html = '<div class="settings-block"><div class="settings-row"><span class="settings-label">Built-in Tailscale</span>' + toggle + "</div>";
    const state = !st.enabled ? "off" : st.state;
    const words = {
      off: "Off", Stopped: "Starting…", Starting: "Starting…", NoState: "Starting…",
      NeedsLogin: "Not signed in yet", NeedsMachineAuth: "Waiting for approval in the Tailscale admin console",
      Running: st.serving ? "Connected" : "Connecting…",
    };
    html += row("Status", esc(words[state] || state));
    if (state === "Running" && (st.online === false || (st.health && st.health.length))) {
      html += '<div class="settings-note away-error">' + esc(st.online === false ? "Running, but not online on the tailnet — the server is rebinding it, then starting it afresh" : "Tailscale warns: " + st.health.join("; ")) + "</div>";
    }
    if (state === "Running") {
      if (st.dns_name) html += row("Name", esc(st.dns_name));
      if (st.address) html += row("Address", esc(st.address.replace(/^http:\/\//, "")));
      html += '<div class="settings-row"><span class="settings-label"></span><button type="button" class="action-btn" data-away-act="logout"' + (busy ? " disabled" : "") + ">Sign out of Tailscale</button></div>";
      html += note("On your tailnet as “" + esc(st.hostname) + "”. Mallard uses it by itself away from home once it has been home once to learn the address. Any other device signed in to the same Tailscale account — an iPhone, a laptop — opens " + esc(st.address || "the address above") + ".");
    } else if (st.enabled) {
      const link = st.auth_url
        ? '<a class="action-btn primary" href="' + esc(st.auth_url) + '" target="_blank" rel="noopener">Sign in to Tailscale</a>'
        : '<button type="button" class="action-btn primary" data-away-act="login"' + (busy ? " disabled" : "") + ">Sign in to Tailscale</button>";
      html += '<div class="settings-row"><span class="settings-label"></span>' + link + "</div>";
      html += note("Sign in with the same Tailscale account as your phone. The server then joins your tailnet as “" + esc(st.hostname) + "” — nothing else to install, no ports to open. Free for personal use. Only your own devices on the tailnet are let in.");
    }
    if (err || st.error) html += '<div class="settings-note away-error">' + esc(err || st.error) + "</div>";
    body.innerHTML = html + "</div>";
  }

  body.addEventListener("change", (e) => { const t = e.target.closest("[data-away-enable]"); if (t) act("enable", { on: t.checked }); });
  body.addEventListener("click", (e) => { const b = e.target.closest("[data-away-act]"); if (b) act(b.getAttribute("data-away-act")); });
  navItem.addEventListener("click", load);
  // While it is open: signing in happens in another tab, so look now and then.
  setInterval(() => { if (!pane.classList.contains("hidden") && !busy) load(); }, 3000);
})();
