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
