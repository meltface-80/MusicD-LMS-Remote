// @ts-check
"use strict";
/*
 * phoneplayers.js — a phone as a Lyrion player of its own: "This phone", Mandarin's phone zone made an LMS player
 * (owner request, "look at Mandarin and how it does it").
 *
 * For each phone that says hello, this server runs its own Slimproto player (lib/relayplayer.js) under a MAC made from
 * the phone's id, so LMS knows it as a player like any other and keeps its settings and queue. LMS streams each track to
 * it as the file's own bytes, and the phone plays them one item per track, as Mandarin's phone does:
 *   - the file itself (bit-perfect) — at home always, and away when chosen;
 *   - away, by default, Opus 256 kbps made per track from that file (24-bit/48 kHz on the phone).
 * The phone asks for one or the other per track (q=opus); the server never resamples the original. There is no
 * continuous stream and no squeezelite: a stream resampled to 48 kHz was neither Mandarin's Opus nor lossless.
 * LMS has the player fixed at 100%: its volume is the phone's own (Mallard's ThisPhone).
 */
const crypto = require("crypto");
const { makeRelayPlayer } = require("./relayplayer");

const IDLE_MS = 15 * 60 * 1000;       // a phone with no channel open and not heard from for this long: its player is stopped

/** A stable, locally administered MAC for a phone ("02:6d:…": 02 local, 6d "m" for Mallard). @param {string} deviceId */
function macFor(deviceId) {
  const h = crypto.createHash("sha1").update("mallard-phone:" + deviceId).digest();
  return ["02", "6d", ...[...h.subarray(0, 4)].map((b) => b.toString(16).padStart(2, "0"))].join(":");
}

/** Whether an LMS player id is one of these phones'. @param {string} id */
const isPhonePlayer = (id) => /^02:6d:/i.test(String(id || ""));

/**
 * @param {{ lmsHost: () => string|null, log: any, now?: () => number, relay?: typeof makeRelayPlayer,
 *   playerUp?: (id: string) => void, identify?: (playerId: string, fresh: boolean) => Promise<string|null> }} deps
 */
function makePhonePlayers(deps) {
  const now = deps.now || Date.now;
  /** @typedef {{ id: string, name: string, ip: string|null, seen: number, maxRate: number, relay: any, events: Set<any> }} Phone */
  /** @type {Map<string, Phone>} */
  const phones = new Map();

  /** The phone's player, connected to LMS. @param {Phone} p */
  function start(p) {
    const host = deps.lmsHost();
    if (!host || p.relay) return;
    p.relay = (deps.relay || makeRelayPlayer)({ host, mac: p.id, name: p.name, maxRate: p.maxRate, log: deps.log, send: (ev) => tell(p, ev),
      identify: deps.identify ? (fresh) => /** @type {any} */ (deps.identify)(p.id, fresh) : undefined });
    p.relay.start();
    deps.log.info(`phone player "${p.name}" (${p.id}) started for LMS at ${host}`);
    // Once LMS knows it: fixed at 100%, no direct streaming (phone-routes.js).
    if (deps.playerUp) setTimeout(() => { if (p.relay && deps.playerUp) deps.playerUp(p.id); }, 1500).unref();
  }

  /** The phone's player stopped; the phone itself kept. @param {Phone} p */
  function halt(p) { if (p.relay) { p.relay.stop(); p.relay = null; } }

  /**
   * A phone said hello (at home or away): its player, started if it isn't. [maxRate] is the highest rate the phone
   * takes (its USB DAC's); a change of it says hello to LMS again with the new one. [ip] is the tailnet address it came
   * in on (away), so the zone list there is its player alone.
   * @param {{ deviceId: string, name?: string, ip?: string|null, maxRate?: number }} h
   */
  function hello(h) {
    const id = macFor(h.deviceId);
    const name = String(h.name || "").trim().slice(0, 60) || "Mallard phone";
    const maxRate = Math.max(44100, Math.min(768000, Math.floor(Number(h.maxRate) || 192000)));
    let p = phones.get(id);
    if (!p) { p = { id, name, ip: null, seen: 0, maxRate, relay: null, events: new Set() }; phones.set(id, p); }
    p.seen = now(); p.ip = h.ip || null; p.name = name;
    if (p.maxRate !== maxRate) { halt(p); p.maxRate = maxRate; }
    start(p);
    return { player_id: id, name, mode: "exact" };
  }

  /** An event for the phone's channel (every connection it has open). @param {Phone} p @param {object} ev */
  function tell(p, ev) {
    const line = "data: " + JSON.stringify(ev) + "\n\n";
    for (const r of p.events) { try { r.write(line); } catch (e) { /* gone */ } }
  }

  /** The phone gone home to a room (or gone): its player stopped; LMS forgets it soon after. @param {string} id */
  function bye(id) {
    const p = phones.get(id);
    if (!p) return false;
    halt(p);
    for (const r of [...p.events]) { try { r.end(); } catch (e) { /* gone */ } }
    phones.delete(id);
    deps.log.info(`phone player "${p.name}" stopped`);
    return true;
  }

  /**
   * The player's channel to the phone (Server-Sent Events, held open): every command for it, as it happens. False when
   * there is no such phone. @param {string} id @param {import("http").ServerResponse} res
   */
  function events(id, res) {
    const p = phones.get(id);
    if (!p || !p.relay) return false;
    p.seen = now();
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no" });
    res.write("retry: 2000\n\n");
    p.events.add(res);
    const ping = setInterval(() => { try { res.write(": ping\n\n"); } catch (e) { /* gone */ } }, 15000);
    res.on("close", () => { clearInterval(ping); p.events.delete(res); p.seen = now(); });
    return true;
  }

  /** What the phone says of its playing (relayplayer phoneEvent). @param {string} id @param {any} ev */
  function report(id, ev) {
    const p = phones.get(id);
    if (!p || !p.relay) return false;
    p.seen = now();
    p.relay.phoneEvent(ev);
    return true;
  }

  /** The player of phone [id], for the route that serves its tracks; null when there is none. @param {string} id */
  const relayOf = (id) => { const p = phones.get(id); return p && p.relay ? p.relay : null; };

  /** The phone player whose hello came from [ip] (the tailnet address a request came in on), or null. @param {string|null|undefined} ip */
  function playerFor(ip) {
    if (!ip) return null;
    for (const p of phones.values()) if (p.ip === ip) return p.id;
    return null;
  }

  /** Every so often: a phone with no channel open and not heard from in a while is let go. */
  function sweep() {
    const t = now();
    for (const p of [...phones.values()]) if (!p.events.size && t - p.seen > IDLE_MS) bye(p.id);
  }
  const sweeper = setInterval(sweep, 60000);
  if (sweeper.unref) sweeper.unref();

  function stopAll() { for (const id of [...phones.keys()]) bye(id); clearInterval(sweeper); }

  /** For the apps and the log: the phones' players. */
  const list = () => [...phones.values()].map((p) => ({ player_id: p.id, name: p.name, running: !!p.relay, listeners: p.events.size }));

  return { hello, bye, events, report, relayOf, playerFor, list, sweep, stopAll };
}

module.exports = { makePhonePlayers, macFor, isPhonePlayer };
