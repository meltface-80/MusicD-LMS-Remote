"use strict";
// The diagnostic log (lib/diagnostics.js, lib/scrub.js; owner request: logging "off by default", "capture all Lyrion
// logs also", downloaded from Mallard and the browser). Pinned: off by default and nothing taken in while off; on, the
// server logs at debug and takes Mallard's and the page's lines under their names; the download has the header, this
// server's log and Lyrion's server.log and scanner.log; every line without what is the owner's alone; off again, back to
// the level it had.
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const express = require("express");
const logMod = require("./log");
const { mountDiagnostics } = require("./diagnostics");
const { scrub } = require("./scrub");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
setTimeout(() => { console.log("  [FAIL] timed out"); process.exit(1); }, 30000).unref();

// ---- the rules, as Mallard's LogRedact
ok("a token, a password, a URL's login: hidden", scrub("GET /x?token=abc123&x=1") === "GET /x?token=[…]&x=1" && scrub("password: hunter2") === "password: […]" &&
  scrub("http://me:pw@192.168.1.5:9000/") === "http://[…]@192.168.1.5:9000/");
ok("an email and a long key: hidden; a UUID kept", scrub("from a@b.com") === "from [email]" && scrub("key ABCDEFGHIJKLMNOPQRSTUVWXYZ012345") === "key […]" &&
  scrub("id 123e4567-e89b-12d3-a456-426614174000") === "id 123e4567-e89b-12d3-a456-426614174000");
ok("internet and Tailscale addresses hidden, the home network's kept", scrub("8.8.8.8 100.81.92.67 192.168.0.248 musicd-lms.tail95de0c.ts.net") === "[wan] [tailscale] 192.168.0.248 [tailscale]");
ok("the user's own folder hidden", scrub("/home/lewis/music/a.flac") === "/home/[user]/music/a.flac");

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "diag-"));
  logMod.setLogFile(path.join(dir, "musicd.log"));
  logMod.setLevel("info");
  // A stand-in Lyrion: its two logs over its web server, as LMS serves them (?full=1).
  const lyrion = http.createServer((req, res) => {
    if (req.url === "/server.log?full=1") return res.end("[26-11-28 13:00:00] Slim::Web::HTTP server line from me@example.com at 8.8.4.4\n");
    if (req.url === "/scanner.log?full=1") return res.end("[26-11-28 12:00:00] scanner: done\n");
    res.statusCode = 404; res.end();
  });
  await new Promise((r) => lyrion.listen(0, "127.0.0.1", r));
  let flags = {};
  const features = { enabled: (id) => flags[id] === true, setEnabled: (id, on) => { flags[id] = !!on; return flags[id]; } };
  const app = express(); app.use(express.json({ limit: "1mb" }));
  mountDiagnostics(app, { features, logMod, lms: () => ({ cfg: { host: "127.0.0.1", port: lyrion.address().port } }), version: "9.9.9",
    state: () => ({ connected: true, server: { version: "9.1.0" }, players: [{ id: "aa:bb", name: "Kitchen", model: "squeezelite" }] }), outages: () => [] });
  const srv = await new Promise((r) => { const s = app.listen(0, "127.0.0.1", () => r(s)); });
  const call = (method, p, body) => new Promise((resolve) => {
    const req = http.request({ host: "127.0.0.1", port: srv.address().port, path: p, method, headers: { "Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (iPhone)" } }, (res) => {
      let b = ""; res.on("data", (d) => b += d); res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: b }));
    });
    req.end(body ? JSON.stringify(body) : undefined);
  });
  const logText = () => fs.readFileSync(path.join(dir, "musicd.log"), "utf8");

  ok("off by default", JSON.parse((await call("GET", "/api/diagnostics")).body).enabled === false && logMod.levelName() === "info");
  await call("POST", "/api/diagnostics/app", { device: "Pixel 9", text: "2026-11-28 13:00:00.000 UTC  INFO   [NET] off line\n" });
  ok("…and while off, nothing is taken in", !logText().includes("off line"));

  await call("POST", "/api/diagnostics", { enabled: true });
  ok("on: the server logs at debug", logMod.levelName() === "debug" && flags.diagnostics === true);
  ok("…with a snapshot of what it is and holds", /\[snapshot\] v9\.9\.9 .*Lyrion connected 9\.1\.0; players: Kitchen \(squeezelite\)/.test(logText()));
  await call("POST", "/api/diagnostics/app", { device: "Pixel 9", text: "2026-11-28 13:00:01.000 UTC  WARN   [NET] GET /api/x → HTTP 500 after 40 ms token=sekrit\n2026-11-28 13:00:02.000 UTC  INFO   [TAP] 120,300 on home\n" });
  const t1 = logText();
  ok("Mallard's lines, under the phone's name, each at its own level", /WARN {2}\[app Pixel 9\] .*\[NET\] GET \/api\/x → HTTP 500/.test(t1) && /INFO {2}\[app Pixel 9\] .*\[TAP\] 120,300/.test(t1));
  ok("…without what is the owner's alone", t1.includes("token=[…]") && !t1.includes("sekrit"));
  await call("POST", "/api/diagnostics/page", { lines: [{ level: "error", text: "TypeError: x is undefined at app.js:12" }] });
  ok("the web page's errors, under the browser's kind", /ERROR \[web iOS\] TypeError: x is undefined/.test(logText()));

  const dl = await call("GET", "/api/diagnostics/log");
  ok("the download: a file to save", dl.status === 200 && /attachment; filename="musicd-lms-remote-log-\d{12}\.txt"/.test(dl.headers["content-disposition"]) && dl.headers["content-type"].startsWith("text/plain"));
  ok("…its header: versions, Lyrion, the players", dl.body.startsWith("Mallard — diagnostic log") && dl.body.includes("Server v9.9.9") && dl.body.includes("Lyrion: connected, version 9.1.0") && dl.body.includes("Kitchen [aa:bb]"));
  ok("…this server's log", dl.body.includes("===== Mallard server: musicd.log =====") && dl.body.includes("[app Pixel 9]"));
  ok("…and Lyrion's own server.log and scanner.log", dl.body.includes("===== Lyrion: server.log =====") && dl.body.includes("Slim::Web::HTTP server line") && dl.body.includes("===== Lyrion: scanner.log =====") && dl.body.includes("scanner: done"));
  ok("…Lyrion's lines without what is the owner's alone too", dl.body.includes("from [email] at [wan]") && !dl.body.includes("me@example.com"));

  await call("POST", "/api/diagnostics", { enabled: false });
  ok("off again: back to the level it had", logMod.levelName() === "info" && JSON.parse((await call("GET", "/api/diagnostics")).body).enabled === false);

  srv.close(); lyrion.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} diagnostic log tests passed.`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
