"use strict";
// This phone's tracks by id, for its cache ahead (lib/phonefiles.js), against a stand-in LMS: its queue as `status`
// answers it, and its files at /music/<id>/download (with Range). Pinned: which track LMS is streaming (and none for a
// streaming service's or a seek's), the queue ahead in play order, a file passed through untouched with Range, Opus
// made once and kept, and the cache within its size.
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const crypto = require("crypto");
const express = require("express");
const { makePhoneFiles } = require("./phonefiles");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
const log = { info() {}, warn() {}, debug() {} };
setTimeout(() => { console.log("  [FAIL] timed out"); process.exit(1); }, 30000);

(async () => {
  const files = { 11: crypto.randomBytes(200000), 12: crypto.randomBytes(150000), 13: crypto.randomBytes(90000) };
  const lmsHttp = http.createServer((req, res) => {
    const m = /^\/music\/(\d+)\/download$/.exec(req.url);
    const body = m && files[m[1]];
    if (!body) { res.statusCode = 404; return res.end(); }
    if (req.headers.authorization !== "Basic " + Buffer.from("me:pw").toString("base64")) { res.statusCode = 401; return res.end(); }
    const r = /^bytes=(\d+)-$/.exec(req.headers.range || "");
    if (r) { const from = Number(r[1]); res.writeHead(206, { "Content-Type": "audio/flac", "Content-Range": `bytes ${from}-${body.length - 1}/${body.length}`, "Content-Length": body.length - from }); return res.end(body.subarray(from)); }
    res.writeHead(200, { "Content-Type": "audio/flac", "Content-Length": body.length, "Accept-Ranges": "bytes" }); res.end(body);
  });
  await new Promise((r) => lmsHttp.listen(0, "127.0.0.1", r));
  let status = { time: 0.4, playlist_loop: [{ id: 11 }, { id: 12 }, { id: -5, remote: 1 }, { id: 13 }, { id: 12 }] };
  const lms = { cfg: { host: "127.0.0.1", port: lmsHttp.address().port, username: "me", password: "pw" }, request: async (id, cmd) => {
    const n = Number(cmd[2]);
    return { time: status.time, playlist_loop: status.playlist_loop.slice(0, n) };
  } };
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonefiles-"));
  // A stand-in ffmpeg: fetches its -i address (with its sign-in) and writes "OPUS:" and the bytes; counts its runs.
  const ff = path.join(dir, "ffmpeg"), runs = path.join(dir, "runs");
  fs.writeFileSync(ff, `#!${process.execPath}\nconst a = process.argv.slice(2), u = new URL(a[a.indexOf("-i") + 1]);
require("fs").appendFileSync(${JSON.stringify(runs)}, "x");
require("http").get({ agent: false, host: u.hostname, port: u.port, path: u.pathname, headers: { Authorization: "Basic " + Buffer.from(decodeURIComponent(u.username) + ":" + decodeURIComponent(u.password)).toString("base64") } },
  (r) => { process.stdout.write("OPUS:"); r.pipe(process.stdout); });\n`, { mode: 0o755 });
  const f = makePhoneFiles({ lms: () => lms, dir: path.join(dir, "opus"), log, ffmpeg: ff, maxBytes: 300000 });

  ok("a fresh start: the track playing", (await f.identify("p", true)) === "11");
  ok("the next track queued (gapless): the one after it", (await f.identify("p", false)) === "12");
  status.time = 61;
  ok("a start part-way (a seek): none — its bytes begin mid-file", (await f.identify("p", true)) === null);
  status.time = 0.4;
  status.playlist_loop[1] = { id: -5, remote: 1 };
  ok("a streaming service's track: none", (await f.identify("p", false)) === null);
  status.playlist_loop[1] = { id: 12 };
  const ahead = await f.ahead("p", 4);
  ok("the queue ahead: after the one playing, in play order, the library's own, each once", ahead.map((t) => t.id).join() === "12,13");

  const app = express();
  app.get("/file", (req, res) => f.serve(req, res, String(req.query.id), req.query.q));
  const srv = await new Promise((r) => { const s = app.listen(0, "127.0.0.1", () => r(s)); });
  const get = (p, headers = {}) => new Promise((resolve) => http.get({ host: "127.0.0.1", port: srv.address().port, path: p, headers }, (res) => {
    const parts = []; res.on("data", (d) => parts.push(d)); res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
  }));
  const whole = await get("/file?id=11");
  ok("a track by id: the file itself, untouched", whole.status === 200 && whole.body.equals(files[11]) && whole.headers["content-type"] === "audio/flac");
  const part = await get("/file?id=11", { Range: "bytes=1000-" });
  ok("…Range passed through (a resumed fetch)", part.status === 206 && part.body.equals(files[11].subarray(1000)));
  ok("a bad id: 400", (await get("/file?id=x")).status === 400);

  const o1 = await get("/file?id=12&q=opus");
  ok("q=opus: its Opus, made from the file", o1.status === 200 && o1.headers["content-type"].startsWith("audio/ogg") && o1.body.equals(Buffer.concat([Buffer.from("OPUS:"), files[12]])));
  await get("/file?id=12&q=opus");
  ok("…made once and kept", fs.readFileSync(runs, "utf8").length === 1);
  const r2 = await get("/file?id=12&q=opus", { Range: "bytes=5-" });
  ok("…and read with Range", r2.status === 206 && r2.body.equals(files[12]));
  await new Promise((r) => setTimeout(r, 20));
  await get("/file?id=11&q=opus");
  await new Promise((r) => setTimeout(r, 100));
  const left = fs.readdirSync(path.join(dir, "opus")).filter((x) => x.endsWith(".ogg")).sort().join();
  ok("past its size, the least recently used goes first", left === "11.ogg");

  srv.close(); lmsHttp.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} phone file tests passed.`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
