/*
 * share-sheet.js — the Share card sheet: opened from the album screen's and Now playing's Share button.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 * Mallard's ShareSheet.kt is this sheet natively, and they behave alike: the card is drawn AT ONCE from what is on
 * screen (title, artist, the cover the page already has), then drawn again once /api/album/extras brings the year,
 * the review (LMS's Music & Artist Information plugin, with whose words they are) and Pitchfork's score. That answer
 * can take seconds — the review lookup has a 15 s deadline — and a sheet that sat on a spinner for it read as broken.
 */
(() => {
  const overlay = document.getElementById("share-overlay");
  const frame = document.getElementById("share-frame");
  const actions = document.getElementById("share-actions");
  const hintEl = document.getElementById("share-hint");
  const errEl = document.getElementById("share-err");
  const modalBtn = document.getElementById("modal-share-btn");
  if (!overlay || !frame) return;
  const L = ShareCardLayout;
  const LOGO_URL = "/icons/duck-tile.png";
  // The size the album screen and Now playing already show: the browser's copy, not a second render on the server.
  const coverUrlOf = (key) => (key ? `/api/image/${encodeURIComponent(key)}?size=800` : "");
  const PLACEHOLDER = `<div class="share-placeholder"><div class="share-spinner"></div><div>Generating card…</div></div>`;
  // Which open is current: what a superseded open would paint is dropped.
  let seq = 0;
  let cardUrl = null;

  // Bounded: a font that never arrives must not hold the card up for ever.
  async function ensureFont() {
    if (!document.fonts || !document.fonts.load) return;
    const loaded = Promise.all([600, 700, 400].map((w) => document.fonts.load(`${w} 32px Manrope`)))
      .then(() => document.fonts.ready).catch(() => {});
    await Promise.race([loaded, new Promise((r) => setTimeout(r, 2000))]);
  }

  function close() {
    seq++;
    overlay.classList.add("hidden");
    frame.innerHTML = PLACEHOLDER;
    actions.innerHTML = "";
    hintEl.textContent = "";
    errEl.textContent = "";
  }
  overlay.addEventListener("click", (e) => {
    if (e.target.closest && e.target.closest("[data-share-close]")) close();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !overlay.classList.contains("hidden")) close();
  });

  /** What the card shows, from an /api/album/extras answer (or none). The twin of CardFields.parse in Mallard. */
  function cardFields(j) {
    const f = { releaseRaw: "", review: "", reviewSource: "", score: null, bestNewMusic: false };
    const a = j && j.album;
    if (j && j.year != null) f.releaseRaw = String(j.year);
    else if (a && a.year != null) f.releaseRaw = String(a.year);
    if (a && a.score != null) f.score = a.score;
    if (a && a.isBestNewMusic) f.bestNewMusic = true;
    if (a && a.description) {
      f.review = L.trimReview(a.description);
      f.reviewSource = String(a.descriptionSource || a.description_source || "");
    }
    f.signature = [f.releaseRaw, f.review, f.reviewSource, f.score, f.bestNewMusic].join("|");
    return f;
  }

  async function paint(f, input, mySeq) {
    const blob = await ShareCard.render(Object.assign({ coverUrl: coverUrlOf(input.image_key), logoUrl: LOGO_URL, title: input.title, artist: input.artist }, f));
    if (mySeq !== seq) return;
    if (cardUrl) URL.revokeObjectURL(cardUrl);
    cardUrl = URL.createObjectURL(blob);
    frame.innerHTML = "";
    const img = document.createElement("img");
    img.src = cardUrl;
    img.alt = "Share card";
    frame.appendChild(img);
    buildActions(blob, input.title, input.artist);
  }

  async function open(input) {
    const title = input.title || "";
    const artist = input.artist || "";
    if (!title) return;
    const mySeq = ++seq;
    actions.innerHTML = "";
    hintEl.textContent = "";
    errEl.textContent = "";
    frame.innerHTML = PLACEHOLDER;
    overlay.classList.remove("hidden");
    const card = { title, artist, image_key: input.image_key };
    try {
      const extras = fetch("/api/album/extras?" + new URLSearchParams({ title, artist }), { cache: "no-store" })
        .then((r) => (r.ok ? r.json() : null)).catch(() => null);
      await ensureFont();
      if (mySeq !== seq) return;
      const first = cardFields(null);
      await paint(first, card, mySeq);
      const later = cardFields(await extras);
      if (mySeq !== seq) return;
      if (later.signature !== first.signature) await paint(later, card, mySeq).catch(() => {});
    } catch (e) {
      if (mySeq !== seq) return;
      frame.innerHTML = `<div class="share-placeholder">Could not generate the card.</div>`;
      errEl.textContent = (e && e.message) ? e.message : String(e);
    }
  }
  window.__openShareCard = open;

  /*
   * Copy image (the accent) and Download, as Mallard's sheet has them. The app is served over plain http, where a
   * browser offers no clipboard API and no Web Share: there Copy image is left out rather than drawn and broken, and
   * Share… stands in for it where a browser does have that.
   */
  function buildActions(blob, title, artist) {
    actions.innerHTML = "";
    const name = L.fileName(title, artist);
    const canCopy = typeof window.ClipboardItem !== "undefined" && navigator.clipboard && typeof navigator.clipboard.write === "function";
    const canShare = (() => {
      try {
        return !!(navigator.share && navigator.canShare && navigator.canShare({ files: [new File([new Uint8Array([0])], "p.png", { type: "image/png" })] }));
      } catch (e) { return false; }
    })();
    if (canCopy) {
      const b = button("primary", "copy", "Copy image");
      b.onclick = async () => {
        try {
          await navigator.clipboard.write([new window.ClipboardItem({ "image/png": blob })]);
          label(b, "Copied!");
          setTimeout(() => label(b, "Copy image"), 2000);
        } catch (e) { errEl.textContent = "Couldn’t copy the card"; }
      };
      actions.appendChild(b);
    } else if (canShare) {
      const b = button("primary", "share", "Share…");
      b.onclick = async () => {
        try { await navigator.share({ files: [new File([blob], name, { type: "image/png" })] }); }
        catch (e) { if (e && e.name !== "AbortError") errEl.textContent = e.message || String(e); }
      };
      actions.appendChild(b);
    }
    const a = document.createElement("a");
    a.href = cardUrl;
    a.download = name;
    a.innerHTML = `${icon("download")}<span>Download</span>`;
    actions.appendChild(a);
    hintEl.textContent = (canCopy || canShare) ? "Tap a button above, or long-press the card to save." : "Long-press the card to save, or tap Download.";
  }

  function button(cls, iconName, text) {
    const b = document.createElement("button");
    b.className = cls;
    b.type = "button";
    b.innerHTML = `${icon(iconName)}<span></span>`;
    label(b, text);
    return b;
  }
  function label(btn, text) {
    const s = btn.querySelector("span");
    if (s) s.textContent = text;
  }
  function icon(name) {
    const I = {
      share: '<polyline points="16 6 12 2 8 6"/><line x1="12" y1="2" x2="12" y2="15"/><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/>',
      copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
      download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
    };
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${I[name] || ""}</svg>`;
  }

  // The Share button: on Now playing the card is what plays now (the album, by the track's artist), read live.
  if (modalBtn) {
    modalBtn.addEventListener("click", () => {
      const modal = document.getElementById("album-modal");
      const np = modal && modal.classList.contains("np-mode") && window.__getCurrentNp && window.__getCurrentNp();
      if (np) return open({ title: np.line3 || "", artist: np.line2 || "", image_key: np.image_key });
      const a = window.__currentAlbum;
      if (a) open({ title: a.title || "", artist: a.subtitle || "", image_key: a.image_key });
    });
  }
})();
