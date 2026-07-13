// Tests for the wall-display video picker. Run: node lib/displayvideo.test.js
// Fully offline: no network, no YouTube key. scoreVideo is exercised on canned
// search items; the perceptual-hash / motion logic on hand-built RGBA frames;
// assessMotion + selectDisplayVideo via injected decode + mocked httpJson /
// httpBuffer. One optional test round-trips through real jpeg-js if installed.
"use strict";
const assert = require("assert");
const D = require("./displayvideo");

let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

// -- helpers ----------------------------------------------------------------
const searchItem = (title, channel, id = "vid_" + Math.random().toString(36).slice(2)) =>
  ({ id: { videoId: id }, snippet: { title, channelTitle: channel } });
const normalize = require("./search").normalize;
const ARTIST_N = normalize("Radiohead");
const tokens = normalize("Creep").split(" ").filter(t => t.length > 2);

// Build a solid-colour RGBA frame.
function solidFrame(w, h, r, g, b) {
  const data = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) { data[i*4]=r; data[i*4+1]=g; data[i*4+2]=b; data[i*4+3]=255; }
  return { width: w, height: h, data };
}
// Left half colour A, right half colour B.
function splitFrame(w, h, a, b) {
  const data = new Uint8Array(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const c = x < w/2 ? a : b, i = (y*w+x)*4;
    data[i]=c[0]; data[i+1]=c[1]; data[i+2]=c[2]; data[i+3]=255;
  }
  return { width: w, height: h, data };
}

// ===========================================================================
// scoreVideo
// ===========================================================================
{
  // Proper official music video on the artist's VEVO channel scores high.
  const s1 = D.scoreVideo(searchItem("Radiohead - Creep (Official Music Video)", "RadioheadVEVO"), ARTIST_N, tokens);
  assert.ok(s1 >= 100, "official video on VEVO should be >=100, got " + s1); // 70 + 30
  ok("scoreVideo: Artist VEVO 'Official Music Video' scores high");

  // " - Topic" auto-upload → reject.
  assert.strictEqual(D.scoreVideo(searchItem("Creep", "Radiohead - Topic"), ARTIST_N, tokens), D.REJECT);
  ok("scoreVideo: ' - Topic' channel rejected");

  // Official AUDIO (static art) → reject.
  assert.strictEqual(D.scoreVideo(searchItem("Radiohead - Creep (Official Audio)", "RadioheadVEVO"), ARTIST_N, tokens), D.REJECT);
  ok("scoreVideo: '(Official Audio)' rejected");

  // Lyric / slideshow / nightcore / 1 hour / loop titles → reject.
  for (const t of [
    "Radiohead - Creep (Lyrics)",
    "Radiohead - Creep (Slideshow)",
    "Radiohead Creep Nightcore",
    "Radiohead Creep 1 Hour",
    "Radiohead Creep (Looped)"
  ]) {
    assert.strictEqual(D.scoreVideo(searchItem(t, "RadioheadVEVO"), ARTIST_N, tokens), D.REJECT, "should reject: " + t);
  }
  ok("scoreVideo: lyric/slideshow/nightcore/1 hour/loop rejected");

  // Missing a track token → reject (title has no "creep").
  assert.strictEqual(D.scoreVideo(searchItem("Radiohead - Karma Police (Official Video)", "RadioheadVEVO"), ARTIST_N, tokens), D.REJECT);
  ok("scoreVideo: title missing a track token rejected");

  // Bare "live" on a random (non-artist) channel → reject.
  assert.strictEqual(D.scoreVideo(searchItem("Radiohead Creep Live", "SomeFanChannel"), ARTIST_N, tokens), D.REJECT);
  ok("scoreVideo: bare 'live' on untrusted channel rejected");

  // "Live at X" on the artist's own channel → accept (trusted + live bonus).
  const sLive = D.scoreVideo(searchItem("Radiohead - Creep (Live at Glastonbury)", "Radiohead"), ARTIST_N, tokens);
  assert.ok(sLive >= 90, "live on artist channel should be >=90, got " + sLive); // 70 + 20
  ok("scoreVideo: 'Live at ...' on artist channel accepted");

  // Artist-adjacent channel (contains artist name) → kept but lower.
  const sAdj = D.scoreVideo(searchItem("Radiohead - Creep", "Radiohead Fans TV"), ARTIST_N, tokens);
  assert.ok(sAdj > 0 && sAdj < 70, "adjacent channel should be 40-ish, got " + sAdj);
  ok("scoreVideo: artist-adjacent channel kept below trusted threshold");
}

