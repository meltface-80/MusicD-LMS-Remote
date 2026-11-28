// @ts-check
"use strict";
/*
 * nowquality.js — what the Now playing screen says about the sound (owner request: "for all zones the quality of the
 * music being played shown on the now playing screen, including volume levelling replay gain when enabled"), Mandarin's
 * format line under the album, with the file's own resolution rather than Mandarin's plain "Lossless":
 *   "FLAC 24/96", "ALAC 16/44.1", "MP3 320 kbps", "DSD 2.8 MHz" — and This phone away with Opus chosen: "Opus 256 kbps ·
 *   24/48" (made from the file by the server, decoded by Mallard's own libopus to 24-bit at 48 kHz) — then, while the
 *   player's volume levelling is on and a gain applies, " · ReplayGain −6.2 dB" (Mandarin's wording, a true minus).
 * LMS gives the track's format with the status it already reads (tags o r T I, and Y X for the track's and album's
 * ReplayGain); the player's levelling mode (pref replayGainMode: 0 off, 1 track, 2 album, 3 smart) is read once a minute.
 * A phone's own player says what the phone itself plays (its report) and the gain LMS sent it with the track.
 *
 * Also the album badges' labels (moved here from index.js, which only shrinks): the same words, one place.
 */

// Lossless containers. Everything else describes a DECODER rather than the recording — an MP3 reports 16-bit/44.1k
// because that is what it decodes to, and printing "16/44.1" on it would claim CD quality for a lossy file.
const LOSSLESS_TYPES = new Set(["flc", "flac", "alc", "alac", "aif", "aiff", "wav",
  "wvp", "wv", "ape", "mpc", "dsf", "dff", "ogf"]);
const QUALITY_TYPE_LABELS = /** @type {Record<string, string>} */ ({ flc: "FLAC", flac: "FLAC", alc: "ALAC", alac: "ALAC",
  aif: "AIFF", aiff: "AIFF", wav: "WAV", dsf: "DSD", dff: "DSD", mp3: "MP3", mp4: "AAC", aac: "AAC", ogg: "OGG", ops: "Opus",
  wma: "WMA", wv: "WavPack", wvp: "WavPack", ape: "APE", ogf: "FLAC" });

/** 44100 → "44.1", 96000 → "96". @param {number|null|undefined} hz */
function rateShort(hz) {
  if (!hz) return null;
  const k = hz / 1000;
  return String(Number.isInteger(k) ? k : Math.round(k * 10) / 10);
}

/** An album badge's words (its first track's format): "24/96", or a lossy file's codec. @param {any} f {type, rate, bits} */
function albumQualityLabel(f) {
  if (!f) return null;
  const t = String(f.type || "").toLowerCase();
  const label = QUALITY_TYPE_LABELS[t] || (t ? t.toUpperCase() : null);
  if (t && !LOSSLESS_TYPES.has(t)) return label;   // lossy first, for the reason above
  if (f.bits && f.rate) return f.bits + "/" + rateShort(f.rate);
  if (f.rate) return rateShort(f.rate) + " kHz";
  return label;
}

/** Better than CD (the badge takes the accent then). @param {any} f */
function albumIsHiRes(f) {
  if (!f) return false;
  const t = String(f.type || "").toLowerCase();
  if (t && !LOSSLESS_TYPES.has(t)) return false;
  return !!((f.bits && f.bits > 16) || (f.rate && f.rate > 48000));
}

/** "1411kbps", "320kbps CBR", "256000" → kbps, or null. @param {any} b */
function kbps(b) {
  const m = /(\d+(?:\.\d+)?)\s*(k)?/i.exec(String(b == null ? "" : b));
  if (!m) return null;
  const n = Number(m[1]);
  return n > 0 ? Math.round(m[2] || n < 10000 ? n : n / 1000) : null;
}

/**
 * The words for a track's own format: "FLAC 24/96", "MP3 320 kbps", "DSD 2.8 MHz"; null when LMS says nothing of it.
 * @param {{ type?: any, bitrate?: any, rate?: any, bits?: any }|null|undefined} f
 * @returns {{ kind: "lossless"|"lossy", text: string }|null}
 */
function formatOf(f) {
  if (!f) return null;
  const t = String(f.type || "").toLowerCase();
  const name = QUALITY_TYPE_LABELS[t] || (t ? t.toUpperCase() : "");
  const rate = Number(f.rate) || 0, bits = Number(f.bits) || 0;
  if (t === "dsf" || t === "dff") return { kind: "lossless", text: rate ? `DSD ${Math.round(rate / 100000) / 10} MHz` : "DSD" };
  if (t && !LOSSLESS_TYPES.has(t)) {
    const k = kbps(f.bitrate);
    return name ? { kind: "lossy", text: k ? `${name} ${k} kbps` : name } : null;
  }
  const res = bits && rate ? `${bits}/${rateShort(rate)}` : rate ? `${rateShort(rate)} kHz` : "";
  const text = [name, res].filter(Boolean).join(" ");
  return text ? { kind: "lossless", text } : null;
}

/** "−6.2 dB" / "+1.5 dB" (a true minus, as Mandarin's). @param {number} db */
const dbText = (db) => (db < 0 ? "−" : "+") + Math.abs(Math.round(db * 10) / 10).toFixed(1) + " dB";

/**
 * The ReplayGain LMS applies for levelling [mode] (0 off, 1 track, 2 album, 3 smart): the album's gain where asked for
 * and known, else the track's; null when off or the track carries none. @param {number} mode
 * @param {{ gain?: any, albumGain?: any }|null|undefined} f
 */
function appliedGain(mode, f) {
  if (!mode || !f) return null;
  const track = Number.isFinite(Number(f.gain)) && f.gain !== "" && f.gain != null ? Number(f.gain) : null;
  const album = Number.isFinite(Number(f.albumGain)) && f.albumGain !== "" && f.albumGain != null ? Number(f.albumGain) : null;
  if (mode === 2 || mode === 3) return album != null ? album : track;
  return track;
}

/**
 * Now playing's quality line: {kind, text} — the format, and " · ReplayGain …" while a gain applies; null for nothing.
 * [phone], for a phone's own player: what the phone plays ({form: "opus"|"orig"}) and the gain LMS sent with the track.
 * @param {{ fmt?: any, mode?: number, phone?: { form?: string|null, gainDb?: number|null }|null }} o
 * @returns {{ kind: string, text: string }|null}
 */
function qualityLine(o) {
  const base = o.phone && o.phone.form === "opus" ? { kind: "opus", text: "Opus 256 kbps · 24/48" } : formatOf(o.fmt);
  // A phone applies exactly what LMS sent with the track, which LMS sends only while the player's levelling is on.
  const gain = o.phone ? (o.phone.gainDb != null ? o.phone.gainDb : null) : appliedGain(Number(o.mode) || 0, o.fmt);
  const g = gain != null && Math.abs(gain) >= 0.05 ? `ReplayGain ${dbText(gain)}` : null;
  if (!base && !g) return null;
  return { kind: base ? base.kind : "gain", text: [base && base.text, g].filter(Boolean).join(" · ") };
}

module.exports = { LOSSLESS_TYPES, QUALITY_TYPE_LABELS, rateShort, albumQualityLabel, albumIsHiRes, formatOf, appliedGain, qualityLine, dbText };
