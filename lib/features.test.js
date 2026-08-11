/*
 * Opt-in features and the Home row layout. Pure over an injected store, so
 * this runs without a server.
 *
 * The properties worth pinning are the ones that decide whether an UPGRADE is
 * safe: an existing install must not have its features switched off
 * underneath it, an unreadable data volume must not be written down as "no",
 * and a row added by a future update must appear rather than silently staying
 * hidden.
 */
"use strict";
const F = require("./features");

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra != null ? "— " + JSON.stringify(extra) : ""); }
}
const eq = (label, got, want) => ok(label, JSON.stringify(got) === JSON.stringify(want), { got, want });

// A store in memory, shaped like index.js's loadSettings/saveSettings.
function store(initial) {
  let s = { ...(initial || {}) };
  return { load: () => ({ ...s }), save: (p) => { s = { ...s, ...p }; return { ...s }; },
           peek: () => ({ ...s }) };
}
const quiet = { info() {}, warn() {}, debug() {} };

// ---- home rows: repair against the current vocabulary ----
{
  const ids = ["a", "b", "c"];
  eq("an absent layout is every row, in default order, all on",
     F.repairHomeRows(undefined, ids), [{ id: "a", on: true }, { id: "b", on: true }, { id: "c", on: true }]);
  eq("a stored order is honoured",
     F.repairHomeRows([{ id: "c", on: true }, { id: "a", on: false }], ids).map(r => r.id),
     ["c", "a", "b"]);
  ok("...and a row switched off stays off",
     F.repairHomeRows([{ id: "a", on: false }], ids).find(r => r.id === "a").on === false);
  // A row ADDED by an update must appear. If it defaulted to hidden, shipping
  // a row would mean nobody with an existing install ever saw it.
  ok("a row the stored layout has never heard of is appended, switched ON",
     F.repairHomeRows([{ id: "a", on: true }], ids).find(r => r.id === "c").on === true);
  // A row REMOVED by an update must not linger.
  ok("an unknown id is dropped",
     !F.repairHomeRows([{ id: "gone", on: true }], ids).some(r => r.id === "gone"));
  eq("duplicates collapse to the first",
     F.repairHomeRows([{ id: "b", on: false }, { id: "b", on: true }], ids)
       .filter(r => r.id === "b"), [{ id: "b", on: false }]);
  // Only an explicit false hides a row — a falsy-but-not-false stored value
  // (0, null, absent) is ON.
  eq("only an explicit false switches a row off",
     F.repairHomeRows([{ id: "a", on: 0 }, { id: "b", on: null }, { id: "c" }], ids)
       .map(r => r.on), [true, true, true]);
  eq("garbage entries are ignored", F.repairHomeRows([null, 7, { on: true }], ids).map(r => r.id), ids);
}

// ---- the three-valued flag ----
{
  // Absent + evidence of use = keep it on. This is what stops an upgrade
  // switching an existing user's features off underneath them.
  const r1 = F.inferDefaults({}, { labels: () => true, smartPicks: () => false }, ["labels", "smartPicks"]);
  eq("evidence of use reads as consent", r1.patch, { labels: true, smartPicks: false });
  eq("...and nothing is deferred", r1.deferred, []);

  // A flag already decided is never re-derived, even against opposite evidence.
  const r2 = F.inferDefaults({ labels: false }, { labels: () => true }, ["labels"]);
  eq("an explicit setting is never overridden by evidence", r2.patch, {});

  // NULL is "cannot tell", and must not be written down: recording it from an
  // unreadable data volume would switch an existing user off permanently, and
  // repairing the volume would not undo it.
  const r3 = F.inferDefaults({}, { labels: () => null }, ["labels"]);
  eq("unreadable evidence writes nothing", r3.patch, {});
  eq("...and says so, so it is re-derived next boot", r3.deferred, ["labels"]);

  // A throwing probe is the same case as null, not a crash and not a "no".
  const r4 = F.inferDefaults({}, { labels: () => { throw new Error("locked"); } }, ["labels"]);
  eq("a probe that throws defers rather than answering no", r4, { patch: {}, deferred: ["labels"] });
  eq("a missing probe defers too", F.inferDefaults({}, {}, ["labels"]).deferred, ["labels"]);
}

// ---- the factory ----
{
  const s = store();
  const f = F.makeFeatures({ load: s.load, save: s.save, log: quiet });
  ok("a fresh install starts with everything off", !f.enabled("labels") && !f.enabled("smartPicks"));
  ok("...and nothing decided", !f.isDecided("labels"));

  f.setEnabled("labels", true);
  ok("switching on takes effect immediately", f.enabled("labels") === true);
  ok("...and is persisted", s.peek().labels === true);
  ok("...and counts as decided", f.isDecided("labels"));
  eq("an unknown feature is refused", f.setEnabled("nope", true), null);

  // The inference must not touch a flag the user has already set.
  const r = f.applyDefaults({ labels: () => false, smartPicks: () => true });
  ok("the inference leaves an explicit setting alone", f.enabled("labels") === true);
  ok("...and decides the undecided one from evidence", f.enabled("smartPicks") === true, r);

  eq("all() reports both", f.all(), { labels: true, smartPicks: true });
}

// ---- home rows through the factory ----
{
  const s = store();
  const f = F.makeFeatures({ load: s.load, save: s.save, log: quiet });
  eq("the default layout is every row on", f.homeRows().map(r => r.on), F.HOME_ROW_IDS.map(() => true));

  const saved = f.setHomeRows([{ id: "random", on: false }, { id: "library", on: true }]);
  eq("saving returns the REPAIRED layout, not what was sent",
     saved.length, F.HOME_ROW_IDS.length);
  eq("...leading with what was sent", saved.slice(0, 2).map(r => r.id), ["random", "library"]);
  ok("...and it round-trips", JSON.stringify(f.homeRows()) === JSON.stringify(saved));
  ok("the off row stayed off", f.homeRows().find(r => r.id === "random").on === false);

  // A client that sends nothing recognisable is a bug worth reporting, not a
  // reason to silently reset someone's layout.
  eq("an unrecognisable payload is refused", f.setHomeRows([{ id: "bogus", on: true }]), null);
  eq("a non-array is refused", f.setHomeRows("rows"), null);
  ok("...and the stored layout is untouched",
     f.homeRows().find(r => r.id === "random").on === false);
}

console.log(fail ? `\n${fail} FAILED, ${pass} passed.` : `\n${pass}/${pass} feature-flag tests passed.`);
process.exit(fail ? 1 : 0);
