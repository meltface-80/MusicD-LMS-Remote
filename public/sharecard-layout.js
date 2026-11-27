/*
 * sharecard-layout.js — the share card's layout, measured before it is drawn.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 * Ported from Mandarin, whose web and Android cards are twins. So are ours: this file and Mallard's
 * android/core/.../client/ShareCard.kt (CardLayout) are TWO COPIES OF ONE LAYOUT, and lib/sharecard.test.js runs the
 * cases ShareCardTest.kt runs, with the same stand-in font. Change one, change both.
 *
 *   +--------------------------------------------------------+
 *   | +--------+   RELEASED 2009                             |
 *   | | cover  |   Album Title                               |   the cover again, softened, as the ground,
 *   | | 464px  |   by Artist                                 |   under a glass pane that is the whole card
 *   | +--------+                                             |
 *   | ------------------------------------------------------ |
 *   | The review, across the whole card, under a hairline…   |
 *   | AllMusic                                         [duck] |
 *   +--------------------------------------------------------+
 *
 * 1200 wide; the height is a RESULT of the words (600 to 2100): everything is wrapped first, then the canvas is sized.
 * measure() needs only a width function, so it is tested without a canvas.
 */

const ShareCardLayout = (() => {
  const CARD_W = 1200;
  const MIN_CARD_H = 600;    // a card of art, title and artist
  const MAX_CARD_H = 2100;   // the backstop; DESC_MAX is what bounds the review
  const INSET = 0;           // the pane is the whole card
  const PANE_W = CARD_W - INSET * 2;
  const PANE_PAD = 56;
  const ART = 464;
  const ART_X = INSET + PANE_PAD;
  const TEXT_X = ART_X + ART + 44;
  const TEXT_W = INSET + PANE_W - PANE_PAD - TEXT_X;
  const CONTENT_W = PANE_W - PANE_PAD * 2;   // the review's column: the whole pane
  const DESC_SIZE = 33;
  const DESC_LH = 49;
  // share-sheet.js trims a review to ten sentences and 1400 characters, which wraps to about 24 lines: 27 lets the
  // longest it can send through whole. Raise one, raise the other.
  const DESC_MAX = 27;
  const RULE_GAP = 30;
  const SRC_SIZE = 20;
  const SRC_H = SRC_SIZE + 4;
  const SRC_GAP = 26;
  const META_SIZE = 26;
  const META_H = META_SIZE + 4;
  const META_GAP = 24;
  const BLOCK_GAP = 18;
  const TITLE_SIZES = [56, 48, 42, 36, 31, 27];
  const ARTIST_SIZES = [37, 32, 28, 24, 21];
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

  /** A release as the card writes it: "20 Nov 2006", "Nov 2006", "2006", or as it came. */
  function releaseDate(raw) {
    const s = raw == null ? "" : String(raw).trim();
    if (!s) return null;
    let m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (m && +m[2] >= 1 && +m[2] <= 12 && +m[3] >= 1 && +m[3] <= 31) return `${+m[3]} ${MONTHS[+m[2] - 1]} ${m[1]}`;
    m = s.match(/^(\d{4})-(\d{1,2})$/);
    if (m && +m[2] >= 1 && +m[2] <= 12) return `${MONTHS[+m[2] - 1]} ${m[1]}`;
    return s;
  }

  /** [text] wrapped to maxWidth in maxLines at most; overflow when it didn't all fit, or a word is wider than the column. */
  function wrap(width, text, maxWidth, maxLines) {
    if (!text) return { lines: [], overflow: false };
    const lines = [];
    let cur = "";
    let overflow = false;
    for (const w of String(text).split(/\s+/)) {
      const candidate = cur ? cur + " " + w : w;
      if (width(candidate) <= maxWidth) { cur = candidate; continue; }
      if (cur) lines.push(cur);
      if (lines.length >= maxLines) { cur = ""; overflow = true; break; }
      cur = w;
      if (width(w) > maxWidth) overflow = true;
    }
    if (cur && lines.length < maxLines) lines.push(cur);
    else if (cur) overflow = true;
    return { lines, overflow };
  }

  /** [text] fitted: each size tried until it fits; only at the smallest is the last line cut with "…". */
  function fit(measureText, text, maxWidth, maxLines, weight, sizes, lhRatio) {
    let r = { lines: [], overflow: false };
    let size = sizes[0];
    for (const s of sizes) {
      size = s;
      r = wrap((t) => measureText(t, weight, s), text, maxWidth, maxLines);
      if (!r.overflow) break;
    }
    const lines = r.lines.slice();
    if (r.overflow && lines.length) {
      let last = lines[lines.length - 1];
      while (last.length && measureText(last + "…", weight, size) > maxWidth) last = last.slice(0, -1);
      lines[lines.length - 1] = last.replace(/\s+$/, "") + "…";
    }
    return { lines, size, lh: Math.round(size * lhRatio) };
  }

  /**
   * The card measured. measureText(text, weight, size) is a width. The review is dropped below two lines' room: one
   * orphaned line that stops mid-sentence reads as a fault, not a summary.
   */
  function measure(measureText, data) {
    const rel = releaseDate(data.releaseRaw);
    const metaText = rel ? "Released " + rel : null;
    const title = fit(measureText, data.title || "", TEXT_W, 4, 700, TITLE_SIZES, 68 / 56);
    const artist = fit(measureText, "by " + (data.artist || ""), TEXT_W, 4, 400, ARTIST_SIZES, 48 / 37);
    const headerTextH = (metaText ? META_H + META_GAP : 0) + title.lines.length * title.lh + BLOCK_GAP + artist.lines.length * artist.lh;
    const headerH = Math.max(ART, headerTextH);
    const src = data.reviewSource ? String(data.reviewSource).trim() : "";
    const srcH = src ? SRC_GAP + SRC_H : 0;
    const room = MAX_CARD_H - INSET * 2 - PANE_PAD * 2 - headerH - (RULE_GAP * 2 + 1) - srcH;
    const maxDesc = Math.min(DESC_MAX, Math.floor(room / DESC_LH));
    const desc = data.review && maxDesc >= 2 ? fit(measureText, String(data.review), CONTENT_W, maxDesc, 400, [DESC_SIZE], DESC_LH / DESC_SIZE) : null;
    const descH = desc ? RULE_GAP + 1 + RULE_GAP + desc.lines.length * desc.lh + srcH : 0;
    const contentH = headerH + descH;
    const cardH = Math.max(MIN_CARD_H, Math.min(MAX_CARD_H, contentH + PANE_PAD * 2 + INSET * 2));
    const paneH = cardH - INSET * 2;
    const contentY = INSET + Math.max(PANE_PAD, Math.round((paneH - contentH) / 2));
    return {
      metaText, title, artist, desc, srcText: desc ? src : "", headerTextH, headerH, contentH, cardH, paneH, contentY,
      // The cover and the words are each centred against the taller of the two.
      artY: contentY + Math.round((headerH - ART) / 2),
      textY: contentY + Math.round((headerH - headerTextH) / 2),
      ruleY: contentY + headerH + RULE_GAP,
    };
  }

  /** The review as the card carries it: ten sentences at most, and 1,400 characters, cut at a word with "…". */
  function trimReview(desc) {
    let t = String(desc).trim();
    const sentences = t.match(/[^.!?]+[.!?]+/g);
    if (sentences && sentences.length > 10) t = sentences.slice(0, 10).join(" ").trim();
    if (t.length > 1400) t = t.slice(0, 1398).replace(/\s+\S*$/, "") + "…";
    return t;
  }

  /** The saved card's name: the artist and the title, anything but letters and digits made "_". */
  function fileName(title, artist) {
    return `${(artist || "artist").replace(/[^a-z0-9]+/gi, "_")}-${(title || "card").replace(/[^a-z0-9]+/gi, "_")}.png`;
  }

  return {
    measure, fit, wrap, releaseDate, trimReview, fileName,
    CARD_W, MIN_CARD_H, MAX_CARD_H, INSET, PANE_W, PANE_PAD, ART, ART_X, TEXT_X, TEXT_W, CONTENT_W,
    META_SIZE, META_H, META_GAP, BLOCK_GAP, RULE_GAP, SRC_SIZE, SRC_GAP,
  };
})();

// The suite (lib/sharecard.test.js) loads it in Node; the page with a <script> tag.
if (typeof module !== "undefined" && module.exports) module.exports = ShareCardLayout;
