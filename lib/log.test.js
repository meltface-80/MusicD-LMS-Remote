// Tests for the leveled logger. Run: node lib/log.test.js
"use strict";
const assert = require("assert");

let n = 0; const ok = (l) => { console.error("  [PASS]", l); n++; };

// Capture console.log/error output while exercising the logger.
function capture(fn) {
  const out = [];
  const origLog = console.log, origErr = console.error;
  console.log = (...a) => out.push(["log", a.join(" ")]);
  console.error = (...a) => out.push(["err", a.join(" ")]);
  try { fn(); } finally { console.log = origLog; console.error = origErr; }
  return out;
}

// Fresh require of the module with a given env (clears the require cache so the
// level is re-resolved from process.env at load time).
function loadWith(env) {
  for (const k of ["LOG_LEVEL", "DEBUG"]) delete process.env[k];
  Object.assign(process.env, env);
  delete require.cache[require.resolve("./log")];
  return require("./log");
}

// default level = info: info/warn/error print, debug/trace suppressed
{
  const log = loadWith({}).makeLogger("t");
  const out = capture(() => { log.error("e"); log.warn("w"); log.info("i"); log.debug("d"); log.trace("tr"); });
  const joined = out.map(o => o[1]).join("|");
  assert.ok(/\be\b/.test(joined) && /\bw\b/.test(joined) && /\bi\b/.test(joined), "info level prints error/warn/info");
  assert.ok(!/\bd\b/.test(joined) && !/\btr\b/.test(joined), "info level suppresses debug/trace");
  ok("default level = info (debug/trace suppressed)");
}

// DEBUG=1 → debug: debug prints, trace still suppressed
{
  const log = loadWith({ DEBUG: "1" }).makeLogger("t");
  const out = capture(() => { log.debug("d"); log.trace("tr"); });
  const joined = out.map(o => o[1]).join("|");
  assert.ok(/\bd\b/.test(joined) && !/\btr\b/.test(joined), "DEBUG=1 prints debug, not trace: " + joined);
  ok("DEBUG=1 → debug level");
}

// DEBUG=trace → trace: everything prints
{
  const log = loadWith({ DEBUG: "trace" }).makeLogger("t");
  const out = capture(() => { log.trace("tr"); });
  assert.ok(out.map(o => o[1]).join("|").includes("tr"), "DEBUG=trace prints trace");
  ok("DEBUG=trace → trace level (firehose)");
}

// LOG_LEVEL wins over DEBUG
{
  const mod = loadWith({ DEBUG: "1", LOG_LEVEL: "warn" });
  const log = mod.makeLogger("t");
  const out = capture(() => { log.info("i"); log.warn("w"); });
  const joined = out.map(o => o[1]).join("|");
  assert.ok(!/\bi\b/.test(joined) && /\bw\b/.test(joined), "LOG_LEVEL=warn suppresses info even with DEBUG=1");
  assert.strictEqual(mod.levelName(), "warn");
  ok("LOG_LEVEL overrides DEBUG");
}

// format: "HH:MM:SS.mmm LEVEL [tag] message" + error/warn → stderr
{
  const log = loadWith({ LOG_LEVEL: "trace" }).makeLogger("lms");
  const out = capture(() => { log.info("hello", "world"); log.error("boom"); });
  const infoLine = out.find(o => o[1].includes("hello"));
  assert.ok(/^\d\d:\d\d:\d\d\.\d\d\d INFO {2}\[lms\] hello world$/.test(infoLine[1]), "format: " + infoLine[1]);
  assert.strictEqual(infoLine[0], "log", "info → stdout");
  assert.strictEqual(out.find(o => o[1].includes("boom"))[0], "err", "error → stderr");
  ok("line format + error/warn routed to stderr");
}

// child() extends the tag; enabled() reflects the level
{
  const mod = loadWith({ LOG_LEVEL: "debug" });
  const log = mod.makeLogger("albumart");
  const out = capture(() => { log.child("mb").debug("x"); });
  assert.ok(out[0][1].includes("[albumart:mb]"), "child tag: " + out[0][1]);
  assert.strictEqual(log.enabled("debug"), true);
  assert.strictEqual(log.enabled("trace"), false);
  ok("child() extends tag; enabled() gates by level");
}

// setLevel() flips verbosity at runtime
{
  const mod = loadWith({});
  const log = mod.makeLogger("t");
  let out = capture(() => log.debug("d1"));
  assert.strictEqual(out.length, 0, "debug quiet at info");
  mod.setLevel("trace");
  out = capture(() => log.debug("d2"));
  assert.ok(out.length === 1, "debug prints after setLevel(trace)");
  ok("setLevel() changes verbosity at runtime");
}

// rotating file sink: current + N archives, oldest dropped, current under cap
{
  const fs = require("fs"), os = require("os"), path = require("path");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "logrot-"));
  const file = path.join(dir, "logs", "musicd.log");
  const mod = loadWith({ LOG_LEVEL: "info" });
  assert.strictEqual(mod.setLogFile(file, { maxBytes: 1024, archives: 3 }), true);
  const l = mod.makeLogger("t");
  capture(() => { for (let i = 0; i < 800; i++) l.info("padded line", i, "to grow the file quickly"); });
  const files = fs.readdirSync(path.dirname(file));
  const archives = files.filter(f => /musicd\.log\.\d+$/.test(f)).map(f => Number(f.split(".").pop())).sort((a, b) => a - b);
  assert.ok(files.includes("musicd.log"), "current file exists");
  assert.ok(archives.length <= 3 && archives.length >= 1, "1..3 archives, got " + archives.join(","));
  assert.ok(!archives.includes(4), "never keeps a .4 (oldest dropped): " + archives.join(","));
  assert.ok(fs.statSync(file).size <= 1024, "current file stays under the cap");
  // the current file holds the most-recent lines
  assert.ok(fs.readFileSync(file, "utf8").includes("padded line 799"), "current file has the newest line");
  fs.rmSync(dir, { recursive: true, force: true });
  ok("rotating file sink: current + capped archives, oldest dropped, size-bounded");
}

// clean up so a later require in the same process gets default env
for (const k of ["LOG_LEVEL", "DEBUG"]) delete process.env[k];
console.error(`\n${n}/${n} log tests passed.`);
