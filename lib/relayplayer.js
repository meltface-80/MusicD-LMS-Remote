// @ts-check
"use strict";
/*
 * relayplayer.js — a phone away from home as a Lyrion player that passes the music on UNTOUCHED (owner request:
 * "Away from home lossless … sends the stream untouched … for users who want bitperfect via usb DACs").
 *
 * Squeezelite can't do that: it decodes, and its stdout is PCM. This is a Slimproto PLAYER of the server's own, in place
 * of squeezelite for that phone (same MAC, so LMS keeps the player, its queue and its settings). LMS streams each track
 * to it as the file's own bytes (the capabilities' format order makes every common format a `-` passthrough); each
 * track is spooled to a temp file and offered to the phone as one item of a playlist, which the phone plays and reports
 * on. What LMS sees is a player like any other: STMc/STMs/STMd/STMt and the rest, with the phone's own position.
 *
 * THE ORDERING TRAP (verified in LMS 9.2's StreamingController): a DSCO or STMd that reaches LMS while it is BUFFERING —
 * after a fresh strm s, before the first STMs — makes it mark the song playing before a sound is made, and the phone's
 * later STMs is then taken for the NEXT track. So for a track that starts playback, both are held until the phone says
 * it started, then sent STMs, DSCO, STMd. A track queued behind one playing sends them as soon as it is fully read.
 * LMS keeps at most one track ahead, so at most two spools exist (three while one is let go).
 *
 * The phone's side arrives through [phoneEvent] and leaves through [send] (the phone's event channel, phone-routes.js).
 *
 * As Mandarin: each track is served as the file itself (bit-perfect; at home always), or — asked for with q=opus, away
 * from home — as Opus 256 kbps made from that file per track, resampled to 48 kHz by soxr and encoded from float
 * (Mandarin's Downloads.StreamOpus, to the argument). LMS's ReplayGain for the track goes to the phone with it
 * (gain_db), which applies it, as Mandarin's phone does: nothing on the server touches the samples for it.
 */
const { spawn } = require("child_process");
const net = require("net");
const fs = require("fs");
const os = require("os");
const path = require("path");
const sp = require("./slimproto");

/** LMS's Slimproto port: 3483, unless `SLIMPROTO_PORT` says otherwise (an LMS moved off it). */
const SLIMPROTO_PORT = Number(process.env.SLIMPROTO_PORT) || 3483;

/**
 * @param {{ host: string, mac: string, name: string, maxRate: number, log: any, send: (ev: object) => void,
 *   port?: number, now?: () => number, connect?: typeof net.connect, dir?: string, ffmpeg?: string, spawn?: typeof spawn }} o
 */
