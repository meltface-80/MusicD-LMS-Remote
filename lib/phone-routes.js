// @ts-check
"use strict";
/*
 * phone-routes.js — the phones' own players ("This phone"; lib/phoneplayers.js, lib/relayplayer.js), as Mandarin's:
 *   POST /api/phone/hello  {device_id, name, max_rate} → {player_id, name}: its LMS player, started if it isn't
 *   POST /api/phone/bye    {player_id}        → its player stopped (a room chosen, or home from away)
 *   GET  /api/phone/events?player_id=         → the player's commands for the phone (Server-Sent Events)
 *   POST /api/phone/report {player_id, ev, seq, ms, playing} → what the phone plays (started / pos / drained / failed)
 *   GET  /api/phone/track?player_id=&seq=[&q=opus] → one track: the file's own bytes as LMS sent them (bit-perfect), or
 *        its Opus 256 kbps (away); Range for a resumed read
 * and, away, the zone list: a request that came in over the tailnet from a phone that said hello sees ONLY that phone's
 * player, named "This phone" (Mandarin's rule: away from home, only this phone plays). At home the phones' players that
 * no phone is using any more are left out.
 */
const { makePhonePlayers, isPhonePlayer, macFor } = require("./phoneplayers");

const ID_RE = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/i;

/**
 * @param {any} app
 * @param {{ lms: () => any, log: any, notConnected: (res: any) => any }} deps
 */
function mountPhones(app, deps) {
  const phones = makePhonePlayers({ lmsHost: () => { const l = deps.lms(); return l && l.cfg ? l.cfg.host : null; }, log: deps.log, playerUp });

  /**
   * A phone's player, once LMS knows it: FIXED AT 100% (Mandarin's rule, owner request: its volume is the phone's own —
   * the buttons, the lock screen, a USB DAC's level). `digitalVolumeControl 0` is LMS's "output level fixed at 100%",
   * and the level is set to 100 so every page shows it. No direct streaming either, so every track comes through LMS
   * and so through the player. @param {string} id
   */
  function playerUp(id) {
    const l = deps.lms();
    if (!l || !l.setPlayerPref || !l.setVolume) return;
    Promise.all([l.setPlayerPref(id, "digitalVolumeControl", 0), l.setVolume(id, 100), l.setPlayerPref(id, "mp3StreamingMethod", 1)])
      .then(() => deps.log.info(`phone player ${id}: fixed at 100%, no direct streaming`))
      .catch((/** @type {Error} */ e) => deps.log.warn(`phone player ${id}: couldn't set its prefs:`, e.message));
  }
  process.once("exit", () => phones.stopAll());

  app.post("/api/phone/hello", (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    const b = req.body || {};
    const deviceId = String(b.device_id || "").trim();
    if (!/^[\w.-]{4,80}$/.test(deviceId)) return res.status(400).json({ error: "device_id required" });
    const out = phones.hello({ deviceId, name: b.name, ip: req.viaTailnet || null, maxRate: b.max_rate });
    res.json({ ...out, away: !!req.viaTailnet });
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
    const q = req.query.q === "opus" ? "opus" : undefined;
    const it = relay && Number.isInteger(seq) ? relay.item(seq, q) : null;
    if (!relay || !it) return res.status(404).json({ error: "No such track" });
    const range = /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range || ""));
    const head = { "Content-Type": it.mime, "Cache-Control": "no-store", "Accept-Ranges": "bytes", "X-Content-Type-Options": "nosniff" };
    // "bytes=0-" asks for the whole track, which a plain 200 gives at once, while it is still arriving.
    if (range && !(range[1] === "0" && !range[2] && !it.done)) {
      // A resumed read: answered once the track is all here (LMS sends a local file at full speed), so its size is known.
      if (!(await relay.whole(seq, 60000, q))) return res.status(503).json({ error: "The track isn't all here" });
      const size = (relay.item(seq, q) || { size: 0 }).size;
      const from = Number(range[1]); const to = range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
      if (from >= size || to < from) return res.status(416).set("Content-Range", `bytes */${size}`).end();
      res.writeHead(206, { ...head, "Content-Range": `bytes ${from}-${to}/${size}`, "Content-Length": String(to - from + 1) });
      await relay.pipe(seq, from, to, res, q);
      return res.end();
    }
    res.writeHead(200, it.done ? { ...head, "Content-Length": String(it.size) } : head);
    await relay.pipe(seq, 0, Infinity, res, q);
    res.end();
  });

  app.get("/api/phone/players", (/** @type {any} */ req, /** @type {any} */ res) => res.json({ players: phones.list() }));

  // The zone list, filtered: away (over the tailnet) only this phone's player; at home no phone player nobody uses, and —
  // for a Mallard that names itself (X-Mallard-Device) — its own player as "This phone" among the rooms, running or not
  // (owner request: the phone zone on the same network as Lyrion too, as Mandarin's). Choosing it says hello.
  app.get("/api/zones", (/** @type {any} */ req, /** @type {any} */ res, /** @type {any} */ next) => {
    const mine = phones.playerFor(req.viaTailnet);
    const dev = String(req.get("x-mallard-device") || "").trim();
    const ownHere = !mine && /^[\w.-]{4,80}$/.test(dev) ? macFor(dev) : null;
    const used = new Set(phones.list().map((p) => p.player_id));
    const json = res.json.bind(res);
    res.json = (/** @type {any} */ body) => {
      if (!body || !Array.isArray(body.zones)) return json(body);
      if (mine) {
        const z = body.zones.find((x) => x.zone_id === mine);
        const own = z ? { ...z, display_name: "This phone" } : { zone_id: mine, display_name: "This phone", state: "stopped", outputs: [{ output_id: mine, display_name: "This phone" }] };
        return json({ ...body, zones: [own], away: true });
      }
      const zones = body.zones.filter((x) => !isPhonePlayer(x.zone_id) || (used.has(x.zone_id) && x.zone_id !== ownHere));
      if (ownHere) {
        const z = body.zones.find((x) => x.zone_id === ownHere);
        // Last, so it is never the room a screen falls back to.
        zones.push(z ? { ...z, display_name: "This phone" } : { zone_id: ownHere, display_name: "This phone", state: "stopped", outputs: [{ output_id: ownHere, display_name: "This phone" }] });
      }
      return json({ ...body, zones });
    };
    next();
  });

  return phones;
}

module.exports = { mountPhones };
