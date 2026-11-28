/*
 * diag-log.js — the web app's side of the diagnostic log (lib/diagnostics.js; owner request: logging off by default, a
 * user switches it on if they're having trouble, and downloads it through the browser): Settings → System's switch and
 * Download, and, while logging is on, the page's own errors sent to the server's one log ("[web <browser>]") — each error
 * once a minute at most, batched after 3 s, never retried, as Mandarin's page-errors.js.
 */
(function () {
  "use strict";
  let on = false;
  const toggle = document.getElementById("diag-toggle");
  function read() {
    fetch("/api/diagnostics", { cache: "no-store" }).then((r) => r.json()).then((j) => { on = !!j.enabled; if (toggle) toggle.checked = on; }).catch(() => {});
  }
  if (toggle) toggle.addEventListener("change", () => {
    const want = toggle.checked;
    fetch("/api/diagnostics", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: want }) })
      .then((r) => r.json()).then((j) => {
        on = !!j.enabled; toggle.checked = on;
        if (window.__showToast) window.__showToast(on ? "Diagnostic logging on — download the log once the problem has happened" : "Diagnostic logging off");
      }).catch(() => { toggle.checked = on; if (window.__showToast) window.__showToast("Couldn't reach the server", "error"); });
  });
  // Read when Settings opens (the switch may have been changed from Mallard) and once now.
  document.addEventListener("click", (e) => { if (e.target && e.target.closest && e.target.closest("#settings-toggle, .settings-nav-item[data-pane=system]")) read(); });
  read();

  let queue = [], timer = null;
  const seen = new Map();
  function send() {
    timer = null;
    const lines = queue.splice(0, 20);
    if (!lines.length) return;
    fetch("/api/diagnostics/page", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ lines }), keepalive: true }).catch(() => {});
  }
  function report(level, text) {
    if (!on || !text) return;
    const t = Date.now();
    if (t - (seen.get(text) || 0) < 60000) return;   // the same error once a minute at most
    seen.set(text, t);
    if (queue.length < 20) queue.push({ level, text: String(text).slice(0, 2000) });
    if (!timer) timer = setTimeout(send, 3000);
  }
  window.addEventListener("error", (e) => report("error", (e.message || "error") + (e.filename ? " at " + e.filename.replace(location.origin, "") + ":" + e.lineno : "")));
  window.addEventListener("unhandledrejection", (e) => report("error", "Unhandled: " + (e.reason && (e.reason.stack || e.reason.message) || String(e.reason))));
})();
