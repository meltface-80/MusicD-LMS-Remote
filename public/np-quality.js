/*
 * np-quality.js — Now playing's quality line (owner request: "for all zones the quality of the music being played shown on
 * the now playing screen, including volume levelling replay gain when enabled"): Mandarin's format pill under the album —
 * "FLAC 24/96", "MP3 320 kbps", This phone away "Opus 256 kbps · 24/48", and " · ReplayGain −6.2 dB" while levelling
 * applies. The words are the server's (lib/nowquality.js, /api/zone-state now_playing.format), the same in Mallard.
 * app.js calls window.__npQuality(quality) wherever it paints the album line; a file of its own as app.js only shrinks.
 */
(function () {
  "use strict";
  window.__npQuality = function (q) {
    const el = document.getElementById("np-quality");
    if (!el) return;
    const text = q && q.text ? String(q.text) : "";
    el.textContent = text;
    el.classList.toggle("hidden", !text);
    el.classList.toggle("is-opus", !!q && q.kind === "opus");
    el.setAttribute("aria-label", text ? "Quality: " + text : "Quality");
  };
})();
