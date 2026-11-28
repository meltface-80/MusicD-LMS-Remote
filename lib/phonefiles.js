// @ts-check
"use strict";
/*
 * phonefiles.js — the tracks of This phone's queue BY ID, for the phone to keep ahead (owner request: "add caching like
 * Mandarin has"). Mandarin's phone keeps the next 20 tracks (10 on mobile data) on the phone ahead of time, so they play
 * through a dead spot or without the network; a track played again is already there. LMS hands a player only ONE track
 * ahead, so the phone can't fill its cache through its player: it asks for the queue ahead ([ahead], LMS's own play
 * order) and fetches each track by its LMS id ([serve]) — the file itself (LMS's /music/<id>/download, Range passed
 * through), or its Opus 256 kbps, made once from that file with Mandarin's arguments and kept here (least recently used
 * goes first past [maxBytes]). And as LMS starts each track, [identify] says which one it is, so the phone plays it by id
 * (from its cache when it has it) rather than as the player's one-off bytes.
 *
 * Only the library's own tracks: a streaming service's (remote) track, or one LMS starts part-way (a seek: its bytes begin
 * mid-file), is played as the player's bytes, as before.
 */
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");
const { opusArgs } = require("./relayplayer");

const ID_RE = /^\d+$/;

/**
 * @param {{ lms: () => any, dir: string, log: any, ffmpeg?: string, spawn?: typeof spawn, maxBytes?: number }} o
 */
