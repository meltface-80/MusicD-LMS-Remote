// @ts-check
"use strict";
/*
 * tailscale-routes.js — away from home on the server (bug-list item 14): the built-in Tailscale engine
 * (lib/tailscale.js) started with the server, its way in, and the routes Settings → Away from home uses in both apps.
 *
 * THE WAY IN. The engine passes the tailnet's requests to a loopback port of their own (never the LAN port), so a
 * request here is known to have come over the tailnet, and its caller's tailnet address is the engine's
 * X-Forwarded-For (the engine sets it itself — Go's ReverseProxy Rewrite drops any the caller sent — and the LAST entry
 * is read regardless). This server has no sign-in, so the tailnet is let in only as far as the OWNER's devices: the
 * nodes signed in as the server's own Tailscale user, which the engine names (own_ips, Mandarin item 370 — 100.64/10 is
 * also carrier-grade NAT, and holds nodes shared in from other people's tailnets). Before the engine has said, only
 * Tailscale's own ranges get in.
 *
 *   GET  /api/tailscale                 status (and the sign-in link while one is needed)
 *   POST /api/tailscale/login           a sign-in link
 *   POST /api/tailscale/logout          signed out; started afresh, waiting to be signed in
 *   POST /api/tailscale/enable {on}     switched on or off (kept in data/tailscale-settings.json)
 */
const http = require("http");
const net = require("net");
const path = require("path");
const { makeTailscale } = require("./tailscale");

/** Whether [ip] is in Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48). @param {string} ip */
function tailnetRange(ip) {
  const v4 = ip.replace(/^::ffff:/i, "");
  if (net.isIPv4(v4)) { const [a, b] = v4.split(".").map(Number); return a === 100 && b >= 64 && b <= 127; }
  return /^fd7a:115c:a1e0:/i.test(ip);
}

/** The caller a tailnet request names: the last X-Forwarded-For entry, normalised. @param {any} req */
function tailnetCaller(req) {
  const all = String(req.headers["x-forwarded-for"] || "").split(",").map((s) => s.trim()).filter(Boolean);
  return (all[all.length - 1] || "").replace(/^::ffff:/i, "").toLowerCase();
}

/**
 * Whether a request on the engine's port may come in: one of the owner's own nodes, while the engine names them;
 * otherwise any address in Tailscale's own ranges. @param {string} ip @param {Set<string>|null} own
 */
function letIn(ip, own) {
  if (!ip) return false;
  return own ? own.has(ip) : tailnetRange(ip);
}

/**
 * @param {any} app Express app
 * @param {{ port: number, dataDir: string, log: any, env?: NodeJS.ProcessEnv }} deps
 */
function mountTailscale(app, deps) {
  let enginePort = 0;
  const ts = makeTailscale({
    dir: path.join(deps.dataDir, "tailscale"), port: deps.port, upstream: () => enginePort, log: deps.log,
    settingsFile: path.join(deps.dataDir, "tailscale-settings.json"), env: deps.env,
  });
  if (ts.available()) {
    const way = http.createServer((req, res) => {
      const ip = tailnetCaller(req);
      if (!letIn(ip, ts.ownIps())) {
        deps.log.info("tailnet request refused from", ip || "(no address)");
        res.writeHead(403, { "Content-Type": "text/plain" });
        res.end("This server only answers your own devices on your tailnet.");
        return;
      }
      /** @type {any} */ (req).viaTailnet = ip;
      app(req, res);
    });
    way.listen(0, "127.0.0.1", () => {
      const a = way.address();
      enginePort = a && typeof a === "object" ? a.port : 0;
      ts.start().catch((e) => deps.log.warn("start:", e.message));
    });
    way.unref();
    const stop = () => ts.stop();
    process.once("exit", stop);
  }

  /** @param {any} res @param {any} e */
  const fail = (res, e) => res.status((e && e.status) || 500).json({ error: (e && e.message) || "Tailscale didn't answer" });
  app.get("/api/tailscale", (/** @type {any} */ req, /** @type {any} */ res) => res.json(ts.status()));
  // The phone engine's probe (musicdnet/forward.go probe(): /api/ping, answered at once): Mallard's Tailscale asks it
  // across the tailnet to know the server is there before it takes the tunnel for its way to the server.
  app.get("/api/ping", (/** @type {any} */ req, /** @type {any} */ res) => res.json({ ok: true }));
  app.post("/api/tailscale/login", async (/** @type {any} */ req, /** @type {any} */ res) => {
    try { res.json(await ts.login()); } catch (e) { fail(res, e); }
  });
  app.post("/api/tailscale/logout", async (/** @type {any} */ req, /** @type {any} */ res) => {
    try { res.json(await ts.logout()); } catch (e) { fail(res, e); }
  });
  app.post("/api/tailscale/enable", async (/** @type {any} */ req, /** @type {any} */ res) => {
    const on = req.body && req.body.on;
    if (typeof on !== "boolean") return res.status(400).json({ error: "on: true or false" });
    try { res.json(await ts.setEnabled(on)); } catch (e) { fail(res, e); }
  });
  return ts;
}

module.exports = { mountTailscale, tailnetRange, tailnetCaller, letIn };
