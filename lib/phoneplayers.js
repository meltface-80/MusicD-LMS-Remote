// @ts-check
"use strict";
/*
 * phoneplayers.js — a phone away from home as a Lyrion player of its own (owner request: "once off the same subnet …
 * only shows and instantly uses the phone zone"; Mandarin's phone zone, made an LMS player).
 *
 * For each phone that says hello, this server runs SQUEEZELITE — the standard software Squeezebox — as a player LMS
 * knows like any other (its MAC is derived from the phone's id, so LMS keeps that player's own settings). LMS streams to
 * it losslessly and drives it as it drives any player, so every LMS player setting applies: volume, ReplayGain levelling
 * (LMS sends the gain in `strm`; squeezelite applies it), crossfade, Don't Stop The Music. Squeezelite writes what it
 * plays to its stdout as raw PCM — `-o - -a 32 -r 48000 -R`: 32-bit little-endian stereo, resampled to a fixed 48 kHz
 * because a raw stream can't say its rate changed — and this server hands that to ffmpeg, which makes the stream the
 * phone plays over Tailscale: Opus 256 kbps (48 kHz) in Ogg, or lossless FLAC (24-bit/48 kHz) in Ogg.
 *
 * Squeezelite's stdout output has NO CLOCK: it writes as fast as it is read (silence when paused), so it is read here
 * at real time (PACE: 48000 frames × 8 bytes a second) and ALWAYS read, listener or not — a pipe left full stalls it,
 * and one closed kills it. One continuous stream per listener, through pauses and track changes, so the phone's player
 * never sees an end.
 *
 * "Untouched" (bit-perfect, for a USB DAC): no squeezelite and no encoder. The phone's player is then this server's own
 * Slimproto player (lib/relayplayer.js), which passes each track on to the phone as the file's own bytes; the phone
 * plays them as a playlist and reports back through its event channel. Same MAC, so LMS keeps the player either way.
 */
const { spawn } = require("child_process");
const crypto = require("crypto");
const { makeRelayPlayer } = require("./relayplayer");

const RATE = 48000;
const BYTES_PER_SEC = RATE * 2 * 4;   // s32le stereo
const AHEAD_S = 1.0;                  // how far the read may run ahead of the clock (a phone connecting gets a moment's audio at once)
const IDLE_MS = 15 * 60 * 1000;       // a phone neither listening nor heard from for this long: its player is stopped
const SLIMPROTO_PORT = Number(process.env.SLIMPROTO_PORT) || 3483;   // LMS's Slimproto port (env for an LMS moved off it)

/** A stable, locally administered MAC for a phone ("02:6d:…": 02 local, 6d "m" for Mallard). @param {string} deviceId */
function macFor(deviceId) {
  const h = crypto.createHash("sha1").update("mallard-phone:" + deviceId).digest();
  return ["02", "6d", ...[...h.subarray(0, 4)].map((b) => b.toString(16).padStart(2, "0"))].join(":");
}

/** Whether an LMS player id is one of these phones'. @param {string} id */
const isPhonePlayer = (id) => /^02:6d:/i.test(String(id || ""));

/**
 * ffmpeg's arguments for a listener: [q] "opus" (256 kbps, 48 kHz, encoded from 32-bit FLOAT — never cut to 16-bit on the
 * way in; Opus itself has no bit depth, and Mallard decodes it to float with its own libopus) or "flac" (24-bit/48 kHz),
 * both in Ogg. @param {string} q
 */
function ffmpegArgs(q) {
  const input = ["-hide_banner", "-loglevel", "error", "-f", "s32le", "-ar", String(RATE), "-ac", "2", "-i", "pipe:0"];
  const codec = q === "flac"
    ? ["-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-compression_level", "5"]
    : ["-c:a", "libopus", "-sample_fmt", "flt", "-b:a", "256k", "-vbr", "on", "-application", "audio", "-frame_duration", "20"];
  return [...input, ...codec, "-page_duration", "100000", "-flush_packets", "1", "-f", "ogg", "pipe:1"];
}

/**
 * @param {{ lmsHost: () => string|null, log: any, bin?: string, ffmpeg?: string, spawn?: typeof spawn, now?: () => number,
 *   relay?: typeof makeRelayPlayer, relayUp?: (id: string) => void }} deps
 */
