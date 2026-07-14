"use strict";

/* Tiny leveled logger — the app's single logging surface.
 *
 * Levels, most→least severe: error, warn, info, debug, trace.
 * Verbosity comes from the environment, resolved once at startup:
 *   LOG_LEVEL=error|warn|info|debug|trace   explicit, wins over DEBUG
 *   DEBUG=trace                             → trace (the firehose)
 *   DEBUG=1 (or true/debug)                 → debug (rich diagnostics)
 *   (unset)                                 → info  (quiet, production)
 *
 * Each line: "HH:MM:SS.mmm LEVEL [tag] message…". error/warn go to stderr,
 * everything else to stdout, so `docker logs` interleaves them in order.
 *
 * Usage:
 *   const { makeLogger } = require("./log");
 *   const log = makeLogger("albumart");
 *   log.info("sweep done", found + "/" + scanned);
 *   log.trace("mai albumcovers →", urls.length, "candidates");
 *   if (log.enabled("trace")) log.trace(expensiveToBuild());
 */

const LEVELS = { error: 0, warn: 1, info: 2, debug: 3, trace: 4 };

function resolveLevel() {
  const explicit = String(process.env.LOG_LEVEL || "").trim().toLowerCase();
  if (explicit && Object.prototype.hasOwnProperty.call(LEVELS, explicit)) return LEVELS[explicit];
  const dbg = String(process.env.DEBUG || "").trim().toLowerCase();
  if (dbg === "trace") return LEVELS.trace;
  if (dbg === "1" || dbg === "true" || dbg === "debug" || dbg === "yes") return LEVELS.debug;
  return LEVELS.info;
}

let currentLevel = resolveLevel();

function ts() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, "0");
  return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds()) + "." + p(d.getMilliseconds(), 3);
}

function emit(level, tag, args) {
  if (LEVELS[level] > currentLevel) return;
  const prefix = ts() + " " + level.toUpperCase().padEnd(5) + " [" + tag + "]";
  const stream = (level === "error" || level === "warn") ? console.error : console.log;
  stream(prefix, ...args);
}

// A tagged logger. `.child("sub")` extends the tag ("lms" → "lms:rpc").
function makeLogger(tag) {
  return {
    error: (...a) => emit("error", tag, a),
    warn:  (...a) => emit("warn",  tag, a),
    info:  (...a) => emit("info",  tag, a),
    debug: (...a) => emit("debug", tag, a),
    trace: (...a) => emit("trace", tag, a),
    // True when this level would actually print — guard expensive message building.
    enabled: (level) => (LEVELS[level] != null) && LEVELS[level] <= currentLevel,
    child: (sub) => makeLogger(tag + ":" + sub)
  };
}

// A no-op logger (same shape) for modules given no logger — keeps call sites
// unconditional without printing anything.
function noopLogger() {
  const noop = () => {};
  return { error: noop, warn: noop, info: noop, debug: noop, trace: noop, enabled: () => false, child: () => noopLogger() };
}

module.exports = {
  makeLogger,
  noopLogger,
  levelName: () => Object.keys(LEVELS).find(k => LEVELS[k] === currentLevel),
  setLevel: (name) => { const n = String(name).toLowerCase(); if (n in LEVELS) currentLevel = LEVELS[n]; return module.exports.levelName(); },
  LEVELS
};