// ===========================================================================
// aHash / hamming / motionScoreOf / assessMotion on synthetic frames
// ===========================================================================
{
  const black = solidFrame(16, 16, 0, 0, 0);
  const white = solidFrame(16, 16, 255, 255, 255);
  const split = splitFrame(16, 16, [0,0,0], [255,255,255]);

  // Identical frames → hamming 0, motion 0, moving:false.
  const hB = D.aHash(black);
  assert.strictEqual(D.hamming(hB, D.aHash(solidFrame(16,16,0,0,0))), 0);
  assert.strictEqual(D.motionScoreOf([hB, hB, hB]), 0);
  ok("aHash/hamming: identical frames → distance 0");

  const identical = D.assessMotion([black, black, black], { decode: f => f });
  assert.deepStrictEqual(identical, { moving: false, score: 0 });
  ok("assessMotion: three identical frames → moving:false");

  // Clearly different frames → high motion → moving:true.
  const diff = D.assessMotion([black, white, split], { decode: f => f });
  assert.ok(diff.score >= D.MOTION_THRESHOLD, "score " + diff.score + " should clear threshold");
  assert.strictEqual(diff.moving, true);
  ok("assessMotion: black/white/split frames → moving:true");

  // A single decodable frame → unknown.
  const one = D.assessMotion([black], { decode: f => f });
  assert.deepStrictEqual(one, { moving: null, score: null });
  ok("assessMotion: single decodable frame → moving:null (unknown)");

  // Undecodable buffers (decode returns null) → unknown.
  const none = D.assessMotion([{}, {}, {}], { decode: () => null });
  assert.deepStrictEqual(none, { moving: null, score: null });
  ok("assessMotion: nothing decodes → moving:null (unknown)");

  // selectBest ranks by score then views.
  assert.strictEqual(D.selectBest([
    { id: "a", score: 90, views: 10 },
    { id: "b", score: 90, views: 99 },
    { id: "c", score: 70, views: 999 }
  ]).id, "b");
  assert.strictEqual(D.selectBest([]), null);
  ok("selectBest: highest score then most views, [] → null");
}

// ===========================================================================
// Optional: real jpeg-js round-trip (guards the decodeJpeg path).
// ===========================================================================
{
  let jpeg = null;
  try { jpeg = require("jpeg-js"); } catch (_) { jpeg = null; }
  if (jpeg) {
    // Use NON-uniform images: average-hash can't distinguish two solid colours
    // (every cell equals the mean), but two mirrored split frames hash apart.
    const imgA = splitFrame(16, 16, [0,0,0], [255,255,255]);   // left black, right white
    const imgB = splitFrame(16, 16, [255,255,255], [0,0,0]);   // mirror
    const encA = jpeg.encode(imgA, 90).data;
    const encB = jpeg.encode(imgB, 90).data;
    const fA = D.decodeJpeg(encA);
    const fB = D.decodeJpeg(encB);
    assert.ok(fA && fB, "decodeJpeg should decode real JPEGs");
    assert.ok(D.hamming(D.aHash(fA), D.aHash(fB)) > 0, "mirrored frames must hash differently");
    // Full path through assessMotion using the real decoder: A vs B differ → moving.
    const real = D.assessMotion([encA, encB, encA]);
    assert.strictEqual(real.moving, true);
    ok("decodeJpeg: real jpeg-js round-trip decodes + differing frames read as moving");
  } else {
    console.log("  [SKIP] jpeg-js not installed — real-decode round-trip skipped");
  }
}

