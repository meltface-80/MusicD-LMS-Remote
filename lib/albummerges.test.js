"use strict";

/* Album merges. The risky parts are identity (must survive a rescan) and the
 * collapse itself (must keep every part's LMS id, in order, or playback loses
 * discs) — so that's where the tests concentrate. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const makeMerges = require("./albummerges");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra !== undefined ? "— " + JSON.stringify(extra) : ""); }
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "merge-test-"));
const mk = () => makeMerges({ dataDir: dir });
const reset = () => { try { fs.unlinkSync(path.join(dir, "album-merges.json")); } catch (e) {} };
const row = (id, title, artist) => ({ id, title, subtitle: artist, coverId: "c" + id });

// ---- disc-suffix stripping ----
{
  const S = makeMerges.stripDiscSuffix;
  ok("(Disc 1) stripped", S("Sign o' the Times (Disc 1)") === "Sign o' the Times", S("Sign o' the Times (Disc 1)"));
  ok("bare Disc 2 stripped", S("Sandinista! Disc 2") === "Sandinista!", S("Sandinista! Disc 2"));
  ok("[CD2] stripped", S("Foo [CD2]") === "Foo");
  ok("dash separator stripped", S("Foo - Disc 3") === "Foo");
  ok("comma + Vol. stripped cleanly", S("Foo, Vol. 2") === "Foo", S("Foo, Vol. 2"));
  // The conservative half: these must NOT be truncated.
  ok("a real word starting 'disc' is safe", S("Disintegration") === "Disintegration");
  ok("no trailing number means no strip", S("Music for Airports") === "Music for Airports");
  ok("a title that IS only a marker keeps its name", S("Disc 1") === "Disc 1");
}

// ---- merging ----
{
  reset();
  const m = mk();
  ok("one album can't be a merge", m.merge([{ title: "A", artist: "X" }]).ok === false);
  ok("two rows that key identically are refused",
     m.merge([{ title: "A", artist: "X" }, { title: "a", artist: "x" }]).ok === false);

  const r = m.merge([
    { title: "Sign o' the Times (Disc 1)", artist: "Prince" },
    { title: "Sign o' the Times (Disc 2)", artist: "Prince" },
  ]);
  ok("two discs merge", r.ok === true);
  ok("the merged title drops the disc marker", r.merge.title === "Sign o' the Times", r.merge.title);
  ok("it is stored", m.count() === 1);
}

// ---- collapsing rows ----
{
  reset();
  const m = mk();
  m.merge([
    { title: "Set (Disc 1)", artist: "Band" },
    { title: "Set (Disc 2)", artist: "Band" },
    { title: "Set (Disc 3)", artist: "Band" },
  ]);
  const rows = [row(1, "Other", "Band"), row(10, "Set (Disc 1)", "Band"),
                row(11, "Set (Disc 2)", "Band"), row(12, "Set (Disc 3)", "Band"),
                row(2, "Another", "Band")];
  const out = m.apply(rows);
  ok("three rows collapse to one", out.length === 3, out.map(r => r.title));
  const merged = out.find(r => r.mergeId);
  ok("the merged row is titled without the marker", merged.title === "Set", merged.title);
  ok("it keeps every part's LMS id, in order", JSON.stringify(merged.partIds) === '["10","11","12"]', merged.partIds);
  ok("it reports how many parts", merged.partCount === 3, merged.partCount);
  ok("unmerged rows pass through untouched", out[0].title === "Other" && !out[0].mergeId);
  // Position matters: the merged row sits where its primary part was, so the
  // library order doesn't jump around after a merge.
  ok("the merged row sits at the primary's position", out[1].mergeId === merged.mergeId, out.map(r => r.title));
}

// ---- identity survives a rescan ----
{
  reset();
  const m = mk();
  m.merge([{ title: "Set (Disc 1)", artist: "Band" }, { title: "Set (Disc 2)", artist: "Band" }]);
  // Same albums, completely different LMS ids and a different order — exactly
  // what a rescan produces.
  const after = m.apply([row(999, "Set (Disc 2)", "Band"), row(998, "Set (Disc 1)", "Band")]);
  ok("still collapses after a rescan renumbers everything", after.length === 1);
  ok("and still orders parts by the merge, not the library",
     JSON.stringify(after[0].partIds) === '["998","999"]', after[0].partIds);
}

// ---- a missing part ----
{
  reset();
  const m = mk();
  m.merge([{ title: "Set (Disc 1)", artist: "B" }, { title: "Set (Disc 2)", artist: "B" }]);
  const out = m.apply([row(5, "Set (Disc 2)", "B")]);
  ok("a half-present set still reads as one album", out.length === 1 && out[0].mergeId, out);
  ok("with only the surviving part's id", JSON.stringify(out[0].partIds) === '["5"]', out[0].partIds);
}

// ---- a part can only belong to one merge ----
{
  reset();
  const m = mk();
  m.merge([{ title: "A1", artist: "X" }, { title: "A2", artist: "X" }]);
  m.merge([{ title: "A2", artist: "X" }, { title: "A3", artist: "X" }]);
  ok("re-merging a part dissolves the merge it left too small", m.count() === 1, m.list());
  const out = m.apply([row(1, "A1", "X"), row(2, "A2", "X"), row(3, "A3", "X")]);
  ok("A1 is loose again, A2+A3 are merged", out.length === 2, out.map(r => r.title));
}

// ---- unmerge ----
{
  reset();
  const m = mk();
  const r = m.merge([{ title: "S (Disc 1)", artist: "B" }, { title: "S (Disc 2)", artist: "B" }]);
  ok("unmerging an unknown id fails", m.unmerge("nope").ok === false);
  ok("unmerge works", m.unmerge(r.merge.id).ok === true && m.count() === 0);
  const out = m.apply([row(1, "S (Disc 1)", "B"), row(2, "S (Disc 2)", "B")]);
  ok("the albums are separate again", out.length === 2 && !out[0].mergeId);
}

// ---- renames must not break a merge (the v1.0.51 bug) ----
// The edit layer runs BEFORE apply(), renaming the raw row in place and
// stashing the LMS name in origTitle/origArtist. Keying on the DISPLAYED title
// meant a rename changed the very string the part key came from.
{
  reset();
  const m = mk();
  m.merge([
    { title: "Sandinista!", artist: "The Clash", origTitle: "Sandinista!", origArtist: "The Clash" },
    { title: "Sandinista! Disc 2", artist: "The Clash", origTitle: "Sandinista! Disc 2", origArtist: "The Clash" },
  ]);
  // The owner renames the MERGED album; the edit layer would rename the
  // primary raw row, since that is the row an album edit keys on.
  const renamed = { id: 1, title: "Sandinista! (Complete)", subtitle: "The Clash",
                    origTitle: "Sandinista!", origArtist: "The Clash" };
  const disc2 = row(2, "Sandinista! Disc 2", "The Clash");
  let out = m.apply([renamed, disc2]);
  ok("a renamed primary still belongs to its merge",
     out.length === 1 && out[0].partCount === 2, out.map(r => r.title));

  // ...and a renamed NON-primary too.
  const disc2r = { id: 2, title: "Sandinista! II", subtitle: "The Clash",
                   origTitle: "Sandinista! Disc 2", origArtist: "The Clash" };
  out = m.apply([renamed, disc2r]);
  ok("a renamed second disc stays absorbed",
     out.length === 1 && out[0].partIds.join(",") === "1,2", out.map(r => r.title));

  // The merged row must expose the PRIMARY's LMS identity, not the merged
  // title — artwork and year edits key on it.
  ok("the merged row carries the primary's original LMS name",
     out[0].origTitle === "Sandinista!" && out[0].origArtist === "The Clash",
     { t: out[0].origTitle, a: out[0].origArtist });

  // Renaming the merged album goes through the merge record, so the parts are
  // untouched and the collapse still holds.
  const rn = m.rename(m.list()[0].id, "Sandinista! (Complete)", "The Clash");
  ok("rename updates the merge record", rn.ok && rn.merge.title === "Sandinista! (Complete)");
  out = m.apply([renamed, disc2r]);
  ok("the renamed merged album is still one album",
     out.length === 1 && out[0].title === "Sandinista! (Complete)" && out[0].partCount === 2,
     out.map(r => r.title));
  // ...and clearing the rename falls back to the derived title.
  m.rename(m.list()[0].id, null, null);
  ok("clearing the rename re-derives from the primary part",
     m.apply([renamed, disc2r])[0].title === "Sandinista!");
}

// Two discs the owner renamed to the SAME corrected title used to collide on
// key and be refused outright ("look like the same record"), because the key
// came from the display name. Their LMS names differ, so they key apart.
{
  reset();
  const m = mk();
  const r = m.merge([
    { title: "Quadrophenia", artist: "The Who", origTitle: "Quadrophenia CD1", origArtist: "The Who" },
    { title: "Quadrophenia", artist: "The Who", origTitle: "Quadrophenia CD2", origArtist: "The Who" },
  ]);
  ok("identically-renamed discs still merge", r.ok, r.error);
  const out = m.apply([
    { id: 1, title: "Quadrophenia", subtitle: "The Who", origTitle: "Quadrophenia CD1", origArtist: "The Who" },
    { id: 2, title: "Quadrophenia", subtitle: "The Who", origTitle: "Quadrophenia CD2", origArtist: "The Who" },
  ]);
  ok("...and both discs are absorbed, neither lost",
     out.length === 1 && out[0].partIds.join(",") === "1,2", out.map(r2 => r2.partIds));
}

// A merge written before v1.0.51 stored keys derived from the DISPLAYED title.
{
  reset();
  const FILE0 = path.join(dir, "album-merges.json");
  fs.writeFileSync(FILE0, JSON.stringify({ entries: [{ id: "old", at: 1, title: "Set",
    parts: [{ key: "set disc 1|b", title: "Set (Disc 1)", artist: "B" },
            { key: "set disc 2|b", title: "Set (Disc 2)", artist: "B" }] }] }));
  const out = mk().apply([row(1, "Set (Disc 1)", "B"), row(2, "Set (Disc 2)", "B")]);
  ok("a pre-v1.0.51 merge still collapses", out.length === 1 && out[0].partCount === 2);
}

// Two library rows genuinely sharing one name must BOTH be absorbed. Keeping
// only the first dropped the second from the output entirely — an album
// vanishing from the library, tracks and all.
{
  reset();
  const m = mk();
  m.merge([{ title: "Twin", artist: "B" }, { title: "Twin (Disc 2)", artist: "B" }]);
  const out = m.apply([row(1, "Twin", "B"), row(2, "Twin", "B"), row(3, "Twin (Disc 2)", "B")]);
  ok("a duplicate row is absorbed, never dropped",
     out.length === 1 && out[0].partIds.join(",") === "1,2,3", out.map(r => r.partIds));
}

// ---- a rescan that moves the ARTIST string (the reported bug) ----
// The part key is title+artist, and the artist half is SCAN-DERIVED: LMS
// re-runs various-artist detection and re-reads ALBUMARTIST/ARTIST on every
// rescan, so a disc can come back as "Various Artists" with its title
// untouched. The key then matched nothing and the set silently split.
{
  reset();
  const m = mk();
  m.merge([
    { title: "Sandinista!", artist: "The Clash", id: "1" },
    { title: "Sandinista! Disc 2", artist: "The Clash", id: "2" },
  ]);
  // Same ids, artist re-reported by the rescan.
  let out = m.apply([
    { id: 1, title: "Sandinista!", subtitle: "Various Artists" },
    { id: 2, title: "Sandinista! Disc 2", subtitle: "Various Artists" },
  ]);
  ok("a rescan that changes the artist keeps the set merged",
     out.length === 1 && out[0].partCount === 2, out.map(r => r.title + "/" + (r.partCount || 1)));

  // The repair is written back, so the next rebuild is an exact key match
  // rather than a recovery.
  const keys = m.list()[0].parts.map(p => p.key);
  ok("the moved parts are re-keyed on disk", keys.every(k => /various artists$/.test(k)), keys);
}

// A FULL rescan renumbers ids too, so the id handle is gone as well — only the
// title is left. That is recoverable when the title is unambiguous.
{
  reset();
  const m = mk();
  m.merge([
    { title: "Quadrophenia", artist: "The Who", id: "1" },
    { title: "Quadrophenia Disc 2", artist: "The Who", id: "2" },
  ]);
  const out = m.apply([
    { id: 9001, title: "Quadrophenia", subtitle: "Various" },
    { id: 9002, title: "Quadrophenia Disc 2", subtitle: "Various" },
    { id: 9003, title: "Something Else", subtitle: "Nobody" },
  ]);
  ok("new ids AND a new artist still recovers, on title",
     out.length === 2 && out[0].partCount === 2, out.map(r => r.title + "/" + (r.partCount || 1)));
  ok("the recovered parts pick up the new LMS ids",
     m.list()[0].parts.map(p => p.id).join(",") === "9001,9002", m.list()[0].parts.map(p => p.id));
}

// ...but it must NOT guess. Two albums sharing a title with different artists
// is exactly when a title-only match would absorb the wrong record.
{
  reset();
  const m = mk();
  m.merge([
    { title: "Greatest Hits", artist: "Band A", id: "1" },
    { title: "Greatest Hits Disc 2", artist: "Band A", id: "2" },
  ]);
  const out = m.apply([
    { id: 5001, title: "Greatest Hits", subtitle: "Band B" },
    { id: 5002, title: "Greatest Hits", subtitle: "Band C" },
    { id: 5003, title: "Greatest Hits Disc 2", subtitle: "Band A" },
  ]);
  // Two candidates for "Greatest Hits" -> ambiguous -> left alone. Only the
  // unambiguous disc 2 is claimed, so the merge holds one part.
  const titles = out.map(r => r.title + "|" + r.subtitle);
  ok("an ambiguous title is never guessed at",
     out.length === 3 && titles.includes("Greatest Hits|Band B") && titles.includes("Greatest Hits|Band C"),
     titles);
}

// A part recovered by id must not be stolen from a merge that matched it
// exactly — repairs only ever claim rows nothing else wanted.
{
  reset();
  const m = mk();
  m.merge([{ title: "One", artist: "A", id: "1" }, { title: "One Disc 2", artist: "A", id: "2" }]);
  const out = m.apply([
    { id: 1, title: "One", subtitle: "A" },
    { id: 2, title: "One Disc 2", subtitle: "A" },
  ]);
  ok("an exact match is untouched by the repair pass",
     out.length === 1 && out[0].partCount === 2, out.map(r => r.partCount));
}

// ---- robustness ----
{
  reset();
  const m = mk();
  ok("no merges means rows pass straight through", m.apply([row(1, "A", "B")]).length === 1);
  const FILE = path.join(dir, "album-merges.json");
  fs.writeFileSync(FILE, "{{{ not json");
  ok("a corrupt file reads as empty", mk().count() === 0);
  // A stored merge with only one part is meaningless and must be ignored.
  fs.writeFileSync(FILE, JSON.stringify({ entries: [{ id: "x", parts: [{ key: "a|b", title: "A", artist: "B" }] }] }));
  ok("a one-part merge is discarded on load", mk().count() === 0);
}

try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
console.log("\n" + pass + "/" + (pass + fail) + " album-merge tests passed.");
process.exit(fail ? 1 : 0);
