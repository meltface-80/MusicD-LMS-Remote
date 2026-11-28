// @ts-check
"use strict";
/*
 * lmswatch.js — when LMS stops answering and comes back, what THIS server was asking of it at the time (owner report:
 * "got to get to the bottom of why Lyrion keeps restarting/crashing"). Every command lib/lms.js sends is begun and ended
 * here; the last [KEEP] are remembered with how long each took. When a command fails the way an LMS that has gone fails
 * (connection refused or reset, a hang-up, a timeout), the moment is noted with the commands in flight and the last
 * ones before; when one succeeds again, the outage is logged (warn) and kept for /api/lms/outages. A gap under
 * [MIN_GAP_MS] is not an outage (LMS closes the socket on a command it can't dispatch, which looks the same).
 * Slow commands (over [SLOW_MS]) are logged too: LMS is single-threaded, and a slow answer is everything else waiting.
 */
const { makeLogger } = require("./log");

const KEEP = 40;
const MIN_GAP_MS = 3000;
const SLOW_MS = 5000;
const GONE = /ECONNREFUSED|ECONNRESET|EHOSTUNREACH|ENETUNREACH|ETIMEDOUT|socket hang up|timed? ?out/i;

/** @param {{ log: any, now?: () => number }} o */
function makeLmsWatch(o) {
  const now = o.now || Date.now;
  /** @type {{ label: string, at: number, ms: number, error: string|null }[]} */
  const recent = [];
  /** @type {Map<number, { label: string, at: number }>} */
  const inFlight = new Map();
  /** @type {{ at: number, error: string, inFlight: string[], before: string[] }|null} */
  let down = null;
  /** @type {any[]} */
  const outages = [];
  let n = 0;

  const line = (/** @type {{ label: string, ms?: number, error?: string|null }} */ r) => r.label + (r.ms != null ? ` (${r.ms} ms${r.error ? ", " + r.error : ""})` : "");

  /** A command sent: its handle for [end]. @param {string} label */
  function begin(label) { const id = ++n; inFlight.set(id, { label, at: now() }); return id; }

  /** A command answered ([error] null) or failed. @param {number} id @param {string|null} [error] */
  function end(id, error = null) {
    const r = inFlight.get(id);
    if (!r) return;
    inFlight.delete(id);
    const ms = now() - r.at;
    recent.push({ label: r.label, at: r.at, ms, error });
    if (recent.length > KEEP) recent.shift();
    if (!error && ms > SLOW_MS) o.log.warn(`LMS took ${(ms / 1000).toFixed(1)} s to answer: ${r.label}`);
    if (error && GONE.test(error)) {
      if (!down) down = { at: now(), error, inFlight: [r.label, ...[...inFlight.values()].map((x) => x.label)], before: recent.slice(-15, -1).map(line) };
      return;
    }
    if (!error && down) {
      const gap = now() - down.at;
      if (gap >= MIN_GAP_MS) {
        const out = { from: new Date(down.at).toISOString(), seconds: Math.round(gap / 1000), error: down.error, inFlight: down.inFlight, before: down.before };
        outages.push(out);
        if (outages.length > 20) outages.shift();
        o.log.warn(`LMS answers again after ${out.seconds} s away (${out.error}) — ${out.seconds >= 10 ? "it restarted, by the look of it" : "a pause"}. ` +
          `In flight when it went: ${out.inFlight.join(" | ") || "nothing"}. The last before: ${out.before.join(" | ") || "nothing"}.`);
      }
      down = null;
    }
  }

  return { begin, end, outages: () => outages.slice(), recent: () => recent.map(line), get down() { return down; } };
}

/** The server's one watch, for lib/lms.js (cfg.watch) and /api/lms/outages. */
const watch = makeLmsWatch({ log: makeLogger("lms-watch") });

module.exports = { makeLmsWatch, watch };
