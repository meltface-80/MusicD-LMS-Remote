// @ts-check
"use strict";
/*
 * quality-routes.js — Now playing's quality line on /api/zone-state (owner request: "for all zones the quality of the
 * music being played shown on the now playing screen including volume levelling replay gain when enabled"; This phone
 * "opus 256kbps or its actual quality of bitperfect"). The route itself (index.js) passes the track's format as it
 * comes from LMS (now_playing.fmt); this turns it into the words (lib/nowquality.js) as now_playing.format
 * {kind, text} — the field Mallard's Now playing already draws (Mandarin's). The player's levelling mode (pref
 * replayGainMode) is read at most once a minute per player, so the 2 s poll costs LMS nothing more. A phone's own player says what the phone reported it plays and the gain LMS sent.
 */
const { qualityLine } = require("./nowquality");

const MODE_MS = 60000;

/**
 * @param {any} app
 * @param {{ lms: () => any, phones?: { relayOf: (id: string) => any }|null, now?: () => number }} deps
 */
function mountQuality(app, deps) {
  const now = deps.now || Date.now;
  /** @type {Map<string, { at: number, mode: number }>} */
  const modes = new Map();

  /** The player's levelling mode (0 off, 1 track, 2 album, 3 smart), kept a minute. @param {string} id */
  async function modeOf(id) {
    const kept = modes.get(id);
    if (kept && now() - kept.at < MODE_MS) return kept.mode;
    let mode = kept ? kept.mode : 0;
    try { const l = deps.lms(); if (l && l.getPlayerPref) mode = Number(await l.getPlayerPref(id, "replayGainMode")) || 0; } catch (e) { /* the last known */ }
    modes.set(id, { at: now(), mode });
    return mode;
  }

  app.get("/api/zone-state", (/** @type {any} */ req, /** @type {any} */ res, /** @type {any} */ next) => {
    const json = res.json.bind(res);
    res.json = (/** @type {any} */ body) => {
      const np = body && body.zone && body.zone.now_playing;
      if (!np || !("fmt" in np)) return json(body);
      const id = String(body.zone.zone_id || "");
      const relay = deps.phones && deps.phones.relayOf ? deps.phones.relayOf(id) : null;
      const phone = relay && relay.nowPlaying ? relay.nowPlaying() : null;
      const { fmt, ...rest } = np;
      modeOf(id).then((mode) => json({ ...body, zone: { ...body.zone, now_playing: { ...rest, format: qualityLine({ fmt, mode, phone }) } } }),
        () => json({ ...body, zone: { ...body.zone, now_playing: rest } }));
      return res;
    };
    next();
  });
}

module.exports = { mountQuality };
