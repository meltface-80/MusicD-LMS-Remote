// @ts-check
"use strict";
/*
 * diagnostics.js — the diagnostic log (owner request: "logging added, a full sweep covering everything, as Mandarin
 * does, adapted to Lyrion; capture all Lyrion's logs too; downloaded to the phone from Mallard, through the browser
 * from the web app; off by default, a user turns it on if they feel they are having trouble").
 *
 * Mandarin's design, behind a switch: ONE log, the server's own (lib/log.js, data/logs/musicd.log, rotating), into which
 * Mallard's own lines (its screens, taps, every request with its time, playback, crashes and freezes; AppLog) and the
 * web page's errors are sent while the switch is on, each as "[app <phone>]" / "[web <browser>]"; the server itself
 * logs at debug then, and writes a [snapshot] of its state every five minutes. Off (the default), nothing is sent and the
 * server logs as it always did.
 *
 * GET /api/diagnostics/log is the download: a header (versions, Lyrion, the players, recent Lyrion outages), this
 * server's log files, then Lyrion's own server.log and scanner.log, read from Lyrion's web server — every line without
 * what is the owner's alone (lib/scrub.js; Mallard's LogRedact). Mallard adds its own recent lines on the phone.
 *
 *   GET  /api/diagnostics          → {enabled}
 *   POST /api/diagnostics          {enabled} → {enabled}
 *   POST /api/diagnostics/app      {device, text} — Mallard's lines (only while on; 512 KB a request)
 *   POST /api/diagnostics/page     {lines:[{level,text}]} — the web page's errors (only while on; 600 lines a minute)
 *   GET  /api/diagnostics/log      → text/plain, as a file to save
 */
const fs = require("fs");
const path = require("path");
const http = require("http");
const os = require("os");
const { scrub } = require("./scrub");

const FEATURE = "diagnostics";
const APP_MAX = 512 * 1024;
const OWN_MAX = 24 * 1024 * 1024;       // this server's log in a download: its newest 24 MB
const LYRION_MAX = 6 * 1024 * 1024;     // each of Lyrion's logs: its newest 6 MB
const SNAPSHOT_MS = 5 * 60 * 1000;

/**
 * @param {any} app
 * @param {{ features: { enabled: (id: string) => boolean, setEnabled: (id: string, on: boolean) => any },
 *   logMod: { makeLogger: (tag: string) => any, setLevel: (n: string) => string, levelName: () => string|undefined, files: () => string[] },
 *   lms: () => any, version: string, state: () => any, outages?: () => any[], fetch?: (url: string) => Promise<string>, now?: () => number }} deps
 */
