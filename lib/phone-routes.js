// @ts-check
"use strict";
/*
 * phone-routes.js — the phones' own players (lib/phoneplayers.js) for Mallard away from home:
 *   POST /api/phone/hello  {device_id, name}  → {player_id, name}: its LMS player, started if it isn't
 *   GET  /api/phone/stream?player_id=&q=opus|flac → the player's sound, as one continuous Ogg stream
 *   POST /api/phone/bye    {player_id}        → its player stopped (the phone is home again)
 * and, playing untouched (hello with mode "exact": lib/relayplayer.js, bit-perfect for a USB DAC):
 *   GET  /api/phone/events?player_id=         → the relay's commands for the phone (Server-Sent Events)
 *   POST /api/phone/report {player_id, ev, seq, ms, playing} → what the phone plays (started / pos / drained / failed)
 *   GET  /api/phone/track?player_id=&seq=      → one track's own bytes, as LMS sent them (Range for a resumed read)
 * and, away, the zone list: a request that came in over the tailnet from a phone that said hello sees ONLY that phone's
 * player, named "This phone" (Mandarin's rule: away from home, only this phone plays). At home the phones' players that
 * no phone is using any more are left out.
 */
const { makePhonePlayers, isPhonePlayer } = require("./phoneplayers");

const ID_RE = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/i;

/**
 * @param {any} app
 * @param {{ lms: () => any, log: any, notConnected: (res: any) => any }} deps
 */
function mountPhones(app, deps) {
  const phones = makePhonePlayers({ lmsHost: () => { const l = deps.lms(); return l && l.cfg ? l.cfg.host : null; }, log: deps.log, relayUp });

  /**
   * The relay's player, once LMS knows it: digital volume OFF (LMS asks it to apply no gain — `HasDigitalOut` allows
   * 0) and no direct streaming (every track comes through LMS, so through the relay). LMS's volume still reaches the
   * phone in each audg, for the DAC's own control. @param {string} id
   */
  function relayUp(id) {
    const l = deps.lms();
    if (!l) return;
    Promise.all([l.setPlayerPref(id, "digitalVolumeControl", 0), l.setPlayerPref(id, "mp3StreamingMethod", 1)])
      .then(() => deps.log.info(`phone player ${id}: digital volume off, no direct streaming`))
      .catch((/** @type {Error} */ e) => deps.log.warn(`phone player ${id}: couldn't set its prefs:`, e.message));
  }
  process.once("exit", () => phones.stopAll());

  app.post("/api/phone/hello", (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    const b = req.body || {};
    const deviceId = String(b.device_id || "").trim();
    if (!/^[\w.-]{4,80}$/.test(deviceId)) return res.status(400).json({ error: "device_id required" });
    const out = phones.hello({ deviceId, name: b.name, ip: req.viaTailnet || null, mode: b.mode, maxRate: b.max_rate });
    res.json({ ...out, away: !!req.viaTailnet });
  });

  app.get("/api/phone/stream", (/** @type {any} */ req, /** @type {any} */ res) => {
    const id = String(req.query.player_id || "");
    if (!ID_RE.test(id)) return res.status(400).json({ error: "player_id required" });
    if (!phones.stream(id, String(req.query.q || "opus"), res)) res.status(404).json({ error: "That phone hasn't said hello" });
  });

  app.post("/api/phone/bye", (/** @type {any} */ req, /** @type {any} */ res) => {
    const id = String((req.body || {}).player_id || "");
    res.json({ ok: phones.bye(id) });
  });

  app.get("/api/phone/events", (/** @type {any} */ req, /** @type {any} */ res) => {
    const id = String(req.query.player_id || "");
    if (!ID_RE.test(id)) return res.status(400).json({ error: "player_id required" });
    if (!phones.events(id, res)) res.status(404).json({ error: "That phone isn't playing untouched" });
  });

  app.post("/api/phone/report", (/** @type {any} */ req, /** @type {any} */ res) => {
    const b = req.body || {};
    const id = String(b.player_id || "");
    if (!ID_RE.test(id) || !/^(started|pos|drained|failed)$/.test(String(b.ev))) return res.status(400).json({ error: "player_id and ev required" });
    const ev = { ev: String(b.ev), seq: Number.isInteger(b.seq) ? b.seq : undefined, ms: Number(b.ms) || 0, playing: !!b.playing };
    res.json({ ok: phones.report(id, ev) });
  });

  app.get("/api/phone/track", async (/** @type {any} */ req, /** @type {any} */ res) => {
    const id = String(req.query.player_id || "");
    const seq = parseInt(String(req.query.seq || ""), 10);
    const relay = ID_RE.test(id) ? phones.relayOf(id) : null;
    const it = relay && Number.isInteger(seq) ? relay.item(seq) : null;
    if (!relay || !it) return res.status(404).json({ error: "No such track" });
    const range = /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range || ""));
    const head = { "Content-Type": it.mime, "Cache-Control": "no-store", "Accept-Ranges": "bytes", "X-Content-Type-Options": "nosniff" };
    if (range) {
      // A resumed read: answered once the track is all here (LMS sends a local file at full speed), so its size is known.
      if (!(await relay.whole(seq, 60000))) return res.status(503).json({ error: "The track isn't all here" });
      const size = (relay.item(seq) || { size: 0 }).size;
      const from = Number(range[1]); const to = range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
      if (from >= size || to < from) return res.status(416).set("Content-Range", `bytes */${size}`).end();
      res.writeHead(206, { ...head, "Content-Range": `bytes ${from}-${to}/${size}`, "Content-Length": String(to - from + 1) });
      await relay.pipe(seq, from, to, res);
      return res.end();
    }
    res.writeHead(200, it.done ? { ...head, "Content-Length": String(it.size) } : head);
    await relay.pipe(seq, 0, Infinity, res);
    res.end();
  });

  app.get("/api/phone/players", (/** @type {any} */ req, /** @type {any} */ res) => res.json({ players: phones.list() }));

  // The zone list, filtered: away (over the tailnet) only this phone's player; at home no phone player nobody uses.
  app.get("/api/zones", (/** @type {any} */ req, /** @type {any} */ res, /** @type {any} */ next) => {
    const mine = phones.playerFor(req.viaTailnet);
    const used = new Set(phones.list().map((p) => p.player_id));
    const json = res.json.bind(res);
    res.json = (/** @type {any} */ body) => {
      if (!body || !Array.isArray(body.zones)) return json(body);
      if (mine) {
        const z = body.zones.find((x) => x.zone_id === mine);
        const own = z ? { ...z, display_name: "This phone" } : { zone_id: mine, display_name: "This phone", state: "stopped", outputs: [{ output_id: mine, display_name: "This phone" }] };
        return json({ ...body, zones: [own], away: true });
      }
      return json({ ...body, zones: body.zones.filter((x) => !isPhonePlayer(x.zone_id) || used.has(x.zone_id)) });
    };
    next();
  });

  return phones;
}

module.exports = { mountPhones };
