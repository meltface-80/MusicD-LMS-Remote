"use strict";

/* Favourites store. The point of this collection is that it is keyed on
 * something that SURVIVES a library rescan and works for albums LMS has never
 * seen, so most of these tests are about identity rather than storage. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const makeFavourites = require("./favourites");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra !== undefined ? "— " + JSON.stringify(extra) : ""); }
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fav-test-"));
const FILE = path.join(dir, "favourites.json");
const mk = () => makeFavourites({ dataDir: dir });
const reset = () => { try { fs.unlinkSync(FILE); } catch (e) {} };

// ---- add / has / remove ----
{
  reset();
  const f = mk();
  ok("empty to start", f.count() === 0 && !f.has("Kid A", "Radiohead"));
  f.add({ title: "Kid A", subtitle: "Radiohead", image_key: "c1" });
  ok("add then has", f.has("Kid A", "Radiohead"));
  ok("count is 1", f.count() === 1, f.count());
  ok("remove works", f.remove("Kid A", "Radiohead") === true && f.count() === 0);
  ok("removing what isn't there is false", f.remove("Kid A", "Radiohead") === false);
}

// ---- identity survives the things that move ----
{
  reset();
  const f = mk();
  // Favourited from the library, with an offset and an LMS id.
  f.add({ title: "In Rainbows", subtitle: "Radiohead", image_key: "c9", offset: 412, id: "77" });
  // After a rescan the offset and id are different — identity must not care.
  ok("still favourited after ids/offsets change", f.has("In Rainbows", "Radiohead"));
  const rec = f.list()[0];
  ok("neither offset nor id is stored as identity",
     rec.key.indexOf("412") === -1 && rec.key.indexOf("77") === -1, rec.key);
}

// ---- normalisation ----
{
  reset();
  const f = mk();
  f.add({ title: "Café Bleu", subtitle: "The Style Council" });
  ok("accents fold", f.has("Cafe Bleu", "The Style Council"));
  ok("case folds", f.has("CAFE BLEU", "the style council"));
  f.add({ title: "Vol. 1", subtitle: "X" });
  ok("punctuation folds", f.has("Vol 1", "X"));
  ok("different artists stay distinct",
     f.has("Vol. 1", "X") && !f.has("Vol. 1", "Y"));
}

// ---- works for albums that aren't in the library at all ----
{
  reset();
  const f = mk();
  f.add({ title: "Catalogue Only", artist: "Some Band", source: "qobuz", qobuz_id: "12345" });
  const rec = f.list()[0];
  ok("a Qobuz-only album can be favourited", f.has("Catalogue Only", "Some Band"));
  ok("its source and service id are kept", rec.source === "qobuz" && rec.qobuz_id === "12345", rec);
  ok("`artist` is accepted as well as `subtitle`", rec.artist === "Some Band", rec.artist);
}

// ---- re-adding refreshes context without duplicating or reordering ----
{
  reset();
  const f = mk();
  f.add({ title: "A", subtitle: "B", image_key: "old" });
  const firstAt = f.list()[0].at;
  f.add({ title: "A", subtitle: "B", image_key: "better", qobuz_id: "999" });
  const rows = f.list();
  ok("re-adding does not duplicate", rows.length === 1, rows.length);
  ok("re-adding refreshes the cover", rows[0].image_key === "better", rows[0].image_key);
  ok("re-adding fills in a newly-known id", rows[0].qobuz_id === "999");
  ok("re-adding keeps the original added-time", rows[0].at === firstAt);
}

// ---- toggle ----
{
  reset();
  const f = mk();
  const alb = { title: "T", subtitle: "A" };
  ok("toggle on", f.toggle(alb) === true && f.has("T", "A"));
  ok("toggle off", f.toggle(alb) === false && !f.has("T", "A"));
  ok("forced on", f.toggle(alb, true) === true && f.has("T", "A"));
  ok("forced on again is idempotent", f.toggle(alb, true) === true && f.count() === 1);
  ok("forced off", f.toggle(alb, false) === false && f.count() === 0);
}

// ---- ordering ----
{
  reset();
  const f = mk();
  f.add({ title: "First", subtitle: "A" });
  // Force a distinct timestamp rather than relying on clock resolution.
  const rows = JSON.parse(fs.readFileSync(FILE, "utf8")).entries;
  rows[0].at = Date.now() - 60000;
  fs.writeFileSync(FILE, JSON.stringify({ entries: rows }));
  f.add({ title: "Second", subtitle: "B" });
  ok("newest first", f.list()[0].title === "Second", f.list().map(r => r.title));
}

// ---- persistence + robustness ----
{
  reset();
  mk().add({ title: "Persisted", subtitle: "Artist" });
  ok("survives a fresh instance", mk().has("Persisted", "Artist"));

  const f = mk();
  ok("an album with no title is refused", f.add({ title: "", subtitle: "X" }) === null);
  ok("and is not counted", f.count() === 1, f.count());
  ok("has() on a blank title is false", f.has("", "") === false);

  fs.writeFileSync(FILE, "{{{ not json");
  ok("a corrupt file reads as empty", mk().count() === 0);
  ok("and can still be written to", (mk().add({ title: "After", subtitle: "Corrupt" }), mk().has("After", "Corrupt")));

  // A bare array (an older/hand-edited shape) should still load. The key has to
  // agree with the title/artist — that IS the identity, so a row whose key
  // disagrees is unfindable by name, which is correct behaviour.
  fs.writeFileSync(FILE, JSON.stringify([{ key: makeFavourites.keyFor("Bare", "Array"), title: "Bare", artist: "Array", at: 1 }]));
  ok("a bare-array file still loads", mk().count() === 1 && mk().has("Bare", "Array"));

  // Rows without a key are junk and must be dropped, not surfaced.
  fs.writeFileSync(FILE, JSON.stringify({ entries: [{ title: "No key" }, { key: "a|b", title: "Good", artist: "" , at: 1 }] }));
  ok("keyless rows are dropped", mk().count() === 1 && mk().list()[0].title === "Good");
}

// ---- keys() for marking tiles ----
{
  reset();
  const f = mk();
  f.add({ title: "One", subtitle: "A" });
  f.add({ title: "Two", subtitle: "B" });
  const ks = f.keys();
  ok("keys() returns a Set of both", ks.size === 2 && ks.has(f.keyFor("One", "A")), [...ks]);
  ok("keyFor matches what's stored", ks.has(makeFavourites.keyFor("Two", "B")));
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log("\n" + pass + "/" + (pass + fail) + " favourites tests passed.");
process.exit(fail ? 1 : 0);
