// @ts-check
"use strict";
/*
 * slimproto.js — the PLAYER side of Lyrion's Slimproto, as bytes (for lib/relayplayer.js, the bit-perfect relay).
 * Verified against squeezelite's slimproto.c/.h and LMS 9.2's Slim/Networking/Slimproto.pm + Slim/Player/Squeezebox.pm.
 *
 * Player → server: op(4) len(u32 BE) payload.   Server → player: len(u16 BE, counting op and payload) op(4) payload.
 */

/** A player→server frame. @param {string} op @param {Buffer} payload */
function frame(op, payload) {
  const head = Buffer.alloc(8);
  head.write(op, 0, 4, "latin1");
  head.writeUInt32BE(payload.length, 4);
  return Buffer.concat([head, payload]);
}

/** "02:6d:…" → its six bytes. @param {string} mac */
function macBytes(mac) {
  const b = Buffer.from(String(mac).split(":").map((x) => parseInt(x, 16)));
  if (b.length !== 6 || [...b].some((x) => Number.isNaN(x))) throw new Error("bad MAC: " + mac);
  return b;
}

/**
 * HELO: device id 12 (SqueezePlay, as squeezelite), revision 0, the MAC, then the capabilities. [reconnect] sets the
 * wlan field's 0x4000 (squeezelite's reconnect flag), so LMS carries on with the player as it was.
 * @param {{ mac: string, caps: string, reconnect?: boolean, bytes?: number }} o
 */
function helo(o) {
  const p = Buffer.alloc(36);
  p.writeUInt8(12, 0);
  p.writeUInt8(0, 1);
  macBytes(o.mac).copy(p, 2);
  // uuid (16 bytes) zero
  p.writeUInt16BE(o.reconnect ? 0x4000 : 0, 24);
  const bytes = BigInt(Math.max(0, Math.floor(o.bytes || 0)));
  p.writeUInt32BE(Number(bytes >> 32n), 26);
  p.writeUInt32BE(Number(bytes & 0xffffffffn), 30);
  p.write("en", 34, 2, "latin1");
  return frame("HELO", Buffer.concat([p, Buffer.from(o.caps, "latin1")]));
}

/**
 * The capabilities string. FORMAT ORDER MATTERS: LMS tries a file against the player's formats in this order and the
 * first rule that exists wins, so with `flc` first every WAV/ALAC file would be re-encoded; in this order each of
 * flac, wav, alac, ogg, opus and mp3 meets its own `X X * * -` passthrough (convert.conf). [maxRate] is the highest
 * rate the phone takes: above it LMS resamples.
 * @param {{ maxRate: number, name?: string }} o
 */
function capabilities(o) {
  const rate = Math.max(44100, Math.min(768000, Math.floor(o.maxRate || 192000)));
  return `Model=mallard,ModelName=Mallard,Firmware=1,MaxSampleRate=${rate},HasDigitalOut=1,AccuratePlayPoints=1,wav,alc,ogg,ops,flc,mp3,aif,pcm`;
}

/**
 * STAT: [event] (STMc, STMs, STMt, …) with the player's state. [elapsedMs] is time played since THIS stream's first
 * sample (LMS adds the seek offset itself); [jiffies] the player's millisecond clock; [serverTs] echoed back raw.
 * @param {{ event: string, elapsedMs?: number, jiffies: number, serverTs?: number, bytes?: number,
 *   streamSize?: number, streamFull?: number, outSize?: number, outFull?: number }} s
 */
function stat(s) {
  const p = Buffer.alloc(53);
  p.write(s.event, 0, 4, "latin1");
  // num_crlf, mas_initialized, mas_mode: 0
  p.writeUInt32BE((s.streamSize || 0) >>> 0, 7);
  p.writeUInt32BE((s.streamFull || 0) >>> 0, 11);
  const bytes = BigInt(Math.max(0, Math.floor(s.bytes || 0)));
  p.writeUInt32BE(Number(bytes >> 32n), 15);
  p.writeUInt32BE(Number(bytes & 0xffffffffn), 19);
  p.writeUInt16BE(0xffff, 23);                       // signal strength: wired
  p.writeUInt32BE(s.jiffies >>> 0, 25);
  p.writeUInt32BE((s.outSize || 0) >>> 0, 29);
  p.writeUInt32BE((s.outFull || 0) >>> 0, 33);
  const ms = Math.max(0, Math.floor(s.elapsedMs || 0));
  p.writeUInt32BE(Math.floor(ms / 1000) >>> 0, 37);
  // voltage: 0
  p.writeUInt32BE(ms >>> 0, 43);
  p.writeUInt32BE((s.serverTs || 0) >>> 0, 47);
  // error code: 0
  return frame("STAT", p);
}

