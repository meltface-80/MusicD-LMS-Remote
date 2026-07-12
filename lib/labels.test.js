// Tests for the labels index + persistence. Run: node lib/labels.test.js
// No network — every network pass is skipped by giving the factory an empty
// album list where a scan would otherwise reach out, and by exercising the
// pure helpers + in-memory index + JSON round-trip directly.
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const L = require("./labels");
const { normalize } = require("./search");

let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

// --- pure helpers ---------------------------------------------------------
// Suffix stripping: "Records"/"Music" variants collapse to one group.
assert.strictEqual(L.labelGroupKey("Blue Note Records"), L.labelGroupKey("Blue Note"));
assert.strictEqual(L.labelGroupKey("ACT Music"), L.labelGroupKey("ACT"));
assert.strictEqual(L.labelGroupKey("XYZ Music Records"), L.labelGroupKey("XYZ"));
ok("labelGroupKey strips label suffixes");

// Country/region stripping: "[PIAS] America" / "[PIAS] Belgium" -> "[PIAS]".
assert.strictEqual(L.labelGroupKey("[PIAS] America"), L.labelGroupKey("[PIAS]"));
assert.strictEqual(L.labelGroupKey("[PIAS] Belgium"), L.labelGroupKey("[PIAS]"));
assert.strictEqual(L.labelGroupKey("Universal Music Canada"), L.labelGroupKey("Universal Music France"));
ok("labelGroupKey strips country/region qualifiers");

// canonicalName keeps original casing/punctuation.
assert.strictEqual(L.canonicalLabelName("Blue Note Records"), "Blue Note");
assert.strictEqual(L.canonicalLabelName("[PIAS] America"), "[PIAS]");
ok("canonicalLabelName preserves display form");

// isLikelyNotALabel filters management/booking/etc.
assert.strictEqual(L.isLikelyNotALabel("Some Artist Management"), true);
assert.strictEqual(L.isLikelyNotALabel("Creative Booking Agency"), true);
assert.strictEqual(L.isLikelyNotALabel(""), true);
assert.strictEqual(L.isLikelyNotALabel("Warp"), false);
ok("isLikelyNotALabel rejects non-labels, accepts real ones");

// --- factory with a scratch data dir --------------------------------------
const tmp = fs.mkdtempSync(path.join(process.env.TMPDIR || "/tmp/claude-0", "labels-test-"));
const albums = [
  { offset: 0, id: "1", title: "Homework",  subtitle: "Daft Punk", image_key: "c0" },
  { offset: 1, id: "2", title: "Discovery", subtitle: "Daft Punk", image_key: "c1" },
  { offset: 2, id: "3", title: "Untrue",    subtitle: "Burial",    image_key: "c2" },
];
let albumList = albums.slice();
const labels = L.makeLabels({ dataDir: tmp, getAlbums: () => albumList, normalize });

// Directly drive the in-memory index (stands in for a scan hit) via the
// internal add helper by seeding the disk cache then re-seeding.
function key(al) { return normalize(al.title) + "||" + normalize(al.subtitle); }
labels._setName(key(albums[0]), "Virgin Records");
labels._setName(key(albums[1]), "Virgin");         // groups with Virgin Records
labels._setName(key(albums[2]), "Hyperdub");
labels.seedFromCache();

// Grouping + image_key backfill + album dedupe.
const virginKey = labels.groupKey("Virgin");
const vEntry = labels._index.map.get(virginKey);
assert.ok(vEntry, "Virgin group exists");
assert.strictEqual(vEntry.albums.length, 2);            // Homework + Discovery grouped
assert.strictEqual(vEntry.image_key, "c0");             // backfilled from first album
labels.seedFromCache();                                 // idempotent — no dupes
assert.strictEqual(labels._index.map.get(virginKey).albums.length, 2);
ok("index groups labels, backfills image_key, dedupes albums");

// listLabels shape + sort.
const list = labels.listLabels();
assert.ok(Array.isArray(list) && list.length === 2);
assert.deepStrictEqual(Object.keys(list[0]).sort(), ["albumCount","image_key","key","logo_url","mergedFrom","subtitle","title"]);
assert.strictEqual(list.find(l => l.title === "Virgin").subtitle, "2 albums");
assert.strictEqual(list.find(l => l.title === "Hyperdub").subtitle, "1 album");
ok("listLabels output shape + album-count subtitle");

// labelAlbums alpha order.
const la = labels.labelAlbums("Virgin", "alpha");
assert.deepStrictEqual(la.albums.map(a => a.title), ["Discovery", "Homework"]);
assert.strictEqual(la.total, 2);
ok("labelAlbums returns ordered album list");

// source badge flows through: a Qobuz-source album carries source:"qobuz",
// a local one carries source:null (drives the "Q" tile badge in the browser).
// Isolated factory so it doesn't perturb the shared index used below.
{
  const srcTmp = fs.mkdtempSync(path.join(process.env.TMPDIR || "/tmp/claude-0", "labels-src-"));
  const srcAlbums = [
    { offset: 0, id: "q1", title: "Random Access Memories", subtitle: "Daft Punk", image_key: "cq", source: "qobuz" },
    { offset: 1, id: "l1", title: "Alive 2007",             subtitle: "Daft Punk", image_key: "cl" },
  ];
  const srcLabels = L.makeLabels({ dataDir: srcTmp, getAlbums: () => srcAlbums, normalize });
  srcLabels._setName(key(srcAlbums[0]), "Virgin");
  srcLabels._setName(key(srcAlbums[1]), "Virgin");
  srcLabels.seedFromCache();
  const vAlbums = srcLabels.labelAlbums("Virgin", "alpha").albums;
  assert.strictEqual(vAlbums.find(a => a.title === "Random Access Memories").source, "qobuz");
  assert.strictEqual(vAlbums.find(a => a.title === "Alive 2007").source, null);
  try { fs.rmSync(srcTmp, { recursive: true, force: true }); } catch (e) {}
}
ok("labelAlbums preserves per-album source for tile badges");

