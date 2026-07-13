// ---------------------------------------------------------------------------
// Wall-display video picker — chooses a MUTED, MOVING YouTube clip for the
// now-playing track shown on the /display wall.
//
// The wall display is silent, so a "video" that is really album art + audio
// (the ubiquitous " - Topic" auto-uploads, "Official Audio", lyric slideshows,
// 1-hour loops, …) is worthless: a still image on a big screen. The YouTube
// Data API exposes no "moving vs static" field, so we derive one ourselves.
//
// MOTION GATE: YouTube auto-generates three storyboard frames sampled across
// each video at i.ytimg.com/vi/<id>/mq1.jpg | mq2.jpg | mq3.jpg (320x180, free,
// no API quota). For a static-image upload those three frames are (near-)
// identical; for real footage they differ a lot. We perceptual-hash each frame
// (average hash) and take the max pairwise Hamming distance — that IS the motion
// signal, independent of title/channel. Candidates whose frames barely move are
// rejected even if the title looks official.
//
// Scoring still prefers channel ownership + official-video titles, and treats
// VEVO / official uploads as high trust so that if the frames happen to be
// unreadable we can fail closed (keep only clips that ALSO look official).
//
// Pure + fully injectable: every network call (httpJson, httpBuffer) and even
// the JPEG decoder are passed in, so the whole module unit-tests offline with
// no YouTube key. jpeg-js is an OPTIONAL, pure-JS dependency; if it is absent,
// decodeJpeg() returns null and the fail-closed path takes over.
// ---------------------------------------------------------------------------
"use strict";

const { normalize } = require("./search");

// jpeg-js is optional (pure JS, no native build). Absent → decode returns null
// and the motion gate reports "unknown", handled by the fail-closed fallback.
let _jpeg = null;
try { _jpeg = require("jpeg-js"); } catch (_) { _jpeg = null; }

// Minimum max-pairwise Hamming distance (out of 64 aHash bits) for a set of
// storyboard frames to count as "moving". Higher = STRICTER (more clips are
// judged static and rejected). 8/64 comfortably separates identical frames
// (distance ~0) from real footage (typically 20+) while tolerating the mild
// compression/exposure jitter between two frames of a near-static clip.
const MOTION_THRESHOLD = 8;

// Only run the (network) motion gate on the top few scored candidates.
const MOTION_CANDIDATE_CAP = 6;

// Sentinel returned by scoreVideo for a rejected candidate. Callers keep score > 0.
const REJECT = 0;

// Titles that signal a static-image / non-performance upload (worthless muted).
// Extends the original inline list with the many "audio + art" variants.
const REJECT_TITLE = /\b(?:audio|lyrics?|visuali[sz]er|cover|reaction|remix|sped|slowed|8d|karaoke|instrumental|full album|teaser|trailer|interview|behind the scenes|epk|shorts?|official audio|audio only|slideshow|static|art ?track|1 ?hour|10 ?hours?|loop(?:ed)?|nightcore|mashup|tribute|unofficial|fan ?made|photos?|pictures?)\b/i;

// Title keywords that mark a live performance (still moving footage). "live at"
// / "live performance" are covered by the \blive\b alternative.
const LIVE_TITLE = /\b(?:live|session|concert)\b/i;

// Classify a normalized channel name relative to the normalized artist:
//   "trusted"  — a VEVO channel, or the artist's own / official / music channel
//   "adjacent" — some other channel that still contains the artist name
//   "none"     — unrelated (reject)
function channelTrust(channelN, artistN) {
  if (channelN.endsWith("vevo") ||
      channelN === artistN ||
      channelN === artistN + " official" ||
      channelN === artistN + " music") return "trusted";
  if (artistN && channelN.indexOf(artistN) !== -1) return "adjacent";
  return "none";
}

