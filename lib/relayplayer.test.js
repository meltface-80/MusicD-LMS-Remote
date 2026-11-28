"use strict";
// The bit-perfect relay (lib/relayplayer.js, lib/slimproto.js, its routes in lib/phone-routes.js) against a stand-in
// LMS: a Slimproto server that sends strm/audg/setd as Squeezebox.pm packs them and reads STAT as Slimproto.pm unpacks
// it, and an HTTP server that sends a track's bytes as stream.mp3 does. Pinned: the HELO and its format order, the
// bytes reaching the phone UNTOUCHED, the ordering trap (no DSCO/STMd before the first STMs), the queued track's
// DSCO/STMd at once, pause/resume/flush/stop, the heartbeat's echo, the volume for the DAC, and Range.
const net = require("net");
const http = require("http");
const crypto = require("crypto");
const express = require("express");
const sp = require("./slimproto");
const { makeRelayPlayer } = require("./relayplayer");
const { mountPhones } = require("./phone-routes");
const { macFor } = require("./phoneplayers");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const log = { info() {}, warn() {}, debug() {} };
// A step that never answers is a failure, not a hang.
setTimeout(() => { console.log("  [FAIL] timed out"); process.exit(1); }, 30000);

/** A strm frame as LMS packs it (pack 'aaaaaaaCCCaCCCNnN' + request). */
function strm(cmd, o = {}) {
  const p = Buffer.alloc(24);
  p.write(cmd, 0); p.write(String(o.autostart ?? 1), 1); p.write(o.format || "f", 2); p.write("????", 3);
  p[7] = o.threshold ?? 1; p.write("0", 10);
  p.writeUInt32BE(o.gain || 0, 14); p.writeUInt16BE(o.port || 0, 18); p.writeUInt32BE(0, 20);
  const body = Buffer.concat([p, Buffer.from(o.request || "", "latin1")]);
  const head = Buffer.alloc(6); head.writeUInt16BE(body.length + 4, 0); head.write("strm", 2);
  return Buffer.concat([head, body]);
}
function serverFrame(op, payload) { const h = Buffer.alloc(6); h.writeUInt16BE(payload.length + 4, 0); h.write(op, 2); return Buffer.concat([h, payload]); }

const get2 = (port, p) => new Promise((resolve) => http.get({ host: "127.0.0.1", port, path: p }, (res) => { const parts = []; res.on("data", (d) => parts.push(d)); res.on("end", () => resolve(Buffer.concat(parts))); }));