// ===========================================================================
// selectDisplayVideo end-to-end with mocked httpJson + httpBuffer + decode
// ===========================================================================
(async () => {
  const STATIC_ID = "staticId0001";
  const MOVING_ID = "movingId0002";

  // Both candidates are on the artist's VEVO channel and pass scoreVideo.
  const searchResp = { items: [
    searchItem("Radiohead - Creep (Official Music Video)", "RadioheadVEVO", STATIC_ID),
    searchItem("Radiohead - Creep (Official Music Video)", "RadioheadVEVO", MOVING_ID)
  ] };
  const videosResp = { items: [
    { id: STATIC_ID, status: { embeddable: true, privacyStatus: "public" }, contentDetails: {}, statistics: { viewCount: "500" } },
    { id: MOVING_ID, status: { embeddable: true, privacyStatus: "public" }, contentDetails: {}, statistics: { viewCount: "100" } }
  ] };

  const httpJson = async (url) =>
    url.indexOf("/search?") !== -1 ? searchResp :
    url.indexOf("/videos?")  !== -1 ? videosResp : {};

  // httpBuffer: identical bytes for the static id, differing bytes per frame for
  // the moving id. A decode stub maps those sentinel buffers to synthetic frames.
  const buf = (tag) => Buffer.from(tag);
  const httpBuffer = async (url) => {
    const id = url.match(/\/vi\/([^/]+)\//)[1];
    const frame = url.match(/mq(\d)\.jpg/)[1];
    return id === STATIC_ID ? buf("SAME") : buf("MOVE" + frame);
  };
  const decode = (b) => {
    const s = b ? b.toString() : "";
    if (s === "SAME")  return solidFrame(16, 16, 10, 10, 10);
    if (s === "MOVE1") return solidFrame(16, 16, 0, 0, 0);
    if (s === "MOVE2") return solidFrame(16, 16, 255, 255, 255);
    if (s === "MOVE3") return splitFrame(16, 16, [0,0,0], [255,255,255]);
    return null;
  };

  const picked = await D.selectDisplayVideo({
    artist: "Radiohead", track: "Creep", youtubeKey: "KEY", httpJson, httpBuffer, decode
  });
  assert.ok(picked, "should pick a moving video");
  assert.strictEqual(picked.videoId, MOVING_ID, "must pick the MOVING id, not the static one");
  assert.ok(picked.embedUrl.indexOf(MOVING_ID) !== -1 && picked.embedUrl.indexOf("youtube-nocookie") !== -1);
  ok("selectDisplayVideo: rejects the static clip, returns the moving one's embedUrl");

  // No YouTube key → null (never calls the network).
  let called = false;
  const spyJson = async () => { called = true; return searchResp; };
  assert.strictEqual(await D.selectDisplayVideo({
    artist: "Radiohead", track: "Creep", youtubeKey: "", httpJson: spyJson, httpBuffer, decode
  }), null);
  assert.strictEqual(called, false, "must not hit the network without a key");
  ok("selectDisplayVideo: no youtubeKey → null");

  // All candidates static → null.
  const allStaticBuffer = async () => buf("SAME");
  assert.strictEqual(await D.selectDisplayVideo({
    artist: "Radiohead", track: "Creep", youtubeKey: "KEY", httpJson, httpBuffer: allStaticBuffer, decode
  }), null);
  ok("selectDisplayVideo: all-static candidates → null");

  // Fail-closed: frames unreadable (decode → null) but candidate is high-trust
  // (VEVO + Official Music Video) → kept.
  const failClosed = await D.selectDisplayVideo({
    artist: "Radiohead", track: "Creep", youtubeKey: "KEY", httpJson,
    httpBuffer: async () => null, decode: () => null
  });
  assert.ok(failClosed, "high-trust candidate should survive unreadable frames");
  ok("selectDisplayVideo: unreadable frames + high-trust → kept (fail-closed)");

  // Fail-closed rejects a non-high-trust candidate when frames are unreadable.
  const adjSearch = { items: [ searchItem("Radiohead - Creep", "Radiohead Fans TV", "adjId0003") ] };
  const adjVideos = { items: [ { id: "adjId0003", status: { embeddable: true, privacyStatus: "public" }, contentDetails: {}, statistics: { viewCount: "5" } } ] };
  const adjJson = async (url) => url.indexOf("/search?") !== -1 ? adjSearch : url.indexOf("/videos?") !== -1 ? adjVideos : {};
  assert.strictEqual(await D.selectDisplayVideo({
    artist: "Radiohead", track: "Creep", youtubeKey: "KEY", httpJson: adjJson,
    httpBuffer: async () => null, decode: () => null
  }), null);
  ok("selectDisplayVideo: unreadable frames + not high-trust → rejected");

  console.log(`\n${n}/${n} displayvideo tests passed.`);
})().catch((e) => { console.error(e); process.exit(1); });
