/*
 * shelf-entry.js — the way into the Shelf (/shelf) from the remote: its side-menu row and its switch in Settings →
 * Wall display. A file of its own because app.js only shrinks (tools/quality). The switch is the server's
 * (/api/settings/shelf, lib/shelf-routes.js) and Mallard reads the same one, so the two apps show Shelf together.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const row = document.getElementById("menu-item-shelf");
  const toggle = document.getElementById("shelf-enabled");
  const note = document.getElementById("shelf-enabled-note");

  /** The row and the switch, as the server has them. */
  function paint(on) {
    if (row) row.classList.toggle("hidden", !on);
    if (toggle) toggle.checked = on;
    if (note) note.textContent = on ? "On: Shelf is in the menu, and at /shelf." : "Off. Switch it on to add Shelf to the menu.";
  }
  async function load() {
    try {
      const r = await fetch("/api/settings/shelf", { cache: "no-store" });
      if (r.ok) paint(!!(await r.json()).enabled);
    } catch (e) { /* unreachable: the row stays as it was */ }
  }
  if (row) row.addEventListener("click", () => { location.href = "/shelf"; });
  if (toggle) toggle.addEventListener("change", async () => {
    const on = toggle.checked;
    try {
      const r = await fetch("/api/settings/shelf", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: on }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || !j.ok) throw new Error(j.error || "Couldn’t save that");
      paint(!!j.enabled);
      if (window.__showToast) window.__showToast(on ? "Shelf on — it's in the menu" : "Shelf off");
    } catch (e) {
      toggle.checked = !on;   // the server refused — don't show what isn't so
      if (window.__showToast) window.__showToast(e.message, "error");
    }
  });
  // On opening, and whenever the menu is opened (another device may have switched it).
  load();
  const menuBtn = document.getElementById("menu-toggle");
  if (menuBtn) menuBtn.addEventListener("click", load);
})();
