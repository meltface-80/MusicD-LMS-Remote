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
setTimeout(() => { console.log("  [FAIL] timed out"); process.exit(1); }, 30000).unref();

(async () => {
  let downloads = 0;
  const files = { 11: crypto.randomBytes(200000), 12: crypto.randomBytes(150000), 13: crypto.randomBytes(90000) };
  const lmsHttp = http.createServer((req, res) => {
    downloads++;
    if (req.url === "/music/99/download") return;   // LMS's download not answering (owner's server): never a byte
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
    if (cmd[0] === "titles") return { titles_loop: cmd[3] === "track_id:21" ? [{ id: 21, url: "file:///mnt/storage/Music/Some%20Artist/One%20Album/01%20Track.flac" }] : [] };
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
  const f = makePhoneFiles({ lms: () => lms, dir: path.join(dir, "opus"), log, ffmpeg: ff, maxBytes: 300000, musicDir: path.join(dir, "no-mount") });

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

  // A slow server (owner report: no sound away): the phone waits 15 s for an answer, and a whole track's Opus can take
  // longer than that to make — so it is sent as it is made, not once it is done.
  const slow = path.join(dir, "ffmpeg-slow");
  fs.writeFileSync(slow, `#!${process.execPath}\nprocess.stdout.write("OPUS:");\nsetTimeout(() => { process.stdout.write("REST"); }, 1500);\n`, { mode: 0o755 });
  const fs2 = makePhoneFiles({ lms: () => lms, dir: path.join(dir, "opus-slow"), log, ffmpeg: slow });
  const app2 = express();
  app2.get("/file", (req, res) => fs2.serve(req, res, String(req.query.id), req.query.q));
  const srv2 = await new Promise((r) => { const s = app2.listen(0, "127.0.0.1", () => r(s)); });
  const t0 = Date.now();
  let firstAt = 0;
  const live = await new Promise((resolve) => http.get({ host: "127.0.0.1", port: srv2.address().port, path: "/file?id=13&q=opus" }, (res) => {
    const parts = []; res.on("data", (d) => { if (!firstAt) firstAt = Date.now() - t0; parts.push(d); });
    res.on("end", () => resolve({ status: res.statusCode, ms: Date.now() - t0, body: Buffer.concat(parts).toString() }));
  }));
  ok("q=opus while it is made: the first of it at once, not after the whole track", live.status === 200 && firstAt < 1000 && live.ms >= 1400);
  ok("…and all of it, to its end", live.body === "OPUS:REST");
  const after = await new Promise((resolve) => http.get({ host: "127.0.0.1", port: srv2.address().port, path: "/file?id=13&q=opus" }, (res) => {
    const parts = []; res.on("data", (d) => parts.push(d)); res.on("end", () => resolve({ len: res.headers["content-length"], body: Buffer.concat(parts).toString() }));
  }));
  ok("…kept once made: the next ask is the finished file, its size known", after.body === "OPUS:REST" && after.len === "9");
  srv2.close();

  // ---- where the file comes from (owner report: LMS's download never answered, and every fetch ahead hung on it)
  const mount = path.join(dir, "mount");
  fs.mkdirSync(path.join(mount, "Music", "Some Artist", "One Album"), { recursive: true });
  const onDisk = crypto.randomBytes(70000);
  fs.writeFileSync(path.join(mount, "Music", "Some Artist", "One Album", "01 Track.flac"), onDisk);
  let t = 1000;
  const g = makePhoneFiles({ lms: () => lms, dir: path.join(dir, "opus-g"), log, ffmpeg: ff, musicDir: mount, headMs: 300, downMs: 60000, now: () => t });
  const app4 = express();
  app4.get("/file", (req, res) => g.serve(req, res, String(req.query.id), req.query.q));
  const s4 = await new Promise((r) => { const s = app4.listen(0, "127.0.0.1", () => r(s)); });
  const get4 = (p, headers = {}) => new Promise((resolve) => http.get({ host: "127.0.0.1", port: s4.address().port, path: p, headers }, (res) => {
    const parts = []; res.on("data", (d) => parts.push(d)); res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(parts) }));
  }));
  downloads = 0;
  const mounted = await get4("/file?id=21");
  ok("the music folder mounted: the track's own file, found by the tail of LMS's path, LMS's download not asked", mounted.status === 200 && mounted.body.equals(onDisk) && downloads === 0);
  const ranged = await get4("/file?id=21", { Range: "bytes=100-" });
  ok("…with Range", ranged.status === 206 && ranged.body.equals(onDisk.subarray(100)));
  const h0 = Date.now();
  const hung = await get4("/file?id=99");
  ok("LMS's download that never answers: given up on in moments (504), not hung on", hung.status === 504 && Date.now() - h0 < 2000);
  downloads = 0;
  const t1 = Date.now();
  const again = await get4("/file?id=98");
  ok("…and not asked again for a while: 503 at once", again.status === 503 && downloads === 0 && Date.now() - t1 < 500);
  t += 61000;
  ok("…until that while is over", (await get4("/file?id=13")).status === 200);
  s4.close();

  srv.close(); lmsHttp.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} phone file tests passed.`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
