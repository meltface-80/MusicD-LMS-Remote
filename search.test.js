// Tests for the search index. Run: node lms/lib/search.test.js
"use strict";
const assert = require("assert");
const s = require("./search");

const rows = [
  { id: 10, offset: 0, title: "Kind of Blue",            subtitle: "Miles Davis",   year: 1959, coverId: "a" },
  { id: 11, offset: 1, title: "Blue Train",              subtitle: "John Coltrane", year: 1957, coverId: "b" },
  { id: 12, offset: 2, title: "The Dark Side of the Moon", subtitle: "Pink Floyd",  year: 1973, coverId: "c" },
  { id: 13, offset: 3, title: "Björk – Homogénic",        subtitle: "Björk",        year: 1997, coverId: "d" },
  { id: 14, offset: 4, title: "Watermelon Man",           subtitle: "Herbie Hancock feat. Miles Davis", year: 1962, coverId: "e" }
];

const index = s.makeIndex();
s.loadRecords(index, rows);

let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

// index maps
assert.strictEqual(index.records.length, 5);
assert.strictEqual(index.byOffset.get(2).title, "The Dark Side of the Moon");
assert.strictEqual(index.byId.get("13").offset, 3);
ok("loadRecords builds byId/byOffset maps");

// image_key carried from coverId
assert.strictEqual(index.byOffset.get(0).image_key, "a");
ok("image_key = coverId");

// exact title match ranks first, correct output shape
const r1 = s.searchAlbums(index, "kind of blue");
assert.strictEqual(r1[0].title, "Kind of Blue");
assert.deepStrictEqual(Object.keys(r1[0]).sort(), ["image_key","offset","score","subtitle","title"]);
ok("searchAlbums exact match + output shape");

// token-prefix: "dark moon" finds Dark Side of the Moon
const r2 = s.searchAlbums(index, "dark moon");
assert.ok(r2.some(x => x.title === "The Dark Side of the Moon"));
ok("searchAlbums order-independent token prefixes");

// diacritics-insensitive: "bjork" finds Björk
const r3 = s.searchAlbums(index, "bjork");
assert.ok(r3.some(x => x.title.startsWith("Björk")));
ok("searchAlbums diacritics-insensitive");

// artist search splits featured credits: "miles" returns Miles Davis once
const a1 = s.searchArtists(index, "miles");
assert.ok(a1.includes("Miles Davis"));
assert.strictEqual(a1.filter(x => x === "Miles Davis").length, 1);
ok("searchArtists dedupes across primary + featured credits");

// artist search prefix ranking: "cole" -> John Coltrane present
const a2 = s.searchArtists(index, "colt");
assert.ok(a2.includes("John Coltrane"));
ok("searchArtists matches substring within name");

// empty query returns nothing
assert.deepStrictEqual(s.searchAlbums(index, ""), []);
assert.deepStrictEqual(s.searchArtists(index, ""), []);
ok("empty query returns []");

console.log(`\n${n}/${n} search tests passed.`);