function makePhonePlayers(deps) {
  const bin = deps.bin || process.env.SQUEEZELITE_BIN || "squeezelite";
  const ffmpeg = deps.ffmpeg || process.env.FFMPEG_BIN || "ffmpeg";
  const run = deps.spawn || spawn;
  const now = deps.now || Date.now;
  /**
   * @typedef {{ id: string, name: string, ip: string|null, seen: number, proc: any, listeners: Set<any>, t0: number, bytes: number,
   *   restarts: number, timer: any, stopped: boolean, mode: string, maxRate: number, relay: any, events: Set<any> }} Phone
   */
  /** @type {Map<string, Phone>} */
  const phones = new Map();

  /** The phone's player: the relay for "exact", else squeezelite, restarted (more slowly each time) if it ends while wanted. @param {Phone} p */
  function start(p) {
    const host = deps.lmsHost();
    if (!host || p.stopped) return;
    if (p.mode === "exact") {
      p.relay = (deps.relay || makeRelayPlayer)({ host, mac: p.id, name: p.name, maxRate: p.maxRate, log: deps.log, send: (ev) => tell(p, ev) });
      p.relay.start();
      deps.log.info(`phone player "${p.name}" (${p.id}) started for LMS at ${host}, untouched`);
      if (deps.relayUp) setTimeout(() => { if (p.relay && deps.relayUp) deps.relayUp(p.id); }, 1500).unref();
      return;
    }
    const args = ["-o", "-", "-a", "32", "-r", String(RATE), "-R", "-Z", "192000", "-n", p.name, "-m", p.id, "-M", "Mallard", "-s", host + ":" + SLIMPROTO_PORT];
    let proc;
    try { proc = run(bin, args, { stdio: ["ignore", "pipe", "pipe"] }); } catch (e) { deps.log.warn("phone player:", /** @type {Error} */ (e).message); return; }
    p.proc = proc; p.t0 = now(); p.bytes = 0;
    deps.log.info(`phone player "${p.name}" (${p.id}) started for LMS at ${host}`);
    proc.on("error", (/** @type {Error} */ e) => deps.log.warn(`phone player "${p.name}":`, e.message));
    if (proc.stderr) proc.stderr.on("data", (/** @type {Buffer} */ d) => deps.log.debug("squeezelite:", String(d).trim()));
    proc.stdout.on("data", (/** @type {Buffer} */ chunk) => feed(p, chunk));
    proc.on("exit", (/** @type {number|null} */ code) => {
      if (p.proc !== proc) return;
      p.proc = null;
      if (p.stopped) return;
      const wait = Math.min(60000, 1000 * (1 << Math.min(p.restarts++, 6)));
      deps.log.warn(`phone player "${p.name}" ended (${code}); again in ${Math.round(wait / 1000)} s`);
      p.timer = setTimeout(() => start(p), wait);
    });
  }

  /** PCM from squeezelite: to every listener's encoder, and the read held to real time. @param {Phone} p @param {Buffer} chunk */
  function feed(p, chunk) {
    for (const l of p.listeners) {
      if (!l.ff.stdin.writable) continue;
      // A listener far behind (a stalled connection) is let go rather than buffered without end.
      if (l.ff.stdin.writableLength > 8 * BYTES_PER_SEC) { l.end(); continue; }
      l.ff.stdin.write(chunk);
    }
    p.bytes += chunk.length;
    const t = now();
    const ahead = p.bytes / BYTES_PER_SEC - (t - p.t0) / 1000;
    if (ahead < -2) { p.t0 = t - (p.bytes / BYTES_PER_SEC) * 1000; return; }   // fallen behind (a pause in reading): no rush to catch up
    if (ahead > AHEAD_S && p.proc) {
      const out = p.proc.stdout;
      out.pause();
      setTimeout(() => out.resume(), Math.round((ahead - AHEAD_S / 2) * 1000));
    }
  }

  /**
   * A phone said hello (away from home): its player, started if it isn't. [mode] "exact" for the untouched relay, else
   * squeezelite; [maxRate] the highest rate the phone (its DAC) takes, for the relay. A change of mode swaps the engine.
   * @param {{ deviceId: string, name?: string, ip?: string|null, mode?: string, maxRate?: number }} h
   */
  function hello(h) {
    const id = macFor(h.deviceId);
    const name = String(h.name || "").trim().slice(0, 60) || "Mallard phone";
    const mode = h.mode === "exact" ? "exact" : "pcm";
    const maxRate = Math.max(44100, Math.min(768000, Math.floor(Number(h.maxRate) || 192000)));
    let p = phones.get(id);
    if (!p) {
      p = { id, name, ip: null, seen: 0, proc: null, listeners: new Set(), t0: now(), bytes: 0, restarts: 0, timer: null, stopped: false,
        mode, maxRate, relay: null, events: new Set() };
      phones.set(id, p);
    }
    p.seen = now(); p.ip = h.ip || p.ip; p.stopped = false; p.name = name;
    if (p.mode !== mode || (mode === "exact" && p.maxRate !== maxRate)) { halt(p); p.mode = mode; p.maxRate = maxRate; }
    if (!p.proc && !p.timer && !p.relay) start(p);
    return { player_id: id, name, mode };
  }

  /** The phone's engine stopped (squeezelite or the relay), its listeners let go; the phone itself kept. @param {Phone} p */
  function halt(p) {
    if (p.timer) { clearTimeout(p.timer); p.timer = null; }
    for (const l of [...p.listeners]) l.end();
    if (p.proc) { const proc = p.proc; p.proc = null; try { proc.kill(); } catch (e) { /* gone */ } }
    if (p.relay) { p.relay.stop(); p.relay = null; }
  }

  /** An event for the phone's relay channel (every connection it has open). @param {Phone} p @param {object} ev */
  function tell(p, ev) {
    const line = "data: " + JSON.stringify(ev) + "\n\n";
    for (const r of p.events) { try { r.write(line); } catch (e) { /* gone */ } }
  }

  /** The phone back home (or gone): its player stopped; LMS forgets it soon after. @param {string} id */
  function bye(id) {
    const p = phones.get(id);
    if (!p) return false;
    p.stopped = true;
    halt(p);
    for (const r of [...p.events]) { try { r.end(); } catch (e) { /* gone */ } }
    phones.delete(id);
    deps.log.info(`phone player "${p.name}" stopped`);
    return true;
  }

  /**
   * The phone's stream, for as long as the connection lasts: [q] "opus" or "flac". False when there is no such phone.
   * @param {string} id @param {string} q @param {import("http").ServerResponse} res
   */
  function stream(id, q, res) {
    const p = phones.get(id);
    if (!p) return false;
    p.seen = now();
    const ff = run(ffmpeg, ffmpegArgs(q === "flac" ? "flac" : "opus"), { stdio: ["pipe", "pipe", "pipe"] });
    const l = {
      ff, ended: false,
      end() {
        if (l.ended) return; l.ended = true;
        p.listeners.delete(l);
        try { ff.stdin.end(); } catch (e) { /* gone */ }
        setTimeout(() => { try { ff.kill(); } catch (e) { /* gone */ } }, 500).unref();
        try { res.end(); } catch (e) { /* gone */ }
      },
    };
    ff.on("error", (/** @type {Error} */ e) => { deps.log.warn("phone stream encoder:", e.message); l.end(); });
    ff.stdin.on("error", () => l.end());
    if (ff.stderr) ff.stderr.on("data", (/** @type {Buffer} */ d) => deps.log.debug("ffmpeg:", String(d).trim()));
    res.writeHead(200, { "Content-Type": "audio/ogg", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
    ff.stdout.pipe(res);
    ff.on("exit", () => l.end());
    res.on("close", () => { l.end(); p.seen = now(); });
    p.listeners.add(l);
    return true;
  }

  /** The phone player whose hello came from [ip] (the tailnet address a request came in on), or null. @param {string|null|undefined} ip */
  function playerFor(ip) {
    if (!ip) return null;
    for (const p of phones.values()) if (p.ip === ip) return p.id;
    return null;
  }

  /**
   * The relay's channel to the phone (Server-Sent Events, held open): every command for it, as it happens. False when
   * there is no such phone playing untouched. @param {string} id @param {import("http").ServerResponse} res
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

  /** The relay player of phone [id], for the route that serves its tracks; null when it isn't playing untouched. @param {string} id */
  const relayOf = (id) => { const p = phones.get(id); return p && p.relay ? p.relay : null; };

  /** Every so often: a phone neither listening nor heard from in a while is let go. */
  function sweep() {
    const t = now();
    for (const p of [...phones.values()]) if (!p.listeners.size && !p.events.size && t - p.seen > IDLE_MS) bye(p.id);
  }
  const sweeper = setInterval(sweep, 60000);
  if (sweeper.unref) sweeper.unref();

  function stopAll() { for (const id of [...phones.keys()]) bye(id); clearInterval(sweeper); }

  /** For the apps and the log: the phones' players. */
  const list = () => [...phones.values()].map((p) => ({ player_id: p.id, name: p.name, mode: p.mode, running: !!(p.proc || p.relay), listeners: p.listeners.size + p.events.size }));

  return { hello, bye, stream, events, report, relayOf, playerFor, list, sweep, stopAll };
}

module.exports = { makePhonePlayers, macFor, isPhonePlayer, ffmpegArgs, BYTES_PER_SEC };
