"use strict";

/* Random Album Radio. The decision is the risky part — get it wrong and you
 * either double-queue albums or leave a player silent — so it is a pure
 * function and this is where the behaviour is pinned. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const makeRadio = require("./radio");
const { radioDecision } = makeRadio;

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra !== undefined ? "— " + JSON.stringify(extra) : ""); }
}

// st = { mode, index, total }; "remaining" is total - index - 1.
const st = (mode, index, total) => ({ mode, index, total });

// ---- off / deferring ----
{
  ok("disabled does nothing", radioDecision(st("play", 0, 1), false, false) === null);
  ok("no status does nothing", radioDecision(null, true, false) === null);
  // LMS's own filler owns the queue; two of them would interleave an album
  // with DSTM's tracks.
  ok("stands down when Don't Stop The Music is on",
     radioDecision(st("play", 0, 1), true, true) === null);
}

// ---- playing ----
{
  ok("queues while the LAST track plays", radioDecision(st("play", 4, 5), true, false) === "queue");
  ok("does nothing mid-album", radioDecision(st("play", 1, 5), true, false) === null);
  ok("does nothing with one track still to come", radioDecision(st("play", 3, 5), true, false) === null);
  // Appending only once the queue has drained would leave a gap; topping up on
  // the last track is what makes the join gapless.
  ok("a one-track queue tops up immediately", radioDecision(st("play", 0, 1), true, false) === "queue");
}

// ---- stopped ----
{
  ok("empty and stopped starts something", radioDecision(st("stop", null, 0), true, false) === "play");
  ok("stopped at the end of the queue starts something",
     radioDecision(st("stop", 4, 5), true, false) === "play");
  ok("stopped mid-queue is left alone (the owner stopped it)",
     radioDecision(st("stop", 1, 5), true, false) === null);
}

// ---- paused: never interrupt ----
{
  ok("paused on the last track does nothing", radioDecision(st("pause", 4, 5), true, false) === null);
  ok("paused on an empty queue does nothing", radioDecision(st("pause", null, 0), true, false) === null);
}

// ---- unknown / missing fields ----
{
  ok("missing totals while playing does nothing",
     radioDecision({ mode: "play" }, true, false) === null);
  ok("an unknown mode does nothing", radioDecision(st("buffering", 0, 1), true, false) === null);
}

// ---- the persisted zone set ----
{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "radio-test-"));
  const mk = () => makeRadio({ dataDir: dir });
  const r = mk();
  ok("starts empty", r.list().length === 0 && r.isOn("a") === false);
  r.set("aa:bb", true);
  ok("enabling is remembered", r.isOn("aa:bb") === true);
  ok("...and survives a restart", mk().isOn("aa:bb") === true);
  r.set("aa:bb", false);
  ok("disabling is remembered", mk().isOn("aa:bb") === false);
  r.set("aa:bb", true); r.set("cc:dd", true);
  // A player that has gone away must not keep a radio flag for ever.
  r.prune(["aa:bb"]);
  ok("a vanished player is pruned", r.isOn("aa:bb") === true && r.isOn("cc:dd") === false, r.list());
  ok("an empty zone id is refused", r.set("", true) === false);

  fs.writeFileSync(path.join(dir, "radio-zones.json"), "{{ not json");
  ok("a corrupt file reads as empty", mk().list().length === 0);
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) {}
}

console.log("\n" + pass + "/" + (pass + fail) + " radio tests passed.");
process.exit(fail ? 1 : 0);