function mountDiagnostics(app, deps) {
  const log = deps.logMod.makeLogger("diagnostics");
  const now = deps.now || Date.now;
  const base = deps.logMod.levelName() || "info";
  const on = () => deps.features.enabled(FEATURE);
  /** @type {any} */
  let timer = null;

  /** The server's own level and its snapshots, as the switch says. */
  function apply() {
    const want = on() && (base === "info" || base === "warn" || base === "error") ? "debug" : base;
    deps.logMod.setLevel(want);
    clearInterval(timer); timer = null;
    if (on()) { timer = setInterval(snapshot, SNAPSHOT_MS); if (timer.unref) timer.unref(); snapshot(); }
  }

  /** A line of what the server is and holds now: its memory, Lyrion, the players, the phones. */
  function snapshot() {
    try {
      const s = deps.state() || {};
      const m = process.memoryUsage();
      const players = (s.players || []).map((/** @type {any} */ p) => `${p.name} (${p.model || "?"}${p.connected === false ? ", offline" : ""})`).join(", ");
      log.info(`[snapshot] v${deps.version} up ${Math.round(process.uptime() / 60)} min, memory ${Math.round(m.rss / 1048576)} MB; ` +
        `Lyrion ${s.connected ? "connected" : "not connected"}${s.server && s.server.version ? " " + s.server.version : ""}; players: ${players || "none"}`);
    } catch (e) { /* a snapshot must never fail anything */ }
  }

  app.get("/api/diagnostics", (/** @type {any} */ req, /** @type {any} */ res) => res.json({ enabled: on() }));

  app.post("/api/diagnostics", (/** @type {any} */ req, /** @type {any} */ res) => {
    const want = !!(req.body || {}).enabled;
    deps.features.setEnabled(FEATURE, want);
    log.info(`diagnostic logging switched ${want ? "on" : "off"}`);
    apply();
    res.json({ enabled: on() });
  });

  // Mallard's lines (AppLog → LogSend): each as it was said, with its own time and level, under the phone's name.
  app.post("/api/diagnostics/app", (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!on()) return res.json({ ok: false, off: true });
    const b = req.body || {};
    const text = String(b.text || "");
    if (text.length > APP_MAX) return res.status(413).json({ error: "too much at once" });
    const who = deps.logMod.makeLogger("app " + String(b.device || "phone").replace(/[^\w .'-]/g, "").slice(0, 40));
    for (const raw of text.split("\n")) {
      if (!raw.trim()) continue;
      const line = scrub(raw);
      if (/\b(ERROR|FATAL)\b/.test(line.slice(0, 40))) who.error(line); else if (/\bWARN\b/.test(line.slice(0, 40))) who.warn(line); else who.info(line);
    }
    res.json({ ok: true });
  });

  // The web page's errors (public/diag-log.js): a few a minute at most, as Mandarin's page-errors.
  let pageWindow = 0, pageCount = 0;
  app.post("/api/diagnostics/page", (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!on()) return res.json({ ok: false, off: true });
    const t = now();
    if (t - pageWindow > 60000) { pageWindow = t; pageCount = 0; }
    const lines = Array.isArray((req.body || {}).lines) ? req.body.lines.slice(0, 20) : [];
    const ua = String(req.get("user-agent") || "");
    const browser = /iPhone|iPad/.test(ua) ? "iOS" : /Android/.test(ua) ? "Android" : /Mac OS/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : "browser";
    const who = deps.logMod.makeLogger("web " + browser);
    for (const l of lines) {
      if (++pageCount > 600) break;
      const text = scrub(String((l && l.text) || "").slice(0, 2000));
      if (!text) continue;
      if (l && l.level === "warn") who.warn(text); else who.error(text);
    }
    res.json({ ok: true });
  });

  /** The tail of [text] at most [max] characters long, from a whole line. @param {string} text @param {number} max */
  const tail = (text, max) => { if (text.length <= max) return text; const cut = text.slice(text.length - max); return cut.slice(cut.indexOf("\n") + 1); };

  /** One of Lyrion's own logs, read from its web server ("server" or "scanner"), or why it couldn't be. @param {string} which */
  async function lyrionLog(which) {
    const l = deps.lms();
    if (!l || !l.cfg) return "(not connected to Lyrion)";
    const url = `http://${l.cfg.host}:${l.cfg.port}/${which}.log?full=1`;
    try {
      const text = deps.fetch ? await deps.fetch(url) : await get(url, l.cfg.username ? { user: l.cfg.username, pass: l.cfg.password || "" } : null);
      return tail(text, LYRION_MAX);
    } catch (e) { return `(Lyrion's ${which}.log couldn't be read: ${/** @type {Error} */ (e).message})`; }
  }

  app.get("/api/diagnostics/log", async (/** @type {any} */ req, /** @type {any} */ res) => {
    const s = deps.state() || {};
    const out = [];
    const stamp = new Date(now());
    out.push("MusicD LMS Remote — diagnostic log", `Saved ${stamp.toISOString()}`, `Server v${deps.version}, Node ${process.version}, ${os.platform()} ${os.arch()}, up ${Math.round(process.uptime() / 60)} min`,
      `Diagnostic logging: ${on() ? "on" : "off (switch it on in Settings → System for a fuller log)"}`,
      `Lyrion: ${s.connected ? "connected" : "not connected"}${s.server && s.server.version ? ", version " + s.server.version : ""}`,
      "Players: " + ((s.players || []).map((/** @type {any} */ p) => `${p.name} [${p.id}] ${p.model || ""}${p.connected === false ? " (offline)" : ""}`).join("; ") || "none"));
    const outages = deps.outages ? deps.outages() : [];
    if (outages.length) out.push("Lyrion stopped answering: " + outages.map((/** @type {any} */ o) => `${o.from} for ${o.seconds} s (${o.error})`).join("; "));
    out.push("Lines are without what is yours alone: keys, passwords, emails, internet and Tailscale addresses. Read it before you send it.", "");
    // This server's log, newest kept when it is long.
    const files = deps.logMod.files();
    /** @type {string[]} */
    const parts = [];
    let budget = OWN_MAX;
    for (let i = files.length - 1; i >= 0 && budget > 0; i--) {
      let text;
      try { text = fs.readFileSync(files[i], "utf8"); } catch (e) { continue; }
      text = tail(text, budget);
      budget -= text.length;
      parts.unshift(`===== MusicD LMS Remote: ${path.basename(files[i])} =====\n` + text);
    }
    out.push(...(parts.length ? parts : ["===== MusicD LMS Remote =====\n(no log file: LOG_FILE is off)"]));
    for (const which of ["server", "scanner"]) out.push(`===== Lyrion: ${which}.log =====\n` + await lyrionLog(which));
    const body = out.join("\n").split("\n").map(scrub).join("\n") + "\n";
    const name = "musicd-lms-remote-log-" + stamp.toISOString().slice(0, 16).replace(/[-:T]/g, "") + ".txt";
    log.info(`diagnostic log downloaded (${Math.round(body.length / 1024)} KB)`);
    res.set({ "Content-Type": "text/plain; charset=utf-8", "Content-Disposition": `attachment; filename="${name}"`, "Cache-Control": "no-store, no-transform" });
    res.send(body);
  });

  apply();
  return { enabled: on, snapshot };
}

/**
 * A GET's body as text, with a sign-in when given, within 20 s. @param {string} url
 * @param {{ user: string, pass: string }|null} auth @returns {Promise<string>}
 */
function get(url, auth) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const headers = /** @type {Record<string, string>} */ ({});
    if (auth) headers.Authorization = "Basic " + Buffer.from(auth.user + ":" + auth.pass).toString("base64");
    const req = http.get({ host: u.hostname, port: u.port, path: u.pathname + u.search, headers, timeout: 20000 }, (r) => {
      if ((r.statusCode || 0) >= 400) { r.resume(); return reject(new Error("HTTP " + r.statusCode)); }
      const chunks = /** @type {Buffer[]} */ ([]);
      r.on("data", (d) => chunks.push(d));
      r.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
      r.on("error", reject);
    });
    req.on("timeout", () => req.destroy(new Error("no answer in 20 s")));
    req.on("error", reject);
  });
}

module.exports = { mountDiagnostics, FEATURE };
