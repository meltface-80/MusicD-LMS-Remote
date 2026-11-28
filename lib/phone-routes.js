// @ts-check
"use strict";
/*
 * phone-routes.js — the phones' own players (lib/phoneplayers.js) for Mallard away from home:
 *   POST /api/phone/hello  {device_id, name}  → {player_id, name}: its LMS player, started if it isn't
 *   GET  /api/phone/stream?player_id=&q=opus|flac → the player's sound, as one continuous Ogg stream
 *   POST /api/phone/bye    {player_id}        → its player stopped (the phone is home again)
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
  const phones = makePhonePlayers({ lmsHost: () => { const l = deps.lms(); return l && l.cfg ? l.cfg.host : null; }, log: deps.log });
  process.once("exit", () => phones.stopAll());

  app.post("/api/phone/hello", (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    const b = req.body || {};
    const deviceId = String(b.device_id || "").trim();
    if (!/^[\w.-]{4,80}$/.test(deviceId)) return res.status(400).json({ error: "device_id required" });
    const out = phones.hello({ deviceId, name: b.name, ip: req.viaTailnet || null });
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
