// @ts-check
"use strict";
/*
 * LMS's own settings, for both apps: which settings page to frame (/api/lms/settings-info, moved out of index.js),
 * and LMS's plugins — listed, installed, updated, removed, switched on and off, and LMS restarted to apply them
 * (/api/lms/plugins…, lib/lmsplugins.js). Owner request: "allow adding plugins and that they work".
 *
 * Every plugin route acts on a plugin BY NAME, and only on one LMS's own page lists: the download address and
 * checksum stay in LMS (Plugins.pm installs from its repository entry), so no client can make LMS fetch anything.
 */
const { makePlugins } = require("./lmsplugins");

/** @typedef {{ cfg: { host: string, port: number|string, username?: string, password?: string },
 *   serverStatus: () => Promise<{ scanning?: boolean }>, request: (pid: string, words: any[]) => Promise<any> }} Lms */

const ACTIONS = new Set(["install", "update", "remove", "enable", "disable"]);
const NAME_RE = /^[A-Za-z0-9_.-]{1,80}$/;   // LMS plugin names are Perl package parts

/**
 * @param {any} app Express app
 * @param {{ lms: () => Lms|null, notConnected: (res: any) => any, log: any, fetch?: typeof globalThis.fetch }} deps
 */
function mountLmsSettings(app, deps) {
  const fetchImpl = deps.fetch || globalThis.fetch;
  const auth = (/** @type {Lms["cfg"]} */ cfg) => (cfg.username
    ? { authorization: "Basic " + Buffer.from(`${cfg.username}:${cfg.password || ""}`).toString("base64") } : {});

  // Which settings page to frame, served SAME-ORIGIN through index.js's LMS proxy (as Material frames LMS's own):
  // Material's styled page when the plugin is installed, LMS's classic settings otherwise. Probed at most every
  // 10 minutes, and again straight after any plugin change, since installing Material is one.
  /** @type {{ at: number, material: boolean }|null} */
  let probe = null;
  app.get("/api/lms/settings-info", async (/** @type {any} */ req, /** @type {any} */ res) => {
    const lms = deps.lms();
    if (!lms) return deps.notConnected(res);
    const cfg = lms.cfg;
    if (!probe || (Date.now() - probe.at) > 10 * 60 * 1000) {
      let material = false;
      try {
        const r = await fetchImpl(`http://${cfg.host}:${cfg.port}/material/settings/server/basic.html`,
          { headers: auth(cfg), signal: AbortSignal.timeout(4000), redirect: "manual" });
        material = r.status === 200;
      } catch (e) { /* Material not installed / unreachable — classic it is */ }
      probe = { at: Date.now(), material };
    }
    let scanning = false;
    try { scanning = !!(await lms.serverStatus()).scanning; } catch (e) { /* best-effort */ }
    res.json({
      host: cfg.host, port: cfg.port, material: probe.material, scanning,
      // A path on THIS app's origin, so the browser never needs to reach the LMS host itself.
      settings_path: probe.material ? "/material/settings/server/basic.html" : "/settings/index.html",
    });
  });

  const plugins = makePlugins({ cfg: () => { const l = deps.lms(); return l ? l.cfg : null; }, fetch: fetchImpl });
  /** @param {any} res @param {any} e */
  const fail = (res, e) => {
    deps.log.debug("plugins:", e && e.message);
    if (e && e.risk) return res.status(409).json({ error: e.message, risk: true });
    const status = e && (e.status === 401 || e.status === 403) ? 502 : e && /^(No update|LMS doesn't list|Unknown action)|can't be|part of LMS/.test(e.message) ? 400 : 502;
    res.status(status).json({ error: (e && e.message) || "LMS didn't answer" });
  };
  // One change at a time: each is a read-then-post of LMS's whole page, and LMS waits on downloads before answering.
  let busy = Promise.resolve();
  /** @template T @param {() => Promise<T>} fn @returns {Promise<T>} */
  const serial = (fn) => { const run = busy.then(fn, fn); busy = run.then(() => undefined, () => undefined); return run; };

  app.get("/api/lms/plugins", async (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    try { res.json(await plugins.list()); } catch (e) { fail(res, e); }
  });

  // LMS's "plugins are third-party code" warning, accepted for a year — only ever on the person's own tap in an app
  // that has shown them what it says, which is why the request has to say so.
  app.post("/api/lms/plugins/accept-risk", async (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    if (!req.body || req.body.accept !== true) return res.status(400).json({ error: "accept:true required" });
    try { res.json(await serial(() => plugins.acceptRisk())); } catch (e) { fail(res, e); }
  });

  app.post("/api/lms/plugins", async (/** @type {any} */ req, /** @type {any} */ res) => {
    if (!deps.lms()) return deps.notConnected(res);
    const { name, action } = req.body || {};
    if (typeof name !== "string" || !NAME_RE.test(name)) return res.status(400).json({ error: "name required" });
    if (!ACTIONS.has(action)) return res.status(400).json({ error: "action must be one of " + [...ACTIONS].join(", ") });
    try {
      const out = await serial(() => plugins.change(name, action));
      probe = null;
      deps.log.info(`plugin ${name}: ${action} — LMS ${out.restart ? "needs a restart" : "took it"}`);
      res.json(out);
    } catch (e) { fail(res, e); }
  });

  // LMS restarts itself (restartserver, as Material's Restart button). It often drops the connection before it
  // answers, which is the restart happening, not a failure; the server's own 2.5 s poll reconnects.
  app.post("/api/lms/restart", async (/** @type {any} */ req, /** @type {any} */ res) => {
    const lms = deps.lms();
    if (!lms) return deps.notConnected(res);
    try { await lms.request("", ["restartserver"]); } catch (e) { deps.log.debug("restartserver:", e && e.message); }
    probe = null;
    deps.log.info("asked LMS to restart");
    res.json({ ok: true });
  });
}

module.exports = { mountLmsSettings };