(async () => {
  // ---- the stand-in LMS: slimproto, and the track's bytes over HTTP
  const track1 = crypto.randomBytes(300000), track2 = crypto.randomBytes(120000);
  const tracks = { "1": track1, "2": track2 };
  const requests = [];
  const web = http.createServer((req, res) => {
    requests.push(req.url);
    const which = new URL(req.url, "http://x").searchParams.get("t");
    if (which === "slow") { res.writeHead(200, { "Content-Type": "audio/flac" }); res.write(track2.subarray(0, 10000)); setTimeout(() => res.end(track2.subarray(10000)), 1500); return; }
    const t = tracks[which];
    res.writeHead(200, { "Content-Type": "audio/flac" });
    let i = 0;   // in pieces, as a stream arrives
    (function more() { if (i >= t.length) return res.end(); res.write(t.subarray(i, i + 50000)); i += 50000; setTimeout(more, 20); })();
  });
  await new Promise((r) => web.listen(0, "127.0.0.1", r));
  const webPort = web.address().port;

  let conn = null; const events = []; let helo = null; let stats = [];
  const lms = net.createServer((c) => {
    conn = c; let buf = Buffer.alloc(0);
    c.on("data", (d) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= 8) {
        const op = buf.toString("latin1", 0, 4), len = buf.readUInt32BE(4);
        if (buf.length < 8 + len) break;
        const p = buf.subarray(8, 8 + len); buf = buf.subarray(8 + len);
        if (op === "HELO") helo = p;
        else if (op === "STAT") { const e = p.toString("latin1", 0, 4); events.push(e); stats.push({ e, ms: p.readUInt32BE(43), ts: p.readUInt32BE(47) }); }
        else events.push(op);
      }
    });
  });
  await new Promise((r) => lms.listen(0, "127.0.0.1", r));

  const told = [];
  const mac = macFor("relay-test");
  const relay = makeRelayPlayer({ host: "127.0.0.1", port: lms.address().port, mac, name: "Pixel 8", maxRate: 96000, log, send: (e) => told.push(e) });
  relay.start();
  await wait(200);

  // ---- HELO, as LMS's _hello_handler reads it
  ok("HELO: device id 12 (SqueezePlay), revision 0", helo && helo[0] === 12 && helo[1] === 0);
  ok("…the phone's MAC", helo && helo.subarray(2, 8).equals(sp.macBytes(mac)));
  const caps = helo ? helo.toString("latin1", 36) : "";
  ok("…the DAC's top rate, digital out (so digital volume may be off)", /MaxSampleRate=96000/.test(caps) && /HasDigitalOut=1/.test(caps));
  ok("…formats in the order that makes each its own passthrough (flc after wav and alc)", /,wav,alc,ogg,ops,flc,mp3,aif,pcm$/.test(caps));

  // ---- a track that starts playback
  const req1 = `GET /stream.mp3?player=${mac}&t=1 HTTP/1.0\r\n\r\n`;
  conn.write(strm("s", { port: webPort, request: req1 }));
  await wait(600);
  ok("strm s: STMf, then STMc, then RESP (the response headers)", events.join(",").startsWith("STMf,STMc,RESP"));
  ok("…the request sent to LMS as it came, to the strm's port", requests[0] === `/stream.mp3?player=${mac}&t=1`);
  ok("…the phone told: a fresh first track, to play", told.some((e) => e.cmd === "add" && e.seq === 1 && e.fresh === true && e.play === true && e.mime === "audio/flac"));
  ok("THE TRAP: no DSCO or STMd before the phone has started it", !events.includes("DSCO") && !events.includes("STMd"));

  // ---- the phone fetches it through the route: the very bytes LMS sent
  const app = express(); app.use(express.json());
  const phones = mountPhones(app, { lms: () => ({ cfg: { host: "127.0.0.1" } }), log, notConnected: (res) => res.status(503).end() });
  const routesSrv = await new Promise((r) => { const s = app.listen(0, "127.0.0.1", () => r(s)); });
  const rp = routesSrv.address().port;
  // The route serves the phones' relays; here this one stands in for a phone's.
  phones.relayOf = () => relay;
  const get = (p, headers = {}) => new Promise((resolve) => http.get({ host: "127.0.0.1", port: rp, path: p, headers }, (res) => {
    const parts = []; res.on("data", (d) => parts.push(d)); res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
  }));
  const whole = await get(`/api/phone/track?player_id=${mac}&seq=1`);
  ok("the track reaches the phone UNTOUCHED (byte for byte)", whole.status === 200 && whole.body.equals(track1));
  ok("…as its own type", whole.headers["content-type"] === "audio/flac");
  const part = await get(`/api/phone/track?player_id=${mac}&seq=1`, { Range: "bytes=1000-" });
  ok("a resumed read (Range): 206 and the rest, exactly", part.status === 206 && part.body.equals(track1.subarray(1000)) && part.headers["content-range"] === `bytes 1000-${track1.length - 1}/${track1.length}`);
  const zero = await get(`/api/phone/track?player_id=${mac}&seq=1`, { Range: "bytes=0-" });
  ok("bytes=0- on a whole track: 206, all of it", zero.status === 206 && zero.body.equals(track1));
    ok("an unknown track: 404", (await get(`/api/phone/track?player_id=${mac}&seq=9`)).status === 404);

  // ---- the phone starts it: STMs, then the held DSCO and STMd, in that order
  events.length = 0;
  relay.phoneEvent({ ev: "started", seq: 1, ms: 0 });
  await wait(100);
  ok("started: STMs, then the held DSCO, then STMd", events.join(",") === "STMs,DSCO,STMd");

  // ---- the next track, queued behind the one playing: its DSCO/STMd at once (LMS then streams no further ahead)
  events.length = 0;
  conn.write(strm("s", { port: webPort, request: `GET /stream.mp3?player=${mac}&t=2 HTTP/1.0\r\n\r\n` }));
  await wait(500);
  ok("the next track, queued: added (not fresh) and its DSCO/STMd sent once it is all read", told.some((e) => e.cmd === "add" && e.seq === 2 && e.fresh === false) && events.includes("DSCO") && events.indexOf("STMd") > events.indexOf("DSCO"));

  // ---- the position: the phone's, carried in STAT
  relay.phoneEvent({ ev: "pos", seq: 1, ms: 61000, playing: true });
  stats = [];
  conn.write(strm("t", { gain: 0xdeadbeef }));
  await wait(100);
  const t = stats.find((s) => s.e === "STMt");
  ok("strm t: STMt echoing the server's timestamp", t && t.ts === 0xdeadbeef);
  ok("…with the phone's position in the track", t && t.ms >= 61000 && t.ms < 62500);

  // ---- pause, resume
  told.length = 0; events.length = 0;
  conn.write(strm("p")); await wait(80);
  conn.write(strm("u")); await wait(80);
  ok("pause and resume: told to the phone, STMp and STMr to LMS", told.map((e) => e.cmd).join(",") === "pause,resume" && events.join(",") === "STMp,STMr");

  // ---- flush: the queued track, not yet started, let go
  told.length = 0; events.length = 0;
  conn.write(strm("f")); await wait(80);
  ok("strm f: the queued track dropped on the phone, STMf", told.some((e) => e.cmd === "drop" && e.seq === 2) && events.join(",") === "STMf");
  ok("…and no longer served", relay.item(2) === null && relay.item(1) !== null);

  // ---- LMS's volume: the player is fixed at 100%, its volume the phone's own — nothing reaches the phone
  told.length = 0;
  const audg = Buffer.alloc(18); audg.writeUInt32BE(32768, 10); audg.writeUInt32BE(32768, 14);
  conn.write(serverFrame("audg", audg)); await wait(80);
  ok("audg: LMS's volume goes nowhere (fixed at 100%; the phone's volume is its own)", told.length === 0);

  // ---- the player's name
  events.length = 0;
  conn.write(serverFrame("setd", Buffer.from([0]))); await wait(80);
  ok("setd 0: SETD with the phone's name", events.includes("SETD"));

  // ---- stop: everything let go
  told.length = 0; events.length = 0;
  conn.write(strm("q")); await wait(100);
  ok("strm q: the phone stopped, STMf", told.some((e) => e.cmd === "stop") && events.join(",") === "STMf");
  ok("…and the spools gone", relay.item(1) === null);

  // ---- the end of the playlist
  events.length = 0;
  relay.phoneEvent({ ev: "drained" }); await wait(50);
  ok("drained: STMu (LMS stops at the end)", events.join(",") === "STMu");

  // ---- a track still arriving, asked for as bytes=0-: answered at once (200), not after it is all here
  conn.write(strm("s", { port: webPort, request: `GET /stream.mp3?player=${mac}&t=slow HTTP/1.0\r\n\r\n` }));
  await wait(300);
  const slowSeq = told.filter((e) => e.cmd === "add").pop().seq;
  const t0 = Date.now();
  const first = await new Promise((resolve) => http.get({ host: "127.0.0.1", port: rp, path: `/api/phone/track?player_id=${mac}&seq=${slowSeq}`, headers: { Range: "bytes=0-" } },
    (res) => res.once("data", () => { resolve({ status: res.statusCode, ms: Date.now() - t0 }); res.destroy(); })));
  ok("bytes=0- on a track still arriving: 200 at once", first.status === 200 && first.ms < 800);

  relay.stop(); routesSrv.close();

  // ---- end to end through the routes: hello untouched, the event channel, a track, the phone's report
  process.env.SLIMPROTO_PORT = String(lms.address().port);
  delete require.cache[require.resolve("./relayplayer")]; delete require.cache[require.resolve("./phoneplayers")]; delete require.cache[require.resolve("./phone-routes")];
  const app2 = express(); app2.use(express.json());
  const phones2 = require("./phone-routes").mountPhones(app2, { lms: () => ({ cfg: { host: "127.0.0.1" }, setPlayerPref: async () => ({}) }), log, notConnected: (res) => res.status(503).end() });
  const s2 = await new Promise((r) => { const x = app2.listen(0, "127.0.0.1", () => r(x)); });
  const p2 = s2.address().port;
  const post = (p, body) => new Promise((resolve) => { const r = http.request({ host: "127.0.0.1", port: p2, path: p, method: "POST", headers: { "Content-Type": "application/json" } },
    (res) => { let t = ""; res.on("data", (d) => t += d); res.on("end", () => resolve(JSON.parse(t))); }); r.end(JSON.stringify(body)); });
  conn = null; helo = null;
  const hi = await post("/api/phone/hello", { device_id: "relay-e2e", name: "Pixel 9", mode: "exact", max_rate: 192000 });
  await wait(300);
  ok("e2e: hello untouched → the relay connects to LMS as that phone", hi.mode === "exact" && helo && helo.subarray(2, 8).equals(sp.macBytes(hi.player_id)));
  const sse = [];
  const evReq = http.get({ host: "127.0.0.1", port: p2, path: "/api/phone/events?player_id=" + hi.player_id }, (res) => {
    ok("e2e: the event channel is Server-Sent Events", res.headers["content-type"] === "text/event-stream");
    res.on("data", (d) => { for (const m of String(d).matchAll(/^data: (.*)$/gm)) sse.push(JSON.parse(m[1])); });
  });
  await wait(200);
  events.length = 0;
  conn.write(strm("s", { port: webPort, request: `GET /stream.mp3?player=${hi.player_id}&t=2 HTTP/1.0\r\n\r\n` }));
  await wait(500);
  const add = sse.find((e) => e.cmd === "add");
  ok("e2e: the phone hears of the track on its channel", add && add.fresh === true);
  const got = await get2(p2, `/api/phone/track?player_id=${hi.player_id}&seq=${add ? add.seq : 0}`);
  ok("e2e: and fetches it untouched", got.equals(track2));
  const rep = await post("/api/phone/report", { player_id: hi.player_id, ev: "started", seq: add ? add.seq : 0, ms: 0, playing: true });
  await wait(100);
  ok("e2e: its report reaches LMS as STMs, then the held DSCO/STMd", rep.ok === true && events.slice(-3).join(",") === "STMs,DSCO,STMd");
  evReq.destroy(); phones2.stopAll(); s2.close();
  web.close(); lms.close();
  await wait(100);
  console.log(`\n${pass}/${pass + fail} relay tests passed.`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