function makeRelayPlayer(o) {
  const now = o.now || Date.now;
  const connect = o.connect || net.connect;
  const t0 = now();
  const jiffies = () => (now() - t0) >>> 0;
  const dir = o.dir || fs.mkdtempSync(path.join(os.tmpdir(), "mallard-relay-"));
  const ffmpeg = o.ffmpeg || process.env.FFMPEG_BIN || "ffmpeg";
  const run = o.spawn || spawn;
  /**
   * A file being written and read at once: the track as LMS sent it, or its Opus.
   * @typedef {{ file: string, mime: string, size: number, done: boolean, failed: boolean, waiters: Set<() => void> }} Art
   * @typedef {Art & { seq: number, fmt: string, out: fs.WriteStream|null, sock: net.Socket|null, autostart: number,
   *   start: boolean, held: boolean, started: boolean, opus: (Art & { proc: any })|null }} Item
   */
  /** @type {Map<number, Item>} */
  const items = new Map();
  let seq = 0;
  let sock = /** @type {net.Socket|null} */ (null);
  let stopped = false;
  let reconnect = false;
  let retry = /** @type {any} */ (null);
  let bytes = 0;                 // stream bytes received, all told (STAT)
  let current = /** @type {Item|null} */ (null);   // the item the phone is playing
  let pos = { ms: 0, at: now(), playing: false };
  let heartbeat = /** @type {any} */ (null);
  let sentSTMl = false;
  let lastPhone = now();

  function write(/** @type {Buffer} */ b) { if (sock && !sock.destroyed) sock.write(b); }
  function elapsed() { return pos.ms + (pos.playing ? Math.max(0, now() - pos.at) : 0); }
  function stat(/** @type {string} */ event, serverTs = 0) {
    const s = current;
    write(sp.stat({ event, jiffies: jiffies(), serverTs, bytes, elapsedMs: current ? elapsed() : 0,
      streamSize: 2 << 20, streamFull: s && !s.done ? 1 << 20 : 0, outSize: 1 << 20, outFull: pos.playing ? 1 << 19 : 0 }));
  }

  /** Connect (again) and say hello; LMS keeps the player's queue across a reconnect. */
  function open() {
    if (stopped) return;
    const s = connect({ host: o.host, port: o.port || SLIMPROTO_PORT });
    sock = s;
    s.setNoDelay(true);
    s.on("connect", () => {
      o.log.info(`relay player "${o.name}" (${o.mac}) connected to LMS at ${o.host}`);
      write(sp.helo({ mac: o.mac, caps: sp.capabilities({ maxRate: o.maxRate }), reconnect, bytes }));
      reconnect = true;
    });
    s.on("data", sp.frameReader(onFrame));
    s.on("error", (e) => o.log.debug("relay slimproto:", e.message));
    s.on("close", () => {
      if (sock !== s) return;
      sock = null;
      if (stopped) return;
      o.log.warn(`relay player "${o.name}": LMS closed the connection; again in 3 s`);
      retry = setTimeout(open, 3000);
    });
  }

  /** @param {string} op @param {Buffer} p */
  function onFrame(op, p) {
    if (op === "strm") return onStrm(sp.parseStrm(p));
    if (op === "setd" && p.length >= 1 && p[0] === 0 && p.length === 1) return write(sp.setdName(o.name));
    // audg (LMS's volume: the player is fixed at 100% and its volume is the phone's own), aude, audc, audf, audp, audr,
    // bdac, serv, vers, setd (others), cont, codc, vfdc, grfe…: nothing to do.
  }

  /** @param {ReturnType<typeof sp.parseStrm>} s */
  function onStrm(s) {
    switch (s.command) {
      case "t": return write(sp.stat({ event: "STMt", jiffies: jiffies(), serverTs: s.replayGain, bytes, elapsedMs: current ? elapsed() : 0 }));
      case "q": dropAll(); o.send({ cmd: "stop" }); return stat("STMf");
      case "f": { const n = dropQueued(); if (n) stat("STMf"); return; }
      case "p": if (s.replayGain === 0) { pos = { ms: elapsed(), at: now(), playing: false }; o.send({ cmd: "pause" }); stat("STMp"); } return;
      case "u": pos = { ms: elapsed(), at: now(), playing: !!current }; o.send({ cmd: "resume" }); return stat("STMr");
      case "s": return startStream(s);
      default: return;   // a (skip ahead), sync only
    }
  }

  /** strm s: the next track's bytes, fetched from LMS as its request says, spooled and offered to the phone. @param {ReturnType<typeof sp.parseStrm>} s */
  function startStream(s) {
    stat("STMf");
    sentSTMl = false;
    const n = ++seq;
    const playing = !!current && [...items.values()].some((i) => i.started);
    /** @type {Item} */
    const it = { seq: n, fmt: s.format, mime: sp.mimeFor(s.format, s.pcmSize), file: path.join(dir, String(n)), size: 0, done: false, failed: false,
      out: fs.createWriteStream(path.join(dir, String(n))), sock: null, autostart: s.autostart, start: !playing, held: false, started: false, waiters: new Set(), opus: null };
    items.set(n, it);
    const ip = s.serverIp ? [s.serverIp >>> 24, (s.serverIp >>> 16) & 255, (s.serverIp >>> 8) & 255, s.serverIp & 255].join(".") : o.host;
    const c = connect({ host: ip, port: s.serverPort || 9000 });
    it.sock = c;
    let head = Buffer.alloc(0);
    let inBody = false;
    c.on("connect", () => { c.write(s.request, "latin1"); stat("STMc"); });
    c.on("data", (/** @type {Buffer} */ d) => {
      if (!inBody) {
        head = Buffer.concat([head, d]);
        const end = head.indexOf("\r\n\r\n");
        if (end < 0) return;
        inBody = true;
        write(sp.resp(head.toString("latin1", 0, end + 4)));
        d = head.subarray(end + 4);
      }
      if (!d.length) return;
      bytes += d.length;
      it.size += d.length;
      if (it.out) it.out.write(d);
      wake(it);
      // A paused start (autostart 0): LMS waits for STMl, once enough is in, before it says strm u.
      if (it.start && it.autostart === 0 && !sentSTMl && it.size >= Math.max(1, s.threshold) * 1024) { sentSTMl = true; stat("STMl"); }
    });
    c.on("error", (e) => { o.log.debug("relay stream:", e.message); it.failed = true; });
    c.on("close", () => {
      it.sock = null;
      if (!items.has(it.seq)) return;    // dropped (q / f)
      const finish = () => {
        it.done = true; wake(it);
        if (it.start && it.autostart === 0 && !sentSTMl) { sentSTMl = true; stat("STMl"); }
        // The trap: a track that starts playback holds DSCO and STMd until the phone says it started.
        if (it.start && !it.started) it.held = true; else { write(sp.dsco(0)); stat("STMd"); }
      };
      if (it.out) it.out.end(finish); else finish();
      it.out = null;
    });
    // LMS's ReplayGain for this track (16.16; 0 none), for the phone to apply.
    const gainDb = s.replayGain > 0 && s.replayGain !== 65536 ? Math.round(2000 * Math.log10(s.replayGain / 65536)) / 100 : null;
    o.send({ cmd: "add", seq: n, mime: it.mime, fresh: it.start, play: it.autostart === 1 || it.autostart === 3, gain_db: gainDb });
  }

  function wake(/** @type {Art} */ it) { for (const w of [...it.waiters]) w(); }

  /**
   * Item [n] as Opus 256 kbps (Mandarin's arguments), made once from the whole file and kept beside it: null when there
   * is no such item. Made as it is first asked for; read while it is still being written.
   * @param {number} n
   */
  function opusOf(n) {
    const it = items.get(n);
    if (!it) return null;
    if (it.opus) return it.opus;
    const art = { file: it.file + ".opus", mime: "audio/ogg", size: 0, done: false, failed: false, waiters: new Set(), proc: /** @type {any} */ (null) };
    it.opus = art;
    const go = () => {
      if (!items.has(n) || it.failed) { art.failed = true; art.done = true; wake(art); return; }
      const out = fs.createWriteStream(art.file);
      const p = run(ffmpeg, opusArgs(it.file), { stdio: ["ignore", "pipe", "pipe"] });
      art.proc = p;
      p.on("error", (/** @type {Error} */ e) => { o.log.warn("relay opus:", e.message); art.failed = true; art.done = true; wake(art); });
      if (p.stderr) p.stderr.on("data", (/** @type {Buffer} */ d) => o.log.debug("ffmpeg:", String(d).trim()));
      p.stdout.on("data", (/** @type {Buffer} */ d) => { out.write(d); art.size += d.length; wake(art); });
      p.on("close", (/** @type {number|null} */ code) => {
        art.proc = null;
        if (code !== 0) art.failed = true;
        out.end(() => { art.done = true; wake(art); });
      });
    };
    // From the whole file: an MP4 (ALAC) names its frames at its end, so a pipe can't be read as it arrives.
    if (it.done) go(); else { const w = () => { if (it.done) { it.waiters.delete(w); go(); } }; it.waiters.add(w); }
    return art;
  }

  function forget(/** @type {Item} */ it) {
    items.delete(it.seq);
    if (it.sock) it.sock.destroy();
    if (it.out) it.out.destroy();
    it.done = true; it.failed = true; wake(it);
    fs.rm(it.file, { force: true }, () => {});
    if (it.opus) {
      const a = it.opus;
      if (a.proc) { try { a.proc.kill(); } catch (e) { /* gone */ } }
      a.done = true; a.failed = true; wake(a);
      fs.rm(a.file, { force: true }, () => {});
    }
  }
  function dropAll() { for (const it of [...items.values()]) forget(it); current = null; pos = { ms: 0, at: now(), playing: false }; }
  /** strm f: the tracks not yet started are let go (the playlist changed after the one streaming). */
  function dropQueued() {
    let n = 0;
    for (const it of [...items.values()]) if (!it.started) { forget(it); o.send({ cmd: "drop", seq: it.seq }); n++; }
    return n;
  }

  /**
   * The phone, about what it plays: started (an item began making sound), pos (where it is in it), drained (nothing
   * left to play), failed (it couldn't play one).
   * @param {{ ev: string, seq?: number, ms?: number, playing?: boolean }} e
   */
  function phoneEvent(e) {
    lastPhone = now();
    const it = typeof e.seq === "number" ? items.get(e.seq) : undefined;
    if (e.ev === "started" && it && !it.started) {
      it.started = true;
      for (const old of [...items.values()]) if (old.seq < it.seq) forget(old);
      current = it;
      pos = { ms: Math.max(0, e.ms || 0), at: now(), playing: true };
      stat("STMs");
      if (it.held) { it.held = false; write(sp.dsco(0)); stat("STMd"); }
    } else if (e.ev === "pos" && it && it === current) {
      pos = { ms: Math.max(0, e.ms || 0), at: now(), playing: !!e.playing };
    } else if (e.ev === "drained") {
      pos = { ms: elapsed(), at: now(), playing: false };
      stat("STMu");
      current = null;
    } else if (e.ev === "failed" && it) {
      stat("STMn");
    }
  }

  /**
   * Item [n]'s bytes [from]..[to] (inclusive; Infinity: to its end) into [out], following a spool still being written,
   * at the pace [out] takes them; [q] "opus" for its Opus. Resolves true when all of it went, false when the item went
   * away or [out] closed.
   * @param {number} n @param {number} from @param {number} to @param {import("stream").Writable} out @param {string} [q]
   */
  async function pipe(n, from, to, out, q) {
    const it = art(n, q);
    if (!it) return false;
    let at = from;
    let closed = false;
    out.once("close", () => { closed = true; wake(it); });
    for (;;) {
      if (closed || !items.has(n)) return false;
      const last = Math.min(to, it.size - 1);
      if (at <= last) {
        const rs = fs.createReadStream(it.file, { start: at, end: last });
        const ok = await new Promise((resolve) => {
          const gone = () => { rs.destroy(); resolve(false); };
          out.once("close", gone);
          rs.on("error", () => { out.off("close", gone); resolve(false); });
          rs.on("end", () => { out.off("close", gone); resolve(true); });
          rs.pipe(out, { end: false });
        });
        rs.unpipe(out);
        if (!ok) return false;
        at = last + 1;
        continue;
      }
      if (at > to || (it.done && at >= it.size)) return true;
      await new Promise((resolve) => { const w = () => { it.waiters.delete(w); resolve(null); }; it.waiters.add(w); });
    }
  }

  /** Item [n] as LMS sent it, or ([q] "opus") its Opus, made if it isn't. @param {number} n @param {string} [q] */
  const art = (n, q) => (q === "opus" ? opusOf(n) : items.get(n) || null);

  /** An item, for the route that serves it: its type, whether it is all here, its size so far. @param {number} n @param {string} [q] */
  const item = (n, q) => { const it = art(n, q); return it ? { mime: it.mime, done: it.done, size: it.size } : null; };

  /** Wait (up to [ms]) until item [n] (its Opus, [q]) is all here; resolves whether it is. @param {number} n @param {number} ms @param {string} [q] */
  function whole(n, ms, q) {
    return new Promise((resolve) => {
      const it = art(n, q);
      if (!it || it.done) { resolve(!!it && !it.failed); return; }
      const t = setTimeout(() => { it.waiters.delete(w); resolve(false); }, ms);
      const w = () => { if (it.done) { clearTimeout(t); it.waiters.delete(w); resolve(!it.failed); } };
      it.waiters.add(w);
    });
  }

  function start() {
    open();
    // STMt once a second while the phone plays (squeezelite's heartbeat; LMS reads the position from it).
    heartbeat = setInterval(() => { if (pos.playing && current) stat("STMt"); }, 1000);
    if (heartbeat.unref) heartbeat.unref();
  }

  function stop() {
    stopped = true;
    if (retry) clearTimeout(retry);
    if (heartbeat) clearInterval(heartbeat);
    dropAll();
    if (sock) sock.destroy();
    sock = null;
    fs.rm(dir, { recursive: true, force: true }, () => {});
  }

  /** Seconds since the phone was last heard from. */
  const quiet = () => (now() - lastPhone) / 1000;

  return { start, stop, phoneEvent, pipe, item, whole, quiet, get playing() { return pos.playing; } };
}

/**
 * ffmpeg's arguments for a track's Opus (Mandarin's Downloads.StreamOpus): resampled to 48 kHz by soxr at its highest
 * precision in double, encoded from 32-bit FLOAT (never cut to 16-bit), 256 kbps VBR in Ogg. Opus has no bit depth of
 * its own; Mallard decodes it to float with its own libopus, so it reaches the phone at 24-bit/48 kHz.
 * @param {string} file
 */
function opusArgs(file) {
  return ["-hide_banner", "-loglevel", "error", "-i", file, "-map", "0:a:0",
    "-af", "aresample=resampler=soxr:precision=33:internal_sample_fmt=dblp:osr=48000", "-ar", "48000", "-ac", "2",
    "-sample_fmt", "flt", "-c:a", "libopus", "-b:a", "256k", "-vbr", "on", "-application", "audio", "-f", "ogg", "pipe:1"];
}

module.exports = { makeRelayPlayer, opusArgs, SLIMPROTO_PORT };
