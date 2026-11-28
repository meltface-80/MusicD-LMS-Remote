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
 *
 * WHERE THE FILE COMES FROM (owner report: LMS's /music/<id>/download never answered on the owner's server — no status,
 * no byte, for 30 s — and every fetch ahead hung on it): the music folder mounted into this container (MUSIC_DIR,
 * read-only, the same mount the label scan uses) when it is there, the track found by the tail of the path LMS has for it;
 * else LMS's download, given [headMs] to answer and, when it doesn't, not asked again for [downMs] (an answer at once,
 * 503, instead of a hang). An Opus made from LMS's address gives up when its input goes quiet.
 */
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");
const { opusArgs } = require("./relayplayer");

const ID_RE = /^\d+$/;

/**
 * @param {{ lms: () => any, dir: string, log: any, ffmpeg?: string, spawn?: typeof spawn, maxBytes?: number,
 *   musicDir?: string, headMs?: number, downMs?: number, now?: () => number }} o
 */
function makePhoneFiles(o) {
  const run = o.spawn || spawn;
  const ffmpeg = o.ffmpeg || process.env.FFMPEG_BIN || "ffmpeg";
  const maxBytes = o.maxBytes || 2 * 1024 * 1024 * 1024;
  /** @type {Map<string, any>} */
  const jobs = new Map();
  const musicDir = path.resolve(o.musicDir || process.env.MUSIC_DIR || "/music");
  const headMs = o.headMs || 6000, downMs = o.downMs || 10 * 60 * 1000, now = o.now || Date.now;
  let downAt = 0;   // when LMS's download last failed to answer
  const downloadUp = () => !downAt || now() - downAt > downMs;
  const downloadDown = (/** @type {string} */ why) => { if (downloadUp()) o.log.warn(`phone files: LMS's download didn't answer (${why}); not asked again for ${Math.round(downMs / 60000)} min — the music folder mount (MUSIC_DIR) is used when it is there`); downAt = now(); };
  /** @type {Map<string, string|null>} */
  const paths = new Map();

  /**
   * Track [id]'s file in the mounted music folder: the path LMS has for it, matched by its tail (LMS's folder and this
   * container's mount differ in where they start). Null when there is no mount or no such file. @param {string} id
   * @returns {Promise<string|null>}
   */
  async function localFile(id) {
    if (paths.has(id)) return /** @type {string|null} */ (paths.get(id));
    let found = null;
    try {
      if (fs.statSync(musicDir).isDirectory()) {
        const l = o.lms();
        const r = l ? await l.request("", ["titles", 0, 1, "track_id:" + id, "tags:u"]) : null;
        const url = r && r.titles_loop && r.titles_loop[0] ? String(r.titles_loop[0].url || "") : "";
        if (/^file:\/\//i.test(url)) {
          const segs = decodeURIComponent(new URL(url).pathname).split("/").filter(Boolean);
          for (let i = 0; i < segs.length && !found; i++) {
            const p = path.join(musicDir, ...segs.slice(i));
            if (p.startsWith(musicDir + path.sep) && fs.existsSync(p) && fs.statSync(p).isFile()) found = p;
          }
        }
      }
    } catch (e) { o.log.debug("phone files: the music folder:", /** @type {Error} */ (e).message); }
    if (paths.size > 2000) paths.clear();
    paths.set(id, found);
    return found;
  }

  /** Where track [id]'s file is read from: the mounted folder, or LMS's download while it answers; null for neither. @param {string} id */
  const source = async (id) => (await localFile(id)) || (downloadUp() ? fileUrl(id) : null);

  /** LMS's own address for the file of track [id], with its sign-in if it has one. @param {string} id */
  function fileUrl(id) {
    const l = o.lms();
    if (!l || !l.cfg) return null;
    const auth = l.cfg.username ? encodeURIComponent(l.cfg.username) + ":" + encodeURIComponent(l.cfg.password || "") + "@" : "";
    return `http://${auth}${l.cfg.host}:${l.cfg.port}/music/${id}/download`;
  }

  /**
   * A track that is a FILE: its LMS id, else null. A streaming service's album in the library (Qobuz's online library)
   * has an ordinary-looking id too, but its address is qobuz://… — there is no file to fetch or keep ahead, and LMS's
   * download never answers for it (owner report: testing with Qobuz only). It plays as the player's bytes.
   * @param {any} row
   */
  const own = (row) => row && ID_RE.test(String(row.id)) && !Number(row.remote) && (!row.url || /^file:/i.test(String(row.url))) ? String(row.id) : null;

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

  /**
   * Track [id]'s Opus being made (once, however often asked at once): followed as it is written, so the phone hears it
   * at once rather than after the whole track is encoded — on a small server that takes longer than the phone waits.
   * Null when it is already made, or there is no LMS to make it from.
   * @param {string} id @returns {Job|null}
   * @typedef {{ tmp: string, size: number, done: boolean, failed: boolean, waiters: Set<() => void>, made: Promise<string|null> }} Job
   */
  function making(id, /** @type {string|null} */ url) {
    const busy = jobs.get(id);
    if (busy) return busy;
    const file = path.join(o.dir, id + ".ogg");
    if (fs.existsSync(file)) return null;
    if (!url) return null;
    const remote = /^https?:/.test(url);
    const args = opusArgs(url);
    // From LMS's address: give up when it goes quiet for 10 s, rather than wait for ever (owner report: it never answered).
    if (remote) args.splice(args.indexOf("-i"), 0, "-rw_timeout", "10000000");
    fs.mkdirSync(o.dir, { recursive: true });
    const tmp = file + ".part";
    const out = fs.createWriteStream("", { fd: fs.openSync(tmp, "w") });   // there at once, for a follower to open
    /** @type {Job} */
    const job = { tmp, size: 0, done: false, failed: false, waiters: new Set(), made: Promise.resolve(null) };
    const wake = () => { for (const w of [...job.waiters]) w(); };
    job.made = new Promise((resolve) => {
      const proc = run(ffmpeg, args, { stdio: ["ignore", "pipe", "pipe"] });
      proc.stdout.on("data", (/** @type {Buffer} */ d) => out.write(d, () => { job.size += d.length; wake(); }));
      if (proc.stderr) proc.stderr.on("data", (/** @type {Buffer} */ d) => o.log.debug("ffmpeg:", String(d).trim()));
      // Done once BOTH have happened, in whichever order: ffmpeg gone (its code) and the file closed (every write in).
      let code = /** @type {number|null|undefined} */ (undefined), closed = false;
      const end = (/** @type {string|null} */ made) => { job.failed = !made; job.done = true; wake(); resolve(made); };
      const finish = () => {
        if (code === undefined || !closed) return;
        if (code !== 0) { fs.rm(tmp, { force: true }, () => {}); o.log.warn(`phone files: no Opus for track ${id} (ffmpeg ${code})`); if (remote && job.size === 0) downloadDown("nothing came for its Opus"); return end(null); }
        fs.rename(tmp, file, (e) => { if (e) return end(null); prune(); end(file); });
      };
      out.on("close", () => { closed = true; finish(); });
      proc.on("error", (/** @type {Error} */ e) => { o.log.warn("phone files: opus:", e.message); code = -1; out.end(); finish(); });
      proc.on("close", (/** @type {number|null} */ c) => { code = c; out.end(); finish(); });
    });
    jobs.set(id, job);
    job.made.finally(() => jobs.delete(id));
    return job;
  }

  /** Track [id]'s Opus, made if it isn't: its path once it is all there, or null. @param {string} id */
  async function opus(id) {
    const file = path.join(o.dir, id + ".ogg");
    if (fs.existsSync(file)) { const t = new Date(); fs.utimes(file, t, t, () => {}); return file; }
    const job = jobs.get(id) || making(id, await source(id));
    return job ? job.made : null;
  }

  /**
   * [job]'s Opus into [res] as it is written, from its start to its end; false when the file was already gone (made
   * and renamed in the meantime: the caller sends the finished one). @param {Job} job @param {any} res
   */
  async function follow(job, res) {
    let fh;
    try { fh = await fs.promises.open(job.tmp, "r"); } catch (e) { return false; }
    let at = 0, gone = false;
    const wait = (/** @type {boolean} */ drain) => new Promise((resolve) => {
      const w = () => { job.waiters.delete(w); res.off("close", w); res.off("drain", w); resolve(null); };
      job.waiters.add(w); res.once("close", w); if (drain) res.once("drain", w);
    });
    res.once("close", () => { gone = true; });
    try {
      res.writeHead(200, { "Content-Type": "audio/ogg", "Cache-Control": "no-store, no-transform", "X-Content-Type-Options": "nosniff" });
      for (;;) {
        if (gone) return true;
        if (at < job.size) {
          const { bytesRead, buffer } = await fh.read(Buffer.allocUnsafe(Math.min(256 * 1024, job.size - at)), 0, Math.min(256 * 1024, job.size - at), at);
          if (bytesRead <= 0) { await wait(false); continue; }
          at += bytesRead;
          if (!res.write(buffer.subarray(0, bytesRead))) await wait(true);
          continue;
        }
        if (job.done) { if (job.failed) res.destroy(); else res.end(); return true; }
        await wait(false);
      }
    } finally { await fh.close().catch(() => {}); }
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
    const t0 = Date.now();
    let fromLms = -1;   // the file's bytes LMS sent for it (the original only)
    res.on("close", () => o.log.info(`phone file ${id}${q ? " (opus)" : ""}${req.headers.range ? " " + req.headers.range : ""}: ${res.statusCode} in ${Date.now() - t0} ms` +
      (fromLms >= 0 ? `, ${fromLms} bytes from LMS` : fromLms === -2 ? ", from the music folder" : "")));
    if (q === "opus") {
      // Being made, and asked from its start: followed as it is written. Made, or asked part-way (Range): the file.
      const job = jobs.get(id) || (fs.existsSync(path.join(o.dir, id + ".ogg")) ? null : making(id, await source(id)));
      const range = String(req.headers.range || "");
      if (job && (!range || /^bytes=0-$/.test(range)) && await follow(job, res)) return;
      const file = await opus(id);
      if (!file) return res.status(502).json({ error: "Couldn't make the track's Opus" });
      return res.type("audio/ogg").sendFile(file, { headers: { "Cache-Control": "no-store, no-transform" } });
    }
    const local = await localFile(id);
    if (local) { fromLms = -2; return res.sendFile(local, { dotfiles: "allow", headers: { "Cache-Control": "no-store, no-transform" } }); }
    if (!downloadUp()) return res.status(503).json({ error: "Lyrion's download isn't answering: the track is fetched as it plays" });
    const url = fileUrl(id);
    if (!url) return res.status(503).json({ error: "Not connected" });
    const u = new URL(url);
    const headers = /** @type {Record<string, string>} */ ({});
    if (u.username) headers.Authorization = "Basic " + Buffer.from(decodeURIComponent(u.username) + ":" + decodeURIComponent(u.password)).toString("base64");
    if (req.headers.range) headers.Range = String(req.headers.range);
    const late = setTimeout(() => { if (!res.headersSent) { downloadDown(`no answer in ${headMs / 1000} s`); up.destroy(); res.status(504).json({ error: "Lyrion's download didn't answer" }); } }, headMs);
    const up = require("http").get({ host: u.hostname, port: u.port, path: u.pathname, headers }, (r) => {
      clearTimeout(late);
      const pass = ["content-type", "content-length", "content-range", "accept-ranges", "last-modified"];
      /** @type {Record<string, any>} */
      const h = { "Cache-Control": "no-store, no-transform", "X-Content-Type-Options": "nosniff" };
      for (const k of pass) if (r.headers[k]) h[k] = r.headers[k];
      if ((r.statusCode || 502) >= 400) o.log.warn(`phone files: LMS answered ${r.statusCode} for track ${id}'s file`);
      res.writeHead(r.statusCode || 502, h);
      fromLms = 0;
      r.on("data", (/** @type {Buffer} */ d) => { fromLms += d.length; });
      r.pipe(res);
    });
    up.on("error", (e) => {
      clearTimeout(late);
      o.log.debug("phone files: file:", e.message);
      if (!res.headersSent) res.status(502).json({ error: "LMS didn't send the file" }); else res.end();
    });
    res.on("close", () => up.destroy());
  }

  return { identify, ahead, serve, opus, prune };
}

module.exports = { makePhoneFiles };
