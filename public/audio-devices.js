/*
^ * audio-devices.js — Settings → Audio Devices (owner request: Playback and Player settings "condensed into one settings page called Audio
 * Devices like Mandarin", the web app a copy of Mallard's): the zone the screens play in at the top (the app's own
 * #zone-select, which every screen reads), then every Lyrion player as Mandarin's card — its kind's icon, its name, its
 * model, what it is doing, › — and a tap opens the player's own page: On (Lyrion's power), random album radio, its name
 * (renamed in Lyrion), About, then Lyrion's settings for it (shuffle, repeat, Don't Stop The Music, crossfade, volume
 * levelling, volume control, on power on, sync). Mallard's SettingsAudio.kt / SettingsDevice.kt and core AudioDevices.kt
 * are the reference: the same rows, words and order. A file of its own because app.js only shrinks.
 */
(function () {
  "use strict";
  const ZONE_INFO = "The player Play, Queue and the transport controls send to — on every screen. The same as Play on.";
  const DEVICES_INFO = "Every player Lyrion knows. Tap one for its own page: switch it on or off, random album radio, its name, and Lyrion's settings for it — crossfade, volume levelling, Don't Stop The Music, sync.";
  const RADIO_INFO = "When this player's queue runs out, keep it going by queueing whole random albums from your library rather than shuffling single tracks. It stands down on its own while Lyrion's own Don't Stop The Music is on for the player, so the two never fight over the queue.";
  const ICONS = {
    phone: "M9,2 H15 A2,2,0,0,1,17,4 V20 A2,2,0,0,1,15,22 H9 A2,2,0,0,1,7,20 V4 A2,2,0,0,1,9,2 Z M11,18 A1,1,0,1,0,13,18 A1,1,0,1,0,11,18 Z",
    upnp: "M4,7 H20 A2,2,0,0,1,22,9 V15 A2,2,0,0,1,20,17 H4 A2,2,0,0,1,2,15 V9 A2,2,0,0,1,4,7 Z M15,12 A2,2,0,1,0,19,12 A2,2,0,1,0,15,12 Z M5,12 H12",
  };
  const ch = (pairs) => pairs.map(([v, t]) => ({ v: String(v), t }));
  const OFF_ON = ch([[0, "Off"], [1, "On"]]);
  /** Lyrion's prefs shown on a player's page, as Mallard's PREF_ROWS: only those the player has. */
  const PREF_ROWS = [
    ["transitionType", "Crossfade", "How one track blends into the next.", ch([[0, "None"], [1, "Crossfade"], [2, "Fade in"], [3, "Fade out"], [4, "Fade in & out"]])],
    ["transitionDuration", "Crossfade seconds", "Length of the crossfade or fade.", ch(Array.from({ length: 11 }, (_, i) => [i, i + " s"]))],
    ["transitionSmart", "Smart crossfade", "Skip the crossfade between consecutive tracks of the same album (gapless stays gapless).", OFF_ON],
    ["replayGainMode", "Volume levelling", "Use ReplayGain tags to even out loudness between tracks or albums.", ch([[0, "Off"], [1, "Track gain"], [2, "Album gain"], [3, "Smart gain"]])],
    ["digitalVolumeControl", "Volume control", "Fix the output at 100% if your amplifier or DAC controls loudness, or you need perfect digital passthrough.", ch([[0, "Fixed at 100%"], [1, "Volume adjusts outputs"]])],
    ["powerOnResume", "On power on", "What playback does when the player is switched off and back on.", ch([["PauseOff-PlayOn", "Pause · resume"], ["PauseOff-NoneOn", "Pause · stay paused"], ["StopOff-PlayOn", "Stop · play"], ["StopOff-NoneOn", "Stop · do nothing"], ["StopOff-ResetPlayOn", "Reset · play"], ["StopOff-ResetOn", "Reset · do nothing"]])],
    ["syncVolume", "Sync volume", "Volume changes apply to the whole sync group.", OFF_ON],
    ["syncPower", "Sync power", "Power state follows the sync group.", OFF_ON],
  ];

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const toast = (msg, kind) => { if (window.__showToast) window.__showToast(msg, kind); };
  const show = (pane) => { if (window.__settingsShow) window.__settingsShow(pane); };
  const visible = (pane) => { const v = document.querySelector(`.settings-view[data-pane="${pane}"]`); return !!v && !v.classList.contains("hidden"); };
  const enc = encodeURIComponent;

  /** A player as its card shows it (core AudioDevices.parse). */
  function row(z) {
    const id = String(z.zone_id || "");
    const phone = /^02:6d:/i.test(id);
    const power = typeof z.power === "boolean" ? z.power : null;
    const connected = z.connected !== false;
    const state = !connected ? "Offline" : power === false ? "Off" : z.state === "playing" ? "Playing" : z.state === "paused" ? "Paused" : "Idle";
    return { id, name: z.display_name || id, model: z.model || "", state, power, phone, playing: state === "Playing" };
  }

  /* ---- the list ---------------------------------------------------------- */
  let listTimer = null;
  async function loadList() {
    const list = $("ad-list");
    if (!list) return;
    let rows = null;
    try { const r = await fetch("/api/zones", { cache: "no-store" }); if (r.ok) rows = ((await r.json()).zones || []).map(row); } catch (e) { /* kept */ }
    if (!rows) { if (!list.children.length) list.innerHTML = '<div class="settings-note">Asking Lyrion…</div>'; return; }
    if (!rows.length) { list.innerHTML = '<div class="settings-note">Lyrion knows no players yet. Switch one on.</div>'; return; }
    const chosen = ($("zone-select") || {}).value || "";
    list.innerHTML = rows.map((d) => `<button type="button" class="ad-card${d.id === chosen ? " is-chosen" : ""}${d.state === "Offline" ? " is-offline" : d.state === "Off" ? " is-off" : ""}" data-device="${esc(d.id)}">` +
      `<span class="ad-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${ICONS[d.phone ? "phone" : "upnp"]}"/></svg></span>` +
      `<span class="ad-txt"><span class="ad-name">${esc(d.name)}</span>${d.model ? `<span class="ad-sub">${esc(d.model)}</span>` : ""}</span>` +
      `<span class="ad-state${d.playing ? " is-playing" : ""}">${d.state.toUpperCase()}</span><span class="ad-caret" aria-hidden="true">›</span></button>`).join("");
  }
  function open() {
    loadList();
    clearInterval(listTimer);
    listTimer = setInterval(() => { if (visible("playback")) loadList(); else if (!visible("device")) clearInterval(listTimer); }, 10000);
  }

  /* ---- a player's page ---------------------------------------------------- */
  let current = null, devTimer = null;
  const info = (text) => text ? ` <button class="settings-info-btn" data-info="${esc(text)}" aria-label="Info">ⓘ</button>` : "";
  const title = (t) => `<div class="ad-title">${esc(t)}</div>`;
  const toggleRow = (label, help, on, key) => `<div class="settings-row"><span class="settings-label">${esc(label)}${info(help)}</span>` +
    `<label class="switch"><input type="checkbox" data-ad="${key}"${on ? " checked" : ""}><span class="switch-track"><span class="switch-thumb"></span></span></label></div>`;
  function selectRow(label, help, choices, value, key) {
    const v = String(value == null ? "" : value);
    const opts = choices.some((c) => c.v === v) ? choices : choices.concat([{ v, t: v }]);
    return `<div class="settings-row"><span class="settings-label">${esc(label)}${info(help)}</span><div class="settings-select-wrap">` +
      `<select class="settings-select" data-ad="${esc(key)}">${opts.map((c) => `<option value="${esc(c.v)}"${c.v === v ? " selected" : ""}>${esc(c.t)}</option>`).join("")}</select>` +
      `<svg class="settings-caret" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg></div></div>`;
  }

  function render(s, radio) {
    const body = $("ad-dev-body");
    if (!body || !s) return;
    const phone = /^02:6d:/i.test(current);
    const power = typeof s.power === "boolean" ? s.power : null;
    const parts = [];
    parts.push('<div class="settings-block">' + (!phone && power != null ? toggleRow(power ? "On" : "Off", "", power, "power") : "") +
      toggleRow("Random album radio", RADIO_INFO, radio === true, "radio") + "</div>");
    parts.push('<div class="settings-divider"></div><div class="settings-block">' + title("Name") + (phone
      ? '<div class="settings-note">A phone\'s own player, named after the phone.</div>'
      : `<input type="text" class="settings-token-input ad-rename" data-ad="name" maxlength="60" value="${esc(s.name || "")}" aria-label="Name in Lyrion"><div class="settings-note">Press Enter to rename it in Lyrion.</div>`) + "</div>");
    parts.push('<div class="settings-divider"></div><div class="settings-block">' + title("About") +
      [["Model", s.model || ""], ["Player id", current]].filter(([, v]) => v).map(([k, v]) => `<div class="ad-about"><span>${esc(k)}</span><span>${esc(v)}</span></div>`).join("") + "</div>");
    const play = [];
    const m = s.modes || {};
    if (m.shuffle != null) play.push(selectRow("Shuffle", "Shuffle mode for this player's queue.", ch([[0, "Off"], [1, "By song"], [2, "By album"]]), m.shuffle, "shuffle"));
    if (m.repeat != null) play.push(selectRow("Repeat", "Repeat mode for this player's queue.", ch([[0, "Off"], [1, "One song"], [2, "All"]]), m.repeat, "repeat"));
    const d = s.dstm || {};
    if (Array.isArray(d.options) && d.options.length) play.push(selectRow("Don't Stop The Music", "When the queue runs out, Lyrion keeps playing using the mix chosen here. This is Lyrion's own feature.",
      (d.options.some((o) => String(o.key) === "0") ? [] : [{ v: "0", t: "Off" }]).concat(d.options.map((o) => ({ v: String(o.key), t: o.text }))),
      d.current == null ? "0" : d.current, "dstm"));
    const prefs = s.prefs || {};
    for (const [pref, name, help, choices] of PREF_ROWS) if (prefs[pref] != null) play.push(selectRow(name, help, choices, prefs[pref], "pref:" + pref));
    if (play.length) parts.push('<div class="settings-divider"></div><div class="settings-block">' + title("Playback") + play.join("") + "</div>");
    const sync = s.sync || {};
    if (Array.isArray(sync.others) && sync.others.length) parts.push('<div class="settings-divider"></div><div class="settings-block">' +
      selectRow("Sync with", "Group this player with another for synchronous playback.", [{ v: "", t: "Not synced" }].concat(sync.others.map((o) => ({ v: String(o.id), t: o.name }))),
        (sync.members || [])[0] || "", "sync") + "</div>");
    body.innerHTML = parts.join("");
  }

  async function loadDevice() {
    if (!current) return;
    const id = current;
    try {
      const [sr, rr] = await Promise.all([fetch(`/api/lms/player/${enc(id)}/settings`, { cache: "no-store" }), fetch("/api/radio?zone=" + enc(id), { cache: "no-store" })]);
      const s = sr.ok ? await sr.json() : null;
      const radio = rr.ok ? !!(await rr.json()).enabled : null;
      if (id !== current) return;
      if (!s) { if (!$("ad-dev-body").children.length) $("ad-dev-body").innerHTML = '<div class="settings-note">Lyrion didn\'t answer for this player.</div>'; return; }
      // The page's head: the player's name (Mallard's: "Device" when Lyrion gives none).
      $("ad-dev-title").textContent = s.name || "Device";
      // Never redrawn under a field being typed in.
      const focus = document.activeElement;
      if (focus && focus.closest && focus.closest("#ad-dev-body") && focus.matches("input[type=text], select")) return;
      render(s, radio);
    } catch (e) { /* kept as it was */ }
  }
  function openDevice(id) {
    current = id;
    $("ad-dev-title").textContent = "Device";
    $("ad-dev-body").innerHTML = '<div class="settings-note">Asking Lyrion…</div>';
    show("device");
    loadDevice();
    clearInterval(devTimer);
    devTimer = setInterval(() => { if (visible("device")) loadDevice(); else clearInterval(devTimer); }, 10000);
  }

  /** A change on the player's page: sent, said, read again (Mallard's act()). */
  async function act(url, body, said) {
    try {
      const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || j.ok === false) throw new Error(j.error || "HTTP " + r.status);
      toast(said);
    } catch (e) { toast("Couldn't save: " + e.message, "error"); }
    loadDevice();
  }
  function changed(el) {
    const key = el.getAttribute("data-ad");
    const p = `/api/lms/player/${enc(current)}`;
    if (key === "power") return act(p + "/power", { on: el.checked }, el.checked ? "Switched on" : "Switched off");
    if (key === "radio") return act("/api/radio", { zone: current, enabled: el.checked }, el.checked ? "Random album radio on" : "Random album radio off");
    if (key === "shuffle") return act(p + "/mode", { shuffle: Number(el.value) }, "Saved ✓");
    if (key === "repeat") return act(p + "/mode", { repeat: Number(el.value) }, "Saved ✓");
    if (key === "dstm") return act(p + "/dstm", { provider: el.value }, "Saved ✓");
    if (key === "sync") return act(p + "/sync", { with: el.value || null }, el.value ? "Synced ✓" : "Unsynced ✓");
    if (key.startsWith("pref:")) return act(`${p}/pref/${enc(key.slice(5))}`, { value: el.value }, "Saved ✓");
  }

  document.addEventListener("click", (e) => {
    const card = e.target.closest && e.target.closest(".ad-card[data-device]");
    if (card) { openDevice(card.getAttribute("data-device")); return; }
    if (e.target.closest && e.target.closest("[data-device-back]")) { show("playback"); open(); }
  });
  document.addEventListener("change", (e) => {
    const el = e.target;
    if (!el || !el.closest || !el.closest("#ad-dev-body") || !el.hasAttribute("data-ad") || el.getAttribute("data-ad") === "name") return;
    changed(el);
  });
  document.addEventListener("keydown", (e) => {
    const el = e.target;
    if (e.key !== "Enter" || !el || !el.matches || !el.matches("#ad-dev-body input[data-ad=name]")) return;
    const name = el.value.trim();
    el.blur();
    if (name) act(`/api/lms/player/${enc(current)}/name`, { name }, "Renamed ✓");
  });
  // The chosen zone changed (here or anywhere): its card is marked at once.
  document.addEventListener("change", (e) => { if (e.target && e.target.id === "zone-select" && visible("playback")) loadList(); });

  window.__audioDevices = { open, openDevice };
  // Shown for the info buttons (app.js reads data-info on .settings-info-btn).
  const zoneInfo = $("ad-zone-info"); if (zoneInfo) zoneInfo.setAttribute("data-info", ZONE_INFO);
  const devInfo = $("ad-devices-info"); if (devInfo) devInfo.setAttribute("data-info", DEVICES_INFO);
})();
