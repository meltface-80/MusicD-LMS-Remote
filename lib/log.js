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
 * ROON-STYLE FILE LOG: call setLogFile(path) once at startup to ALSO tee every
 * emitted line to a rotating log file. The current file grows to `maxBytes`
 * (default 8 MB), then rotates: current → .1, .1 → .2 … keeping `archives`
 * (default 10) numbered backups; the oldest is dropped. Console output is
 * unaffected (so `docker logs` still works).
 *
 * Usage:
 *   const { makeLogger } = require("./log");
 *   const log = makeLogger("albumart");
 *   log.info("sweep done", found + "/" + scanned);
 *   log.trace("mai albumcovers →", urls.length, "candidates");
 *   if (log.enabled("trace")) log.trace(expensiveToBuild());
 */

const fs = require("fs");
const path = require("path");
const util = require("util");

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

// ---- rotating file sink (optional; enabled by setLogFile) ------------------
let sink = null;   // { path, fd, maxBytes, archives, size }

function setLogFile(filePath, opts = {}) {
  try {
    if (sink && sink.fd != null) { try { fs.closeSync(sink.fd); } catch (e) {} }
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    let size = 0;
    try { size = fs.statSync(filePath).size; } catch (e) {}
    const fd = fs.openSync(filePath, "a");
    sink = {
      path: filePath, fd, size,
      maxBytes: opts.maxBytes || 8 * 1024 * 1024,
      archives: opts.archives != null ? opts.archives : 10
    };
    return true;
  } catch (e) { sink = null; return false; }
}

// current → .1, .1 → .2 … dropping the oldest beyond `archives`.
function rotate() {
  if (!sink) return;
  try { fs.closeSync(sink.fd); } catch (e) {}
  const arc = (n) => sink.path + "." + n;
  try { fs.unlinkSync(arc(sink.archives)); } catch (e) {}   // drop the oldest
  for (let n = sink.archives - 1; n >= 1; n--) {
    try { fs.renameSync(arc(n), arc(n + 1)); } catch (e) {}
  }
  try { fs.renameSync(sink.path, arc(1)); } catch (e) {}
  try { sink.fd = fs.openSync(sink.path, "a"); sink.size = 0; }
  catch (e) { sink = null; }   // give up on the file; console still works
}

function fmtArg(a) {
  return typeof a === "string" ? a : util.inspect(a, { depth: 2, breakLength: Infinity });
}

function writeFile(prefix, args) {
  if (!sink) return;
  try {
    const buf = prefix + (args.length ? " " + args.map(fmtArg).join(" ") : "") + "\n";
    fs.writeSync(sink.fd, buf);
    sink.size += Buffer.byteLength(buf);
    if (sink.size >= sink.maxBytes) rotate();
  } catch (e) { /* logging must never crash the app */ }
}

function emit(level, tag, args) {
  if (LEVELS[level] > currentLevel) return;
  const prefix = ts() + " " + level.toUpperCase().padEnd(5) + " [" + tag + "]";
  const stream = (level === "error" || level === "warn") ? console.error : console.log;
  stream(prefix, ...args);
  writeFile(prefix, args);
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
  setLogFile,
  levelName: () => Object.keys(LEVELS).find(k => LEVELS[k] === currentLevel),
  setLevel: (name) => { const n = String(name).toLowerCase(); if (n in LEVELS) currentLevel = LEVELS[n]; return module.exports.levelName(); },
  LEVELS,
  _rotateNow: rotate   // exposed for tests
};