// Score a search.list item. Returns a positive number for a keeper, or REJECT.
// Rejects: " - Topic" channels, static/non-performance titles, any title
// missing a track token, channels not owned-by/adjacent-to the artist, and a
// bare "live" clip on an untrusted channel.
function scoreVideo(item, artistN, trackTokens) {
  const title   = (item && item.snippet && item.snippet.title)        || "";
  const channel = (item && item.snippet && item.snippet.channelTitle) || "";
  const titleN   = normalize(title);
  const channelN = normalize(channel);

  if (/ - topic$/i.test(channel)) return REJECT;
  if (REJECT_TITLE.test(title))   return REJECT;
  for (const t of (trackTokens || [])) if (titleN.indexOf(t) === -1) return REJECT;

  const trust = channelTrust(channelN, artistN);
  let score;
  if (trust === "trusted")       score = 70;
  else if (trust === "adjacent") score = 40;
  else return REJECT;

  if (/\bofficial (music )?video\b/i.test(title)) score += 30;
  else if (/\(official\b/i.test(title))           score += 20;

  if (LIVE_TITLE.test(title)) {
    if (score >= 70) score += 20;   // official live performance
    else return REJECT;             // untrusted "live" = usually a fan phone clip
  }
  return score;
}

// Would this item be trusted enough to keep even if we couldn't read its frames?
// VEVO channel, an explicit "official (music) video" title, or a live clip on a
// trusted channel. Used only by the fail-closed fallback in selectDisplayVideo.
function isHighTrust(item, artistN) {
  const title   = (item && item.snippet && item.snippet.title)        || "";
  const channel = (item && item.snippet && item.snippet.channelTitle) || "";
  const channelN = normalize(channel);
  const trust = channelTrust(channelN, artistN);
  const isVevo         = channelN.endsWith("vevo");
  const isOfficialVid  = /\bofficial (music )?video\b/i.test(title);
  const liveOnTrusted  = trust === "trusted" && LIVE_TITLE.test(title);
  return isVevo || isOfficialVid || liveOnTrusted;
}

// -------- perceptual hashing (average hash) --------------------------------

// Average-hash a decoded frame { width, height, data } (data = RGBA bytes).
// Block-average down to 8x8 grayscale, then set each of the 64 bits to
// (cell luminance > overall mean). Returns a 64-bit BigInt. Deterministic.
function aHash(frame) {
  const width  = frame.width | 0;
  const height = frame.height | 0;
  const data   = frame.data;
  const cells  = new Array(64);
  for (let by = 0; by < 8; by++) {
    const y0 = Math.floor((by * height) / 8);
    const y1 = Math.floor(((by + 1) * height) / 8);
    for (let bx = 0; bx < 8; bx++) {
      const x0 = Math.floor((bx * width) / 8);
      const x1 = Math.floor(((bx + 1) * width) / 8);
      let sum = 0, cnt = 0;
      for (let y = y0; y < y1; y++) {
        let i = (y * width + x0) * 4;
        for (let x = x0; x < x1; x++, i += 4) {
          // Rec. 601 luma; ignore alpha.
          sum += 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
          cnt++;
        }
      }
      cells[by * 8 + bx] = cnt ? sum / cnt : 0;
    }
  }
  let mean = 0;
  for (let i = 0; i < 64; i++) mean += cells[i];
  mean /= 64;
  let hash = 0n;
  for (let i = 0; i < 64; i++) {
    hash <<= 1n;
    if (cells[i] > mean) hash |= 1n;
  }
  return hash;
}

// Bit difference between two aHash BigInts.
function hamming(a, b) {
  let x = a ^ b, c = 0;
  while (x > 0n) { x &= x - 1n; c++; }   // Brian Kernighan popcount
  return c;
}

// Motion signal = the largest pairwise Hamming distance across the frame hashes.
function motionScoreOf(hashes) {
  let max = 0;
  for (let i = 0; i < hashes.length; i++)
    for (let j = i + 1; j < hashes.length; j++)
      max = Math.max(max, hamming(hashes[i], hashes[j]));
  return max;
}

// Decode a JPEG Buffer to { width, height, data:RGBA } via jpeg-js, or null if
// jpeg-js is unavailable / the buffer isn't decodable.
function decodeJpeg(buffer) {
  if (!_jpeg || !buffer) return null;
  try {
    const img = _jpeg.decode(buffer, { useTArray: true, maxMemoryUsageInMB: 64 });
    if (!img || !img.data || !img.width || !img.height) return null;
    return { width: img.width, height: img.height, data: img.data };
  } catch (_) { return null; }
}

// Decode the storyboard frame buffers and judge motion.
//   < 2 frames decode -> { moving: null, score: null }  (unknown)
//   otherwise         -> { moving: score >= threshold, score }
function assessMotion(frameBuffers, opts) {
  opts = opts || {};
  const decode = opts.decode || decodeJpeg;
  const threshold = opts.threshold != null ? opts.threshold : MOTION_THRESHOLD;
  const hashes = [];
  for (const buf of (frameBuffers || [])) {
    const frame = decode(buf);
    if (frame && frame.data && frame.width && frame.height) hashes.push(aHash(frame));
  }
  if (hashes.length < 2) return { moving: null, score: null };
  const score = motionScoreOf(hashes);
  return { moving: score >= threshold, score };
}

// Best of the eligible candidates: highest score, then most views. Null if none.
function selectBest(candidates) {
  if (!candidates || !candidates.length) return null;
  return candidates.slice().sort(
    (a, b) => (b.score - a.score) || ((b.views || 0) - (a.views || 0))
  )[0] || null;
}

function embedUrlFor(id) {
  return "https://www.youtube-nocookie.com/embed/" + id +
    "?autoplay=1&mute=1&controls=0&modestbranding=1&playsinline=1&rel=0&loop=1&playlist=" +
    id + "&enablejsapi=1";
}

// Orchestrator. All I/O injected (httpJson, httpBuffer, and optionally a decode
// stub) so it runs fully offline in tests. Returns { videoId, embedUrl } or null.
async function selectDisplayVideo({ artist, track, youtubeKey, httpJson, httpBuffer, decode, debug }) {
  if (!youtubeKey || !artist || !track) return null;
  const log = debug ? (...a) => console.error("[display:youtube]", ...a) : () => {};
  try {
    const artistN = normalize(artist);
    const trackTokens = normalize(track).split(" ").filter(t => t.length > 2);

    // 1) search.list — score + keep the plausible ones, best first.
    const searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
      "&videoEmbeddable=true&videoSyndicated=true&maxResults=25" +
      "&q=" + encodeURIComponent(artist + " " + track) +
      "&key=" + encodeURIComponent(youtubeKey);
    const searchJson = await httpJson(searchUrl);
    const scored = ((searchJson && searchJson.items) || [])
      .filter(it => it && it.id && it.id.videoId && it.snippet)
      .map(it => ({ id: it.id.videoId, item: it, score: scoreVideo(it, artistN, trackTokens) }))
      .filter(c => c.score > 0)
      .sort((a, b) => b.score - a.score);
    if (!scored.length) return null;

    // 2) videos.list — keep only embeddable + public + not age-restricted; grab views.
    const videosUrl = "https://www.googleapis.com/youtube/v3/videos?part=status,contentDetails,statistics" +
      "&id=" + encodeURIComponent(scored.map(c => c.id).join(",")) +
      "&key=" + encodeURIComponent(youtubeKey);
    const videosJson = await httpJson(videosUrl);
    const viewsById = new Map();
    for (const v of ((videosJson && videosJson.items) || [])) {
      const ok = v && v.status && v.status.embeddable && v.status.privacyStatus === "public" &&
        !(v.contentDetails && v.contentDetails.contentRating &&
          v.contentDetails.contentRating.ytRating === "ytAgeRestricted");
      if (ok) viewsById.set(v.id, parseInt((v.statistics && v.statistics.viewCount) || "0", 10) || 0);
    }
    const verified = scored.filter(c => viewsById.has(c.id)).slice(0, MOTION_CANDIDATE_CAP);
    if (!verified.length) return null;

    // 3) MOTION GATE — fetch the three storyboard frames per candidate and hash them.
    const eligible = [];
    for (const c of verified) {
      const buffers = await Promise.all([1, 2, 3].map(
        n => Promise.resolve()
          .then(() => httpBuffer("https://i.ytimg.com/vi/" + c.id + "/mq" + n + ".jpg"))
          .catch(() => null)
      ));
      const { moving, score } = assessMotion(buffers, { decode });
      const views = viewsById.get(c.id) || 0;
      if (moving === true) {
        eligible.push({ id: c.id, score: c.score, views });
      } else if (moving === false) {
        log("reject static", c.id, "motion=" + score);
      } else if (isHighTrust(c.item, artistN)) {
        // Frames unreadable but the upload looks official → fail closed, keep it.
        eligible.push({ id: c.id, score: c.score, views });
      } else {
        log("reject uncertain (no frames, not high-trust)", c.id);
      }
    }

    const best = selectBest(eligible);
    if (!best) return null;
    return { videoId: best.id, embedUrl: embedUrlFor(best.id) };
  } catch (e) {
    log(e && e.message);
    return null;
  }
}

module.exports = {
  MOTION_THRESHOLD,
  MOTION_CANDIDATE_CAP,
  REJECT,
  scoreVideo,
  isHighTrust,
  channelTrust,
  aHash,
  hamming,
  motionScoreOf,
  decodeJpeg,
  assessMotion,
  selectBest,
  embedUrlFor,
  selectDisplayVideo
};