// searchLabels ranking: prefix match ranks before substring; caps at 10.
labels._setName("x||y", "Virginia Sound");
albumList = albums.concat([{ offset: 3, id: "4", title: "X", subtitle: "Y", image_key: "c3" }]);
labels.seedFromCache();
const sl = labels.searchLabels("virgin");
assert.strictEqual(sl[0].display, "Virgin");             // exact-prefix first
assert.ok(sl.some(x => x.display === "Virginia Sound"));
ok("searchLabels ranks prefix matches first");

// --- merges ---------------------------------------------------------------
const hyperKey = labels.groupKey("Hyperdub");
labels.mergeLabels([{ key: virginKey, display: "Virgin" }, { key: hyperKey, display: "Hyperdub" }]);
assert.ok(!labels._index.map.has(hyperKey), "merged source key gone from index");
assert.strictEqual(labels._index.map.get(virginKey).albums.length, 3); // Untrue folded in
const merged = labels.listLabels().find(l => l.key === virginKey);
assert.strictEqual(merged.mergedFrom.length, 1);
assert.strictEqual(merged.mergedFrom[0].display, "Hyperdub");
ok("mergeLabels redirects source albums into target");

labels.unmerge(hyperKey);
assert.ok(labels._index.map.has(hyperKey), "unmerge restores the source label");
assert.strictEqual(labels._index.map.get(virginKey).albums.length, 2);
ok("unmerge rebuilds the split");

// --- week candidates (>= minAlbums, stable sort) --------------------------
for (let i = 0; i < 8; i++) labels._setName("big" + i + "||a", "BigLabel");
albumList = albums.concat(Array.from({ length: 8 }, (_, i) => ({ offset: 100 + i, id: "b" + i, title: "big" + i, subtitle: "a", image_key: "k" })));
labels.seedFromCache();
const wc = labels.weekCandidates(6);
assert.ok(wc.keys.includes(labels.groupKey("BigLabel")));
assert.ok(!wc.keys.includes(hyperKey));                  // only 1 album, below threshold
const keys2 = labels.weekCandidates(6).keys;
assert.deepStrictEqual(wc.keys, keys2);                  // stable across calls
ok("weekCandidates filters by album count and sorts stably");

// --- persistence round-trip -----------------------------------------------
labels._flushNow();
assert.ok(fs.existsSync(labels._files.names), "names cache written");
assert.ok(fs.existsSync(labels._files.merges), "merges cache written");
const reopened = L.makeLabels({ dataDir: tmp, getAlbums: () => albumList, normalize });
reopened.seedFromCache();
assert.ok(reopened._index.map.get(labels.groupKey("BigLabel")), "labels survive reopen");
ok("caches persist across a fresh factory instance");

// --- override file priority over disk cache -------------------------------
fs.writeFileSync(path.join(tmp, "labels-override.json"),
  JSON.stringify([{ title: "Untrue", artist: "Burial", label: "Override Label" }]));
const withOverride = L.makeLabels({ dataDir: tmp, getAlbums: () => albums, normalize });
// disk cache still says Hyperdub for Untrue, but the override must win.
assert.strictEqual(withOverride.labelForAlbum({ title: "Untrue", subtitle: "Burial" }), "Override Label");
withOverride.seedFromCache();
assert.ok(withOverride._index.map.has(withOverride.groupKey("Override Label")));
assert.ok(!withOverride._index.map.has(withOverride.groupKey("Hyperdub")));
ok("labels-override.json wins over disk cache");

// --- forceRescan clears names but preserves mbid/logo/merge files ---------
labels._setMbid(virginKey, "mbid-123");
labels._setLogo(virginKey, "http://logo");
labels.mergeLabels([{ key: virginKey, display: "Virgin" }, { key: labels.groupKey("BigLabel"), display: "BigLabel" }]);
labels._flushNow();
const mbidBefore = fs.readFileSync(labels._files.mbid, "utf8");
const logoBefore = fs.readFileSync(labels._files.logo, "utf8");
const mergesBefore = fs.readFileSync(labels._files.merges, "utf8");
// forceRescan kicks an async scan; with an empty getAlbums it returns fast.
albumList = [];
const fr = labels.forceRescan();
assert.strictEqual(fr.ok, true);
labels._flushNow();
assert.strictEqual(fs.readFileSync(labels._files.mbid, "utf8"), mbidBefore, "mbid cache preserved");
assert.strictEqual(fs.readFileSync(labels._files.logo, "utf8"), logoBefore, "logo cache preserved");
assert.strictEqual(fs.readFileSync(labels._files.merges, "utf8"), mergesBefore, "merges preserved");
const namesAfter = JSON.parse(fs.readFileSync(labels._files.names, "utf8"));
assert.strictEqual(namesAfter.entries.length, 0, "name cache cleared by forceRescan");
ok("forceRescan clears name cache, preserves mbid/logo/merge caches");

// cleanup
try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (e) {}

console.log(`\n${n}/${n} labels tests passed.`);