function makePhoneFiles(o) {
  const run = o.spawn || spawn;
  const ffmpeg = o.ffmpeg || process.env.FFMPEG_BIN || "ffmpeg";
  const maxBytes = o.maxBytes || 2 * 1024 * 1024 * 1024;
  /** @type {Map<string, Promise<string|null>>} */
  const making = new Map();

  /** LMS's own address for the file of track [id], with its sign-in if it has one. @param {string} id */
  function fileUrl(id) {
    const l = o.lms();
    if (!l || !l.cfg) return null;
    const auth = l.cfg.username ? encodeURIComponent(l.cfg.username) + ":" + encodeURIComponent(l.cfg.password || "") + "@" : "";
    return `http://${auth}${l.cfg.host}:${l.cfg.port}/music/${id}/download`;
  }

  /** @param {any} row */
  const own = (row) => row && ID_RE.test(String(row.id)) && !Number(row.remote) ? String(row.id) : null;

  /**
   * The track LMS has just started streaming to phone [playerId]'s player: the one playing for a [fresh] start (play,
   * skip, seek), else the one after it (gapless). Null for a streaming service's track, or a fresh start part-way in.
   * @param {string} playerId @param {boolean} fresh
   * @returns {Promise<string|null>}
   */
  async function identify(playerId, fresh) {
    const l = o.lms();
    if (!l) return null;
    try {
      const r = await l.request(playerId, ["status", "-", 2, "tags:u"]);
      const loop = r.playlist_loop || [];
      if (fresh && Number(r.time) > 1.5) return null;   // started part-way: its bytes aren't the file's
      return own(loop[fresh ? 0 : 1]);
    } catch (e) { o.log.debug("phone files: which track:", /** @type {Error} */ (e).message); return null; }
  }

  /**
   * The next [count] tracks of phone [playerId]'s queue after the one playing, in LMS's play order (shuffle applied),
   * the library's own only, each once. @param {string} playerId @param {number} count
   */
  async function ahead(playerId, count) {
    const l = o.lms();
    if (!l) return [];
    const n = Math.max(0, Math.min(50, Math.floor(count) || 0));
    if (!n) return [];
    const r = await l.request(playerId, ["status", "-", n + 1, "tags:u"]);
    const seen = new Set();
    const out = [];
    for (const row of (r.playlist_loop || []).slice(1)) {
      const id = own(row);
      if (id && !seen.has(id)) { seen.add(id); out.push({ id }); }
    }
    return out;
  }

  /** Track [id]'s Opus, made if it isn't (once, however often asked at once): its path, or null. @param {string} id */
  function opus(id) {
    const file = path.join(o.dir, id + ".ogg");
    if (fs.existsSync(file)) { const t = new Date(); fs.utimes(file, t, t, () => {}); return Promise.resolve(file); }
    const busy = making.get(id);
    if (busy) return busy;
    const url = fileUrl(id);
    if (!url) return Promise.resolve(null);
    fs.mkdirSync(o.dir, { recursive: true });
    const tmp = file + ".part";
    const p = new Promise((resolve) => {
      const out = fs.createWriteStream(tmp);
      const proc = run(ffmpeg, opusArgs(url), { stdio: ["ignore", "pipe", "pipe"] });
      proc.stdout.pipe(out);
      if (proc.stderr) proc.stderr.on("data", (/** @type {Buffer} */ d) => o.log.debug("ffmpeg:", String(d).trim()));
      // Done once BOTH have happened, in whichever order: ffmpeg gone (its code) and the file closed (the pipe ends it).
      let code = /** @type {number|null|undefined} */ (undefined), closed = false;
      const finish = () => {
        if (code === undefined || !closed) return;
        if (code !== 0) { fs.rm(tmp, { force: true }, () => {}); o.log.warn(`phone files: no Opus for track ${id} (ffmpeg ${code})`); return resolve(null); }
        fs.rename(tmp, file, (e) => { if (e) return resolve(null); prune(); resolve(file); });
      };
      out.on("close", () => { closed = true; finish(); });
      proc.on("error", (/** @type {Error} */ e) => { o.log.warn("phone files: opus:", e.message); code = -1; out.end(); finish(); });
      proc.on("close", (/** @type {number|null} */ c) => { code = c; out.end(); finish(); });
    });
    making.set(id, p);
    p.finally(() => making.delete(id));
    return p;
  }

  /** The cache within its size: the least recently used go first. */
  function prune() {
    let files;
    try { files = fs.readdirSync(o.dir).filter((f) => f.endsWith(".ogg")).map((f) => { const p = path.join(o.dir, f); const s = fs.statSync(p); return { p, size: s.size, at: s.mtimeMs }; }); }
    catch (e) { return; }
    let total = files.reduce((a, f) => a + f.size, 0);
    for (const f of files.sort((a, b) => a.at - b.at)) { if (total <= maxBytes) break; fs.rm(f.p, { force: true }, () => {}); total -= f.size; }
  }

  /**
   * Track [id] to the phone: its Opus ([q] "opus"; Range by express), or the file itself from LMS, Range passed through.
   * @param {any} req @param {any} res @param {string} id @param {string} [q]
   */
  async function serve(req, res, id, q) {
    if (!ID_RE.test(id)) return res.status(400).json({ error: "track id required" });
    if (q === "opus") {
      const file = await opus(id);
      if (!file) return res.status(502).json({ error: "Couldn't make the track's Opus" });
      return res.type("audio/ogg").sendFile(file, { headers: { "Cache-Control": "no-store" } });
    }
    const url = fileUrl(id);
    if (!url) return res.status(503).json({ error: "Not connected" });
    const u = new URL(url);
    const headers = /** @type {Record<string, string>} */ ({});
    if (u.username) headers.Authorization = "Basic " + Buffer.from(decodeURIComponent(u.username) + ":" + decodeURIComponent(u.password)).toString("base64");
    if (req.headers.range) headers.Range = String(req.headers.range);
    const up = require("http").get({ host: u.hostname, port: u.port, path: u.pathname, headers }, (r) => {
      const pass = ["content-type", "content-length", "content-range", "accept-ranges", "last-modified"];
      /** @type {Record<string, any>} */
      const h = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };
      for (const k of pass) if (r.headers[k]) h[k] = r.headers[k];
      res.writeHead(r.statusCode || 502, h);
      r.pipe(res);
    });
    up.on("error", (e) => { o.log.debug("phone files: file:", e.message); if (!res.headersSent) res.status(502).json({ error: "LMS didn't send the file" }); else res.end(); });
    res.on("close", () => up.destroy());
  }

  return { identify, ahead, serve, opus, prune };
}

module.exports = { makePhoneFiles };
