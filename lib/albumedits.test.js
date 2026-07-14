// Tests for the album-edit override store. Run: node lib/albumedits.test.js
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const makeAlbumEdits = require("./albumedits");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aedits-"));
let n = 0; const ok = (l) => { console.log("  [PASS]", l); n++; };

let edits = makeAlbumEdits({ dataDir: dir, debug: false });

// set + get, keyed by original LMS strings (normalized, case-insensitive)
edits.set("Untitled", "Unknown", { title: "Homework", artist: "Daft Punk", year: 1997 });
let e = edits.get("untitled", "UNKNOWN");
assert.strictEqual(e.title, "Homework");
assert.strictEqual(e.artist, "Daft Punk");
assert.strictEqual(e.year, 1997);
ok("set/get an edit (normalized, case-insensitive key)");

// applyToRow overlays fields + records original identity + stashes orig cover
let row = { title: "Untitled", subtitle: "Unknown", year: 2001, coverId: "lms-9" };
edits.set("Untitled", "Unknown", { art: "art-xyz" });
edits.applyToRow(row);
assert.strictEqual(row.title, "Homework");
assert.strictEqual(row.subtitle, "Daft Punk");
assert.strictEqual(row.year, 1997);
assert.strictEqual(row.coverId, "art-xyz");
assert.strictEqual(row.origCoverId, "lms-9");   // pre-override LMS cover preserved
assert.strictEqual(row.origTitle, "Untitled");
assert.strictEqual(row.origArtist, "Unknown");
assert.strictEqual(row.edited, true);
ok("applyToRow overlays edits + preserves original identity/cover");

// a row with no matching edit is untouched
let plain = { title: "Discovery", subtitle: "Daft Punk", year: 2001, coverId: "lms-1" };
edits.applyToRow(plain);
assert.strictEqual(plain.title, "Discovery");
assert.strictEqual(plain.edited, undefined);
ok("applyToRow leaves un-edited rows alone");

// clearing a field with "" removes just that override
edits.set("Untitled", "Unknown", { year: "" });
e = edits.get("Untitled", "Unknown");
assert.ok(!("year" in e), "year override cleared");
assert.strictEqual(e.title, "Homework");        // others survive
ok("empty string clears a single field override");

// persistence across a fresh factory instance (debounced write flushed)
edits.flushNow();
let edits2 = makeAlbumEdits({ dataDir: dir, debug: false });
e = edits2.get("Untitled", "Unknown");
assert.strictEqual(e.title, "Homework");
assert.strictEqual(e.art, "art-xyz");
ok("edits persist to disk and reload");

// remove drops the entry entirely
edits2.remove("Untitled", "Unknown");
assert.strictEqual(edits2.get("Untitled", "Unknown"), null);
edits2.flushNow();
let edits3 = makeAlbumEdits({ dataDir: dir, debug: false });
assert.strictEqual(edits3.get("Untitled", "Unknown"), null);
ok("remove deletes the override (and persists the deletion)");

// an edit that overrides nothing is not stored
edits3.set("Ghost", "Nobody", {});
assert.strictEqual(edits3.get("Ghost", "Nobody"), null);
ok("a no-op edit stores nothing");

fs.rmSync(dir, { recursive: true, force: true });
console.log(`\n${n}/${n} album-edit tests passed.`);
