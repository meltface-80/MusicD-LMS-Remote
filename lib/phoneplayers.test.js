"use strict";
// The phones' own players (lib/phoneplayers.js, lib/phone-routes.js) against stand-ins: a "squeezelite" that writes PCM
// as fast as it is read (as the real one's stdout output does) and records its arguments, and an "ffmpeg" that passes
// its input through. Pinned: squeezelite's flags, real-time pacing, goodbye, and the zone list away from home.
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const express = require("express");
const { makePhonePlayers, macFor, isPhonePlayer, ffmpegArgs, BYTES_PER_SEC } = require("./phoneplayers");
const { mountPhones } = require("./phone-routes");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phoneplayers-"));
const argsFile = path.join(dir, "args.json");
const sq = path.join(dir, "squeezelite"), ff = path.join(dir, "ffmpeg");
fs.writeFileSync(sq, `#!${process.execPath}\nrequire("fs").writeFileSync(${JSON.stringify(argsFile)}, JSON.stringify(process.argv.slice(2)));\n` +
  `const b = Buffer.alloc(65536, 1); (function w() { while (process.stdout.write(b)); process.stdout.once("drain", w); })();\n`, { mode: 0o755 });
fs.writeFileSync(ff, `#!${process.execPath}\nprocess.stdout.write("OggS"); process.stdin.pipe(process.stdout);\n`, { mode: 0o755 });
const log = { info() {}, warn() {}, debug() {} };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  ok("a phone's MAC is stable and locally administered", macFor("abc123") === macFor("abc123") && /^02:6d(:[0-9a-f]{2}){4}$/.test(macFor("abc123")) && macFor("abc123") !== macFor("abd123"));
  ok("…and known for a phone's player", isPhonePlayer(macFor("x1234")) && !isPhonePlayer("00:04:20:12:34:56"));
  const opus = ffmpegArgs("opus").join(" "), flac = ffmpegArgs("flac").join(" ");
  ok("Opus 256 kbps from s32le 48 kHz stereo, in Ogg", /-f s32le -ar 48000 -ac 2 -i pipe:0/.test(opus) && /libopus -b:a 256k/.test(opus) && /-f ogg pipe:1$/.test(opus));
  ok("lossless: FLAC 24-bit, in Ogg", /-c:a flac -sample_fmt s32 -bits_per_raw_sample 24/.test(flac) && /-f ogg pipe:1$/.test(flac));

  const phones = makePhonePlayers({ lmsHost: () => "lms.local", log, bin: sq, ffmpeg: ff });
  const h = phones.hello({ deviceId: "pixel-0001", name: "Pixel 8", ip: "100.70.0.9" });
  ok("hello: the phone's player, by its MAC", h.player_id === macFor("pixel-0001") && h.name === "Pixel 8");
  await wait(600);
  const args = JSON.parse(fs.readFileSync(argsFile, "utf8")).join(" ");
  ok("squeezelite to stdout, 32-bit, resampled to 48 kHz, hi-res allowed", /-o - -a 32 -r 48000 -R -Z 192000/.test(args));
  ok("…named, with its MAC, at LMS's slimproto port", args.includes("-n Pixel 8") && args.includes("-m " + h.player_id) && args.includes("-s lms.local:3483"));

  // Its stream, read for a while: no faster than real time (plus the moment's lead), though squeezelite writes flat out.
  const srv = http.createServer((req, res) => { if (!phones.stream(h.player_id, "opus", res)) { res.statusCode = 404; res.end(); } });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  const port = /** @type {any} */ (srv.address()).port;
  let got = 0, first = "";
  const t0 = Date.now();
  const req = http.get({ host: "127.0.0.1", port, path: "/" }, (res) => {
    ok("the stream is Ogg", res.headers["content-type"] === "audio/ogg");
    res.on("data", (d) => { if (!first) first = String(d.subarray(0, 4)); got += d.length; });
  });
  await wait(2000);
  const secs = (Date.now() - t0) / 1000;
  ok("the stream starts as the encoder's output", first === "OggS");
  ok(`paced to real time (${(got / BYTES_PER_SEC).toFixed(2)} s of sound in ${secs.toFixed(2)} s)`, got > 0.5 * BYTES_PER_SEC && got < (secs + 1.6) * BYTES_PER_SEC);
  ok("the listener is counted", phones.list()[0].listeners === 1);
  req.destroy();
  await wait(300);
  ok("…and let go when the phone hangs up", phones.list()[0].listeners === 0);
  ok("a stream for a phone that never said hello is refused", phones.stream("02:6d:00:00:00:00", "opus", /** @type {any} */ ({})) === false);
  ok("goodbye stops its player", phones.bye(h.player_id) && phones.list().length === 0);
  srv.close();

  // The zone list over the routes: away (over the tailnet, from the phone that said hello) only its own player.
  const app = express();
  app.use(express.json());
  app.use((req, res, next) => { req.viaTailnet = req.headers["x-test-tailnet"] || undefined; next(); });
  const routes = mountPhones(app, { lms: () => ({ cfg: { host: "lms.local" } }), log, notConnected: (res) => res.status(503).end() });
  const mine = macFor("pixel-0002");
  app.get("/api/zones", (req, res) => res.json({ zones: [{ zone_id: "00:04:20:aa:bb:cc", display_name: "Kitchen" }, { zone_id: mine, display_name: "Pixel 8" }, { zone_id: macFor("old"), display_name: "Old phone" }] }));
  const s2 = app.listen(0, "127.0.0.1");
  await new Promise((r) => s2.once("listening", r));
  const p2 = /** @type {any} */ (s2.address()).port;
  const call = (method, p, body, headers = {}) => new Promise((resolve) => {
    const r = http.request({ host: "127.0.0.1", port: p2, path: p, method, headers: { "Content-Type": "application/json", ...headers } }, (res) => {
      let t = ""; res.on("data", (d) => t += d); res.on("end", () => resolve({ status: res.statusCode, body: t ? JSON.parse(t) : null }));
    });
    r.end(body ? JSON.stringify(body) : undefined);
  });
  const hi = await call("POST", "/api/phone/hello", { device_id: "pixel-0002", name: "Pixel 8" }, { "x-test-tailnet": "100.70.0.10" });
  ok("hello over the tailnet says away", hi.status === 200 && hi.body.player_id === mine && hi.body.away === true);
  const away = await call("GET", "/api/zones", null, { "x-test-tailnet": "100.70.0.10" });
  ok("away: only this phone's player, as This phone", away.body.zones.length === 1 && away.body.zones[0].zone_id === mine && away.body.zones[0].display_name === "This phone");
  const home = await call("GET", "/api/zones");
  ok("at home: the rooms, and a phone player only while a phone uses it", home.body.zones.map((z) => z.display_name).join() === "Kitchen,Pixel 8");
  ok("a bad hello is refused", (await call("POST", "/api/phone/hello", { device_id: "x" })).status === 400);
  routes.stopAll();
  s2.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} phone player tests passed.`);
  process.exit(fail ? 1 : 0);
})();
