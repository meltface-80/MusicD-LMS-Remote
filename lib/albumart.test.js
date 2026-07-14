// Tests for the artwork rescue + store. Run: node lib/albumart.test.js
// Network is mocked (global.fetch) so this stays hermetic; the MusicBrainz /
// Cover Art Archive request *shapes* are asserted against the mock.
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { makeAlbumArt } = require("./albumart");
const search = require("./search");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aart-"));
let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

// 1x1 PNG
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");

// ---- mock fetch: MusicBrainz release-group search + Cover Art Archive image ----
const seen = [];
global.fetch = async (url) => {
  seen.push(url);
  if (url.includes("/ws/2/release-group/")) {
    return { ok: true, status: 200, async json() {
      return { "release-groups": [
        { id: "rg-123", score: 100, title: "Homework", "artist-credit": [{ name: "Daft Punk" }] }
      ] };
    } };
  }
  if (url.includes("coverartarchive.org")) {
    return { ok: true, status: 200,
      headers: { get: (h) => (h.toLowerCase() === "content-type" ? "image/png" : null) },
      async arrayBuffer() { return PNG.buffer.slice(PNG.byteOffset, PNG.byteOffset + PNG.byteLength); } };
  }
  return { ok: false, status: 404, headers: { get: () => null }, async json() { return {}; }, async arrayBuffer() { return new ArrayBuffer(0); } };
};

// No LMS plugin, no Qobuz — force the MusicBrainz→CAA path.
const art = makeAlbumArt({
  getLms: () => null,
  qobuzCall: null,
  qobuz: null,
  dataDir: dir,
  normalize: search.normalize,
  artistKey: search.artistKey,
  debug: false
});

(async () => {
  // candidates(): MBID → CAA release; then release-group search → CAA release-group
  const cands = await art.candidates({ title: "Homework", artist: "Daft Punk", mbid: "mb-rel-9" });
  const urls = cands.map(c => c.url);
  assert.ok(urls.some(u => u === "https://coverartarchive.org/release/mb-rel-9/front-1200"),
    "MBID → CAA release front: " + JSON.stringify(urls));
  assert.ok(urls.some(u => u === "https://coverartarchive.org/release-group/rg-123/front-1200"),
    "release-group search → CAA release-group front: " + JSON.stringify(urls));
  const rgReq = seen.find(u => u.includes("/ws/2/release-group/"));
  const rgDecoded = decodeURIComponent(rgReq);
  assert.ok(/releasegroup:"Homework"/.test(rgDecoded) && /artist:"Daft Punk"/.test(rgDecoded),
    "release-group query carries quoted title + artist: " + rgDecoded);
  ok("candidates: MBID and release-group both yield Cover Art Archive fronts");

  // resolve(): downloads + stores the first working candidate
  const key = await art.resolve({ title: "Homework", artist: "Daft Punk", mbid: "mb-rel-9" });
  assert.ok(key && key.startsWith("art-"), "resolve returns an art- key: " + key);
  assert.strictEqual(art.storedFor("Homework", "Daft Punk"), key);
  const stored = art.read(key);
  assert.ok(stored && stored.body.length === PNG.length && stored.type === "image/png");
  ok("resolve downloads + stores the cover; read() returns the bytes");

  // second resolve is a cache hit (no new download)
  const before = seen.length;
  const key2 = await art.resolve({ title: "Homework", artist: "Daft Punk", mbid: "mb-rel-9" });
  assert.strictEqual(key2, key);
  assert.strictEqual(seen.length, before, "no network on cache hit");
  ok("resolve is cached per album (no repeat network)");

  // choosing a different cover mints a NEW key and drops the old file
  const oldFile = art.read(key);
  const key3 = await art.saveFromUrl("Homework", "Daft Punk", "https://coverartarchive.org/release/other/front-1200", "Manual");
  assert.notStrictEqual(key3, key, "new URL → new content-addressed key");
  assert.strictEqual(art.storedFor("Homework", "Daft Punk"), key3);
  assert.strictEqual(art.read(key), null, "superseded file removed");
  ok("saveFromUrl mints a new key and removes the superseded cover");

  // a failed lookup is remembered as a miss (won't hammer the network)
  const none = await art.resolve({ title: "Nonexistent", artist: "Nobody", mbid: null });
  assert.strictEqual(none, null);
  ok("a full miss returns null");

  // sweep only touches records lacking image_key, mutating them via onFound
  const recs = [
    { title: "Homework", subtitle: "Daft Punk", mbid: "mb-rel-9", image_key: null },
    { title: "Has Art",  subtitle: "Someone",   mbid: null,       image_key: "lms-1" }
  ];
  const found = [];
  const r = await art.sweep(recs, (rec, k) => { rec.image_key = k; found.push(rec.title); });
  assert.strictEqual(r.found, 1);
  assert.deepStrictEqual(found, ["Homework"]);
  assert.ok(recs[0].image_key && recs[0].image_key.startsWith("art-"));
  assert.strictEqual(recs[1].image_key, "lms-1");   // untouched
  ok("sweep fills covers only for art-less records, in place");

  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${n}/${n} album-art tests passed.`);
})().catch(e => { console.error("FAIL:", e); process.exit(1); });
