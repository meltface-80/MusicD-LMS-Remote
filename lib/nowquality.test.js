"use strict";
// Now playing's quality line (lib/nowquality.js; owner request: "the quality of the music being played shown on the now
// playing screen, including volume levelling replay gain when enabled", This phone "opus 256kbps or its actual quality
// of bitperfect"). Pinned: each format's words, lossy never claiming a depth, the levelling modes, the phone's own line,
// and the album badges' labels kept as they were.
const q = require("./nowquality");

let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
const text = (o) => { const l = q.qualityLine(o); return l ? l.text : null; };

ok("FLAC 24/96", text({ fmt: { type: "flc", rate: 96000, bits: 24, bitrate: "4608kbps" } }) === "FLAC 24/96");
ok("CD quality: FLAC 16/44.1", text({ fmt: { type: "flc", rate: 44100, bits: 16 } }) === "FLAC 16/44.1");
ok("ALAC, WAV, AIFF by name", text({ fmt: { type: "alc", rate: 48000, bits: 24 } }) === "ALAC 24/48" && text({ fmt: { type: "wav", rate: 192000, bits: 24 } }) === "WAV 24/192");
ok("lossy: its codec and bitrate, never a depth", text({ fmt: { type: "mp3", rate: 44100, bits: 16, bitrate: "320kbps CBR" } }) === "MP3 320 kbps" &&
  text({ fmt: { type: "aac", bitrate: "256kbps" } }) === "AAC 256 kbps");
ok("DSD by its rate", text({ fmt: { type: "dsf", rate: 2822400 } }) === "DSD 2.8 MHz");
ok("nothing known: no line", q.qualityLine({ fmt: {} }) === null && q.qualityLine({}) === null);
const f = { type: "flc", rate: 44100, bits: 16, gain: -6.24, albumGain: -8.1 };
ok("levelling off: no gain said", text({ fmt: f, mode: 0 }) === "FLAC 16/44.1");
ok("track gain: the track's, with a true minus", text({ fmt: f, mode: 1 }) === "FLAC 16/44.1 · ReplayGain −6.2 dB");
ok("album gain: the album's", text({ fmt: f, mode: 2 }) === "FLAC 16/44.1 · ReplayGain −8.1 dB");
ok("album gain on a track with none: the track's", text({ fmt: { type: "flc", rate: 44100, bits: 16, gain: 2.5 }, mode: 2 }) === "FLAC 16/44.1 · ReplayGain +2.5 dB");
ok("a track with no ReplayGain tags: no gain said", text({ fmt: { type: "flc", rate: 44100, bits: 16 }, mode: 1 }) === "FLAC 16/44.1");
ok("This phone away, Opus: Opus 256 kbps · 24/48", text({ fmt: f, phone: { form: "opus", gainDb: null } }) === "Opus 256 kbps · 24/48" &&
  q.qualityLine({ fmt: f, phone: { form: "opus" } }).kind === "opus");
ok("This phone bit-perfect: the file's own quality", text({ fmt: { type: "flc", rate: 192000, bits: 24 }, phone: { form: "orig", gainDb: null } }) === "FLAC 24/192");
ok("This phone: the gain LMS sent with the track", text({ fmt: f, phone: { form: "opus", gainDb: -4.4 } }) === "Opus 256 kbps · 24/48 · ReplayGain −4.4 dB");
ok("the album badges' labels, as before", q.albumQualityLabel({ type: "flc", rate: 96000, bits: 24 }) === "24/96" && q.albumQualityLabel({ type: "mp3", rate: 44100, bits: 16 }) === "MP3" &&
  q.albumIsHiRes({ type: "flc", rate: 96000, bits: 24 }) && !q.albumIsHiRes({ type: "mp3", rate: 96000, bits: 24 }));

// ---- on the wire: /api/zone-state carries now_playing.format, the mode read once a minute, the phone's own line
(async () => {
  const http = require("http");
  const express = require("express");
  const { mountQuality } = require("./quality-routes");
  let prefReads = 0, t = 0;
  const lms = { getPlayerPref: async (id, name) => { prefReads++; return name === "replayGainMode" ? "1" : null; } };
  const relay = { nowPlaying: () => ({ form: "opus", gainDb: -3.0 }) };
  const app = express();
  mountQuality(app, { lms: () => lms, phones: { relayOf: (id) => (id === "02:6d:00:00:00:01" ? relay : null) }, now: () => t });
  app.get("/api/zone-state", (req, res) => res.json({ zone: { zone_id: req.query.zone, now_playing: { line1: "Angel", fmt: { type: "flc", rate: 96000, bits: 24, gain: -6.24 } } } }));
  const srv = await new Promise((r) => { const s = app.listen(0, "127.0.0.1", () => r(s)); });
  const get = (zone) => new Promise((resolve) => http.get({ host: "127.0.0.1", port: srv.address().port, path: "/api/zone-state?zone=" + encodeURIComponent(zone) }, (res) => {
    let b = ""; res.on("data", (d) => b += d); res.on("end", () => resolve(JSON.parse(b)));
  }));
  const room = await get("aa:bb:cc:dd:ee:ff");
  ok("a room: its track's format and the gain its levelling applies", room.zone.now_playing.format.text === "FLAC 24/96 \u00b7 ReplayGain \u22126.2 dB" && !("fmt" in room.zone.now_playing) && room.zone.now_playing.line1 === "Angel");
  await get("aa:bb:cc:dd:ee:ff");
  ok("…the levelling mode read once, not on every poll", prefReads === 1);
  t += 61000; await get("aa:bb:cc:dd:ee:ff");
  ok("…and again after a minute", prefReads === 2);
  const phone = await get("02:6d:00:00:00:01");
  ok("a phone's own player: what the phone plays, and the gain LMS sent it", phone.zone.now_playing.format.text === "Opus 256 kbps \u00b7 24/48 \u00b7 ReplayGain \u22123.0 dB" && phone.zone.now_playing.format.kind === "opus");
  srv.close();
  console.log(`\n${pass}/${pass + fail} now-playing quality tests passed.`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