/** DSCO: the HTTP stream ended ([reason] 0: ok). @param {number} [reason] */
const dsco = (reason = 0) => frame("DSCO", Buffer.from([reason & 0xff]));
/** RESP: the stream's HTTP response headers, as received. @param {string} headers */
const resp = (headers) => frame("RESP", Buffer.from(headers, "latin1"));
/** SETD id 0: the player's name. @param {string} name */
const setdName = (name) => frame("SETD", Buffer.concat([Buffer.from([0]), Buffer.from(name + "\0", "utf8")]));

/**
 * Server→player frames out of a byte stream: returns a reader that takes chunks and calls [onFrame](op, payload).
 * @param {(op: string, payload: Buffer) => void} onFrame
 */
function frameReader(onFrame) {
  /** @type {Buffer<ArrayBufferLike>} */
  let buf = Buffer.alloc(0);
  return (/** @type {Buffer<ArrayBufferLike>} */ chunk) => {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    while (buf.length >= 2) {
      const len = buf.readUInt16BE(0);
      if (len < 4) throw new Error("slimproto frame too short: " + len);
      if (buf.length < 2 + len) break;
      onFrame(buf.toString("latin1", 2, 6), buf.subarray(6, 2 + len));
      buf = buf.subarray(2 + len);
    }
  };
}

/**
 * A strm frame's fields (Squeezebox.pm stream_s, pack `aaaaaaaCCCaCCCNnN`) and its HTTP request string.
 * @param {Buffer} p
 */
function parseStrm(p) {
  if (p.length < 24) throw new Error("strm too short");
  const ch = (/** @type {number} */ i) => String.fromCharCode(p[i]);
  return {
    command: ch(0),
    autostart: p[1] - 48,          // '0'..'3'
    format: ch(2),                 // p pcm/wav/aif, f flac, l alac, o ogg, u opus, m mp3, a aac, d dsd
    pcmSize: ch(3), pcmRate: ch(4), pcmChannels: ch(5), pcmEndian: ch(6),
    threshold: p[7],               // KB
    transitionPeriod: p[9], transitionType: ch(10),
    flags: p[11],
    outputThreshold: p[12],
    replayGain: p.readUInt32BE(14),     // also the interval (p/a) or the jiffies (u), and the timestamp to echo (t)
    replayGainRaw: p.subarray(14, 18),
    serverPort: p.readUInt16BE(18),
    serverIp: p.readUInt32BE(20),       // 0: the slimproto server's own address
    request: p.toString("latin1", 24),
  };
}

/**
 * An audg frame's new gain (`old L, old R, dvc, preamp, new L, new R`): LMS's volume, as linear 16.16 per side.
 * Sent whatever digitalVolumeControl says; with it 0 the player is asked not to apply it to the samples.
 * @param {Buffer} p
 */
function parseAudg(p) {
  if (p.length < 18) return null;
  return { dvc: p[8], left: p.readUInt32BE(10), right: p.readUInt32BE(14) };
}

/** A 16.16 linear gain in dB (null for silence). @param {number} g */
const gainDb = (g) => (g > 0 ? 20 * Math.log10(g / 65536) : null);

/** The MIME type a strm format's passthrough bytes are. @param {string} f @param {string} [pcmSize] */
function mimeFor(f, pcmSize) {
  switch (f) {
    case "f": return pcmSize === "o" ? "audio/ogg" : "audio/flac";
    case "l": return "audio/mp4";
    case "o": case "u": return "audio/ogg";
    case "m": return "audio/mpeg";
    case "a": return "audio/aac";
    case "p": return "audio/wav";
    default: return "application/octet-stream";
  }
}

module.exports = { frame, helo, capabilities, stat, dsco, resp, setdName, frameReader, parseStrm, parseAudg, gainDb, mimeFor, macBytes };
