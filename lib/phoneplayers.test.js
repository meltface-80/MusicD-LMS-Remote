"use strict";
// The phones' own players (lib/phoneplayers.js, lib/phone-routes.js), "This phone" as Mandarin's: every phone's player
// is the server's own Slimproto player (a stand-in here; lib/relayplayer.test.js drives the real one against a stand-in
// LMS). Pinned: the player per phone and its MAC, LMS told it is fixed at 100%, hello again and a new top rate, goodbye,
// the event channel, no continuous stream any more, and the zone list away and at home ("This phone").
const http = require("http");
const express = require("express");
const { makePhonePlayers, macFor, isPhonePlayer } = require("./phoneplayers");
const { mountPhones } = require("./phone-routes");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
const log = { info() {}, warn() {}, debug() {} };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  ok("a phone's MAC is stable and locally administered", macFor("abc123") === macFor("abc123") && /^02:6d(:[0-9a-f]{2}){4}$/.test(macFor("abc123")) && macFor("abc123") !== macFor("abd123"));
  ok("…the same as Mallard's (core ThisPhone.playerId)", macFor("abc123") === "02:6d:f4:f1:26:46");
  ok("…and known for a phone's player", isPhonePlayer(macFor("x1234")) && !isPhonePlayer("00:04:20:12:34:56"));

  // ---- the player per phone (a stand-in for the relay)
  const made = [];
  let stopped = 0;
  const relay = (o) => { const r = { o, started: false, start() { r.started = true; }, stop() { stopped++; }, phoneEvent(e) { r.last = e; } }; made.push(r); return r; };
  const ups = [];
  const phones = makePhonePlayers({ lmsHost: () => "lms.local", log, relay, playerUp: (id) => ups.push(id) });
  const pid = macFor("pixel-0003");
  const h = phones.hello({ deviceId: "pixel-0003", name: "Pixel 9", maxRate: 384000 });
  ok("hello: the phone's own player, by its MAC, named, with its DAC's top rate", h.player_id === pid && made.length === 1 && made[0].started &&
    made[0].o.mac === pid && made[0].o.name === "Pixel 9" && made[0].o.maxRate === 384000 && made[0].o.host === "lms.local");
  await wait(1700);
  ok("…its LMS settings once LMS knows it", ups.join() === pid);
  phones.hello({ deviceId: "pixel-0003", name: "Pixel 9", maxRate: 384000 });
  ok("hello again: the same player kept", made.length === 1 && stopped === 0);
  phones.hello({ deviceId: "pixel-0003", name: "Pixel 9", maxRate: 96000 });
  ok("a new top rate (another DAC): hello to LMS again with it", made.length === 2 && stopped === 1 && made[1].o.maxRate === 96000);
  ok("the phone's reports reach its player", phones.report(pid, { ev: "started", seq: 1 }) && made[1].last.ev === "started");
  ok("goodbye stops its player", phones.bye(pid) && stopped === 2 && phones.list().length === 0);
  ok("…and a report after it goes nowhere", phones.report(pid, { ev: "pos", seq: 1 }) === false);
  phones.stopAll();

  // ---- the routes, and the zone list away and at home
  const app = express();
  app.use(express.json());
  app.use((req, res, next) => { req.viaTailnet = req.headers["x-test-tailnet"] || undefined; next(); });
  const lmsCalls = [];
  const lmsStub = { cfg: { host: "127.0.0.1" }, setPlayerPref: async (id, n, v) => lmsCalls.push(`${id} ${n} ${v}`), setVolume: async (id, v) => lmsCalls.push(`${id} volume ${v}`) };
  const routes = mountPhones(app, { lms: () => lmsStub, log, notConnected: (res) => res.status(503).end() });
  const mine = macFor("pixel-0002");
  app.get("/api/zones", (req, res) => res.json({ zones: [{ zone_id: "00:04:20:aa:bb:cc", display_name: "Kitchen" }, { zone_id: mine, display_name: "Pixel 8" }, { zone_id: macFor("old"), display_name: "Old phone" }] }));
  const s2 = app.listen(0, "127.0.0.1");
  await new Promise((r) => s2.once("listening", r));
  const p2 = s2.address().port;
  const call = (method, p, body, headers = {}) => new Promise((resolve) => {
    const r = http.request({ host: "127.0.0.1", port: p2, path: p, method, headers: { "Content-Type": "application/json", ...headers } }, (res) => {
      let t = ""; res.on("data", (d) => t += d); res.on("end", () => { let body; try { body = t ? JSON.parse(t) : null; } catch (e) { body = t; } resolve({ status: res.statusCode, body }); });
    });
    r.end(body ? JSON.stringify(body) : undefined);
  });
  const hi = await call("POST", "/api/phone/hello", { device_id: "pixel-0002", name: "Pixel 8" }, { "x-test-tailnet": "100.70.0.10" });
  ok("hello over the tailnet says away", hi.status === 200 && hi.body.player_id === mine && hi.body.away === true);
  await wait(1700);
  ok("the phone's player in LMS: fixed at 100% (digital volume off, level 100), no direct streaming",
    lmsCalls.sort().join() === [`${mine} digitalVolumeControl 0`, `${mine} mp3StreamingMethod 1`, `${mine} volume 100`].sort().join());
  ok("no continuous stream any more: each track is its own item", (await call("GET", "/api/phone/stream?player_id=" + mine)).status === 404);
  const away = await call("GET", "/api/zones", null, { "x-test-tailnet": "100.70.0.10" });
  ok("away: only this phone's player, as This phone", away.body.zones.length === 1 && away.body.zones[0].zone_id === mine && away.body.zones[0].display_name === "This phone");
  const home = await call("GET", "/api/zones");
  ok("at home: the rooms, and a phone player only while a phone uses it", home.body.zones.map((z) => z.display_name).join() === "Kitchen,Pixel 8");
  const named = await call("GET", "/api/zones", null, { "x-mallard-device": "pixel-0004" });
  ok("at home, a Mallard that names itself: the rooms, then its own player as This phone (not yet running)",
    named.body.zones.map((z) => z.display_name).join() === "Kitchen,Pixel 8,This phone" && named.body.zones[2].zone_id === macFor("pixel-0004"));
  const ownRunning = await call("GET", "/api/zones", null, { "x-mallard-device": "pixel-0002" });
  ok("…and once it runs, the same player once, as This phone", ownRunning.body.zones.map((z) => z.display_name).join() === "Kitchen,This phone" && ownRunning.body.zones[1].zone_id === mine);
  ok("an events channel for a phone that never said hello: 404", (await call("GET", "/api/phone/events?player_id=" + macFor("nobody"))).status === 404);
  ok("a bad hello is refused", (await call("POST", "/api/phone/hello", { device_id: "x" })).status === 400);
  routes.stopAll();
  s2.close();
  console.log(`\n${pass}/${pass + fail} phone player tests passed.`);
  process.exit(fail ? 1 : 0);
})();
