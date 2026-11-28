"use strict";
// Qobuz albums' record labels read from LMS's album info (lib/lmslabels.js), against LMS's own answer shapes:
// albuminfo items → loop_loop rows {id, name, hasitems}; the "On Qobuz" section → "Music Label: <name>".
const { makeLmsLabels } = require("./lmslabels");
let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

/** A fake LMS: per album, its sections and the Qobuz section's rows; `cold` albums lack the label the first time. */
function fakeLms(albums) {
  const calls = [], asked = new Map();
  const request = async (player, words) => {
    calls.push(player + " " + words.join(" "));
    const id = String(words.find((w) => String(w).startsWith("album_id:"))).slice(9);
    const a = albums[id];
    if (!a) throw new Error("socket hang up");
    const item = words.find((w) => String(w).startsWith("item_id:"));
    if (!item) return { count: a.top.length, loop_loop: a.top };
    const n = (asked.get(id) || 0) + 1; asked.set(id, n);
    const rows = a.cold && n === 1 ? a.rows.filter((r) => !/Label/.test(r.name)) : a.rows;
    return { count: rows.length, loop_loop: rows };
  };
  return { request, calls };
}
const onQobuz = [{ id: "a1.0", name: "Artwork", hasitems: 1 }, { id: "a1.1", name: "On Qobuz", hasitems: 1 }];
const rows = (label) => [{ id: "a1.1.0", name: "Manage Favorites", hasitems: 1 }, { id: "a1.1.1", name: "Search Artist 'Eivør'" }, { id: "a1.1.2", name: "Music Label: " + label }];

(async () => {
  const lms = fakeLms({
    "11": { top: onQobuz, rows: rows("Nuclear Blast") },
    "12": { top: onQobuz, rows: rows("Warp Records"), cold: true },
    "13": { top: [{ id: "x.0", name: "Artwork", hasitems: 1 }], rows: [] },
    "14": { top: [{ id: "y.0", name: "Op Qobuz", hasitems: 1 }], rows: [{ id: "y.0.0", name: "Muzieklabel: Sub Pop" }] },
  });
  const waits = [];
  const ll = makeLmsLabels({ request: lms.request, wait: async (ms) => { waits.push(ms); } });
  ok("the plugin's label line, from the On Qobuz section", (await ll.labelFor(11)) === "Nuclear Blast");
  ok("the section is opened by its id", lms.calls.includes(" albuminfo items 0 100 album_id:11 item_id:a1.1"));
  ok("an album the plugin fetches only now is asked once more", (await ll.labelFor(12)) === "Warp Records" && waits.length === 1);
  ok("no Qobuz section: no label, nothing waited for", (await ll.labelFor(13)) === null && waits.length === 1);
  ok("another language's words for it", (await ll.labelFor(14)) === "Sub Pop");

  const seen = [], progress = [];
  const albums = [
    { id: 11, qobuz_id: "q11", title: "Tungl", subtitle: "Eivør" },
    { id: 12, qobuz_id: "q12", title: "B", subtitle: "x" },
    { id: 20, qobuz_id: null, title: "Local", subtitle: "y" },        // not a Qobuz album: left to the rest of the scan
    { id: 99, qobuz_id: "q99", title: "Gone", subtitle: "z" },        // LMS closes the socket: skipped, not fatal
  ];
  const out = await ll.labelsFor(albums, (al) => al.title + "|" + al.subtitle, (d, t) => progress.push(d + "/" + t), (al, l) => { seen.push(al.title + "=" + l); });
  ok("only the albums LMS holds as Qobuz albums are asked", !lms.calls.some((c) => /album_id:20/.test(c)));
  ok("labels keyed for the scan, each handed over as found", out.get("Tungl|Eivør") === "Nuclear Blast" && out.get("B|x") === "Warp Records" && out.size === 2 && seen.length === 2);
  ok("a failure skips one album, the rest go on", progress.length === 3 && progress[2] === "3/3");

  // Gently: one at a time with a pause, misses remembered for 30 days, stopped by a scan or a run of failures.
  const fs = require("fs"), os = require("os"), path = require("path");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lmslabels-"));
  const triedFile = path.join(dir, "tried.json");
  const lms2 = fakeLms({
    "11": { top: onQobuz, rows: rows("Nuclear Blast") },
    "13": { top: [{ id: "x.0", name: "Artwork", hasitems: 1 }], rows: [] },
  });
  let inFlight = 0, most = 0;
  const gaps = [];
  const slow = async (p, w) => { inFlight++; most = Math.max(most, inFlight); await new Promise((r) => setTimeout(r, 5)); try { return await lms2.request(p, w); } finally { inFlight--; } };
  const gentle = makeLmsLabels({ request: slow, wait: async (ms) => { gaps.push(ms); }, triedFile });
  const two = [{ id: 11, qobuz_id: "q", title: "Tungl", subtitle: "E" }, { id: 13, qobuz_id: "q", title: "None", subtitle: "N" }];
  await gentle.labelsFor(two, (al) => al.title);
  ok("one album at a time", most === 1);
  ok("a pause between albums", gaps.filter((g) => g === 1500).length === 1);
  ok("an album LMS had no label for is written down", Object.keys(JSON.parse(fs.readFileSync(triedFile, "utf8"))).join() === "None");
  const before = lms2.calls.length;
  await gentle.labelsFor(two, (al) => al.title);
  ok("…and not asked again on the next pass", !lms2.calls.slice(before).some((c) => /album_id:13/.test(c)));
  const later = makeLmsLabels({ request: lms2.request, wait: async () => {}, triedFile, now: () => Date.now() + 31 * 24 * 3600 * 1000 });
  const b2 = lms2.calls.length;
  await later.labelsFor(two, (al) => al.title);
  ok("…until 30 days have passed", lms2.calls.slice(b2).some((c) => /album_id:13/.test(c)));

  const scanning = makeLmsLabels({ request: lms2.request, wait: async () => {}, busy: () => true });
  const b3 = lms2.calls.length;
  await scanning.labelsFor(two, (al) => al.title);
  ok("nothing is asked while LMS scans", lms2.calls.length === b3);

  let asks = 0;
  const failing = makeLmsLabels({ request: async () => { asks++; throw new Error("socket hang up"); }, wait: async () => {} });
  const many = Array.from({ length: 20 }, (_, i) => ({ id: 100 + i, qobuz_id: "q", title: "T" + i, subtitle: "s" }));
  await failing.labelsFor(many, (al) => al.title);
  ok("a run of failures ends the pass", asks === 5);
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} LMS label tests passed.`);
  if (fail) process.exit(1);
})();
