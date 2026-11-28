/*
 * sharecard.js — render an album share card as a PNG, in the browser.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 * Draws what sharecard-layout.js measured (load that first). Ported from Mandarin; Mallard's ShareCardPainter.kt
 * draws the same card, step for step, so the two apps' cards are the same picture.
 *
 *  - The ground is the cover again, softened. THE SOFTENING IS A DOWNSCALE, NOT A BLUR: ctx.filter='blur()' is not
 *    dependable across the browsers this runs in, so the cover is drawn into a 24px canvas and scaled back up.
 *  - Two scrims over it: a flat one sets a floor on how light the ground gets (a white sleeve), a gradient darkens
 *    the foot. Then the glass pane, the sharp cover with a hairline, the words.
 *  - The card stays dark in every theme: it is seen outside the app, on grounds nobody here controls.
 *  - Pitchfork: the score and the Best New Music flag only, NEVER their words (CLAUDE.md, UK-law compliance). The
 *    review on the card is LMS's Music & Artist Information plugin's, with whose words they are under it.
 */

const ShareCard = (() => {
  const L = ShareCardLayout;
  const GROUND = "#12151a";
  const PANE_FILL = "rgba(18,21,26,.5)";
  const RULE_COLOUR = "rgba(255,255,255,.16)";
  const ART_R = 18;
  const SCORE_PAD = 14, SCORE_H = 54, SCORE_R = 12, SCORE_SIZE = 30, BNM_H = 26, BNM_SIZE = 15;
  const LOGO_D = 96, LOGO_PAD = 32;
  const font = (weight, size) => `${weight} ${size}px "Manrope", sans-serif`;

  function roundRectPath(ctx, x, y, w, h, r) {
    const rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }

  function loadImage(src) {
    if (!src) return Promise.resolve(null);
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error("image load failed: " + src));
      img.src = src;
    });
  }

  /** The part of the image that fills dw x dh, cut from the middle. */
  function drawCover(ctx, img, dx, dy, dw, dh) {
    const ir = img.width / img.height, dr = dw / dh;
    let sx, sy, sw, sh;
    if (ir > dr) { sh = img.height; sw = sh * dr; sx = (img.width - sw) / 2; sy = 0; }
    else { sw = img.width; sh = sw / dr; sx = 0; sy = (img.height - sh) / 2; }
    ctx.drawImage(img, sx, sy, sw, sh, dx, dy, dw, dh);
  }

  function drawSoftened(ctx, img, w, h) {
    const off = document.createElement("canvas");
    off.width = 24;
    off.height = Math.max(1, Math.round(24 * (h / w)));
    drawCover(off.getContext("2d"), img, 0, 0, off.width, off.height);
    ctx.imageSmoothingEnabled = true;
    // Past every edge: the outermost pixels of an upscale are the least smoothed.
    const over = Math.round(w * 0.06);
    ctx.drawImage(off, -over, -over, w + over * 2, h + over * 2);
  }

  /** A text centred on x, its top at y. */
  function centred(ctx, text, x, y) {
    ctx.textAlign = "center";
    ctx.fillText(text, x, y);
    ctx.textAlign = "left";
  }

  function drawScore(ctx, score, bestNew, artY) {
    const s = String(score);
    ctx.font = font(700, SCORE_SIZE);
    const sw = Math.ceil(ctx.measureText(s).width) + 30;
    const sx = L.ART_X + L.ART - SCORE_PAD - sw;
    const sy = artY + SCORE_PAD;
    // Opaque: it sits on the album art, so its own ground is all that keeps it legible over a white sleeve.
    roundRectPath(ctx, sx, sy, sw, SCORE_H, SCORE_R);
    ctx.fillStyle = "#0c0e12";
    ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,.18)";
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.fillStyle = "#ffffff";
    centred(ctx, s, sx + sw / 2, sy + Math.round((SCORE_H - SCORE_SIZE) / 2) - 2);
    if (!bestNew) return;
    ctx.font = font(700, BNM_SIZE);
    const bw = Math.ceil(ctx.measureText("BEST NEW MUSIC").width) + 22;
    const bx = L.ART_X + L.ART - SCORE_PAD - bw;
    const by = sy + SCORE_H + 8;
    roundRectPath(ctx, bx, by, bw, BNM_H, 8);
    ctx.fillStyle = "#e8482b";
    ctx.fill();
    ctx.fillStyle = "#ffffff";
    centred(ctx, "BEST NEW MUSIC", bx + bw / 2, by + Math.round((BNM_H - BNM_SIZE) / 2) - 1);
  }

  /** The duck tile in the pane's bottom-right corner, a soft shadow under it. */
  function drawLogo(ctx, img, x, y, d) {
    ctx.save();
    ctx.shadowColor = "rgba(0,0,0,.45)";
    ctx.shadowBlur = 14;
    ctx.shadowOffsetY = 4;
    roundRectPath(ctx, x, y, d, d, d * 0.2);
    ctx.fillStyle = "#17181b";
    ctx.fill();
    ctx.restore();
    ctx.drawImage(img, x, y, d, d);
  }

  /**
   * The card as a PNG blob. data: { title, artist, releaseRaw, review, reviewSource, score, bestNewMusic, coverUrl,
   * logoUrl }.
   */
  async function render(data) {
    const [cover, logo] = await Promise.all([loadImage(data.coverUrl).catch(() => null), loadImage(data.logoUrl).catch(() => null)]);
    // MEASURE, THEN SIZE, THEN DRAW: setting canvas.height resets the context, so nothing is drawn before it.
    const canvas = document.createElement("canvas");
    canvas.width = L.CARD_W;
    canvas.height = L.MIN_CARD_H;
    let ctx = canvas.getContext("2d");
    const l = L.measure((t, w, s) => { ctx.font = font(w, s); return ctx.measureText(t).width; }, data);
    const W = L.CARD_W, H = l.cardH;
    canvas.height = H;
    ctx = canvas.getContext("2d");
    ctx.textBaseline = "top";
    ctx.textAlign = "left";

    ctx.fillStyle = GROUND;
    ctx.fillRect(0, 0, W, H);
    if (cover) {
      drawSoftened(ctx, cover, W, H);
      ctx.fillStyle = "rgba(12,14,18,.44)";
      ctx.fillRect(0, 0, W, H);
      const vign = ctx.createLinearGradient(0, H * 0.45, 0, H);
      vign.addColorStop(0, "rgba(8,10,13,0)");
      vign.addColorStop(1, "rgba(8,10,13,.55)");
      ctx.fillStyle = vign;
      ctx.fillRect(0, 0, W, H);
    }
    ctx.fillStyle = PANE_FILL;
    ctx.fillRect(L.INSET, L.INSET, L.PANE_W, l.paneH);

    // The sharp cover, clipped to its corners, and a hairline so a sleeve white to its edge doesn't bleed.
    ctx.save();
    roundRectPath(ctx, L.ART_X, l.artY, L.ART, L.ART, ART_R);
    ctx.clip();
    if (cover) drawCover(ctx, cover, L.ART_X, l.artY, L.ART, L.ART);
    else { ctx.fillStyle = "rgba(255,255,255,.06)"; ctx.fillRect(L.ART_X, l.artY, L.ART, L.ART); }
    ctx.restore();
    roundRectPath(ctx, L.ART_X + 0.5, l.artY + 0.5, L.ART - 1, L.ART - 1, ART_R);
    ctx.lineWidth = 1;
    ctx.strokeStyle = "rgba(0,0,0,.35)";
    ctx.stroke();

    // The words beside it. #c2cad3 is solved against the worst pane (a white sleeve, softened and scrimmed).
    let y = l.textY;
    if (l.metaText) {
      ctx.fillStyle = "#c2cad3";
      ctx.font = font(600, L.META_SIZE);
      ctx.fillText(l.metaText.toUpperCase(), L.TEXT_X, y);
      y += L.META_H + L.META_GAP;
    }
    ctx.fillStyle = "#ffffff";
    ctx.font = font(700, l.title.size);
    l.title.lines.forEach((line, i) => ctx.fillText(line, L.TEXT_X, y + i * l.title.lh));
    y += l.title.lines.length * l.title.lh + L.BLOCK_GAP;
    ctx.fillStyle = "#cdd3d9";
    ctx.font = font(400, l.artist.size);
    l.artist.lines.forEach((line, i) => ctx.fillText(line, L.TEXT_X, y + i * l.artist.lh));

    // The hairline across the pane, the review under it, and whose words they are.
    if (l.desc) {
      ctx.beginPath();
      ctx.moveTo(L.ART_X, l.ruleY + 0.5);
      ctx.lineTo(L.ART_X + L.CONTENT_W, l.ruleY + 0.5);
      ctx.strokeStyle = RULE_COLOUR;
      ctx.stroke();
      let dy = l.ruleY + 1 + L.RULE_GAP;
      ctx.fillStyle = "#c2cad3";
      ctx.font = font(400, l.desc.size);
      l.desc.lines.forEach((line, i) => ctx.fillText(line, L.ART_X, dy + i * l.desc.lh));
      dy += l.desc.lines.length * l.desc.lh;
      if (l.srcText) {
        ctx.font = font(400, L.SRC_SIZE);
        ctx.fillText(l.srcText, L.ART_X, dy + L.SRC_GAP);
      }
    }
    if (data.score != null && isFinite(data.score)) drawScore(ctx, data.score, !!data.bestNewMusic, l.artY);
    if (logo) drawLogo(ctx, logo, L.INSET + L.PANE_W - LOGO_PAD - LOGO_D, L.INSET + l.paneH - LOGO_PAD - LOGO_D, LOGO_D);

    return new Promise((resolve, reject) => {
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("toBlob failed"))), "image/png");
    });
  }

  return { render };
})();
