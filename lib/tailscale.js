// @ts-check
"use strict";
/*
 * tailscale.js — Tailscale built into the server (away from home, bug-list item 14), ported rule for rule from
 * Mandarin's server (Admin/Tailscale.cs, itself from its Node tsnode.js).
 *
 * The engine is MusicD's own (musicdnet/, a Go program on tailscale.com/tsnet, built into the Docker image at
 * /usr/local/bin/musicdnet). Signed in once — from Settings → Away from home, or with TS_AUTHKEY — it makes this server a
 * machine on the owner's tailnet by itself: no Tailscale on the host, no VPN, no open ports. It takes the tailnet's port
 * 3390 and passes each request to this server on a loopback port of its own, naming the caller's tailnet address in
 * X-Forwarded-For (lib/tailscale-routes.js decides who may come in that way).
 *
 * The engine is driven over HTTP on 127.0.0.1 with a secret only the two know (MUSICDNET_SECRET); it prints
 * "CONTROL <port>" first, and stops when its stdin closes. Its state lives in data/tailscale, so an update or a new
 * container on the same volume is the same machine. TAILSCALE=off leaves it out. A node that says Running but isn't
 * online is asked to rebind, then started afresh.
 */
const { spawn } = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const POLL_MS = 5000;

/**
 * @typedef {{ state?: string, auth_url?: string, ips?: string[], dns_name?: string, serving?: boolean, online?: boolean,
 *   health?: string[], error?: string, version?: string, own_ips?: string[] }} EngineStatus
 */

/**
 * @param {{ dir: string, port: number, upstream: () => number, bin?: string, env?: NodeJS.ProcessEnv, log: any,
 *   settingsFile: string, rebindMs?: number, restartMs?: number, fetch?: typeof globalThis.fetch }} o
 */
function makeTailscale(o) {
  const env = o.env || process.env;
  const bin = o.bin || env.MUSICDNET_BIN || "/usr/local/bin/musicdnet";
  const rebindMs = o.rebindMs || 60000, restartMs = o.restartMs || 240000;
  const fetchImpl = o.fetch || globalThis.fetch;
  /** @type {import("child_process").ChildProcess|null} */
  let proc = null;
  /** @type {number|null} */
  let ctl = null;
  let secret = "";
  /** @type {EngineStatus|null} */
  let last = null;
  /** @type {string|null} */
  let error = null;
  let stopping = false, restarts = 0;
  /** @type {NodeJS.Timeout|null} */
  let timer = null;
  /** @type {number|null} */
  let badSince = null;
  let rebound = 0;

  const executable = () => { try { fs.accessSync(bin, fs.constants.X_OK); return true; } catch (e) { return false; } };
  const hostname = () => String(env.TS_HOSTNAME || "").trim() || "musicd-lms";
  const available = () => String(env.TAILSCALE || "").toLowerCase() !== "off" && executable();
  /** Switched on unless the owner switched it off (Settings → Away from home). */
  function enabled() {
    try { return JSON.parse(fs.readFileSync(o.settingsFile, "utf8")).enabled !== false; } catch (e) { return true; }
  }
  const trimDot = (/** @type {string} */ s) => s.replace(/\.$/, "");

  /** "http://100.x.y.z:3390" once it has joined and serves, else null. */
  function address() {
    if (!proc || !last || last.state !== "Running" || !last.serving) return null;
    const ip = (last.ips || []).find((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a));
    return ip ? `http://${ip}:${o.port}` : null;
  }

  /** For the apps: what it is doing, and the sign-in link while one is needed. */
  function status() {
    const st = last || {};
    const running = !!proc;
    return {
      available: available(), enabled: enabled(), running,
      state: running ? (st.state || "Starting") : "Stopped",
      auth_url: st.auth_url || null, ips: Array.isArray(st.ips) ? st.ips : [],
      dns_name: st.dns_name ? trimDot(st.dns_name) : null, serving: !!st.serving,
      online: st.online === undefined ? null : !!st.online, health: Array.isArray(st.health) ? st.health : [],
      address: address(), error: error || st.error || null, version: st.version || null, hostname: hostname(),
    };
  }

  /** The addresses of the owner's own nodes, while the engine runs and says them (Mandarin item 370); null otherwise. */
  function ownIps() {
    if (!proc || !last || last.state !== "Running" || !Array.isArray(last.own_ips)) return null;
    return new Set(last.own_ips.map((a) => String(a).toLowerCase()));
  }

  /** The engine's control API: JSON in and out, the secret in X-Secret. @param {string} method @param {string} pq @param {any} body @param {number} ms */
  async function call(method, pq, body, ms) {
    if (ctl == null) throw new Error("not running");
    const r = await fetchImpl(`http://127.0.0.1:${ctl}${pq}`, {
      method, headers: { "X-Secret": secret, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(ms),
    });
    /** @type {any} */
    let j = {};
    try { j = await r.json(); } catch (e) { /* no body */ }
    if (!r.ok) throw new Error((j && j.error) || "HTTP " + r.status);
    return j;
  }

  async function start() {
    if (proc || !available() || !enabled()) return;
    stopping = false;
    fs.mkdirSync(o.dir, { recursive: true, mode: 0o700 });
    secret = crypto.randomBytes(24).toString("hex");
    /** @type {NodeJS.ProcessEnv} */
    const childEnv = { ...env, MUSICDNET_SECRET: secret, HOME: o.dir };
    delete childEnv.TS_AUTHKEY;   // given through its API, not inherited
    /** @type {import("child_process").ChildProcess} */
    let p;
    try { p = spawn(bin, ["-server", "-dir", o.dir], { env: childEnv, stdio: ["pipe", "pipe", "pipe"] }); }
    catch (e) { error = /** @type {Error} */ (e).message; return; }
    proc = p;
    /** @type {string[]} */
    const tail = [];
    const first = new Promise((resolve, reject) => {
      let buf = "";
      p.stdout?.on("data", (d) => {
        if (buf === null) return;
        buf += d;
        const i = buf.indexOf("\n");
        if (i >= 0) { resolve(buf.slice(0, i).trim()); buf = /** @type {any} */ (null); }
      });
      p.on("error", reject);
      p.on("exit", () => reject(new Error("the engine didn't start")));
    });
    first.catch(() => { /* said below */ });
    let pending = "";
    p.stderr?.on("data", (d) => {
      pending += d;
      const lines = pending.split("\n"); pending = lines.pop() || "";
      for (const l of lines) { if (!l.trim()) continue; o.log.debug("engine:", l); tail.push(l); if (tail.length > 20) tail.shift(); }
    });
    p.on("exit", (code) => {
      if (proc !== p) return;   // an engine let go of before (a restart): its leaving touches nothing (Mandarin item 181)
      proc = null; ctl = null;
      if (timer) clearTimeout(timer);
      if (stopping) return;
      const said = tail.join(" ").trim().slice(-300);
      error = `stopped (${code})${said ? ": " + said : ""}`;
      o.log.warn(error);
      const wait = Math.min(60000, 2000 * (1 << Math.min(restarts++, 5)));   // more slowly each time it fails
      timer = setTimeout(() => { start(); }, wait);
    });
    try {
      const line = String(await Promise.race([first, new Promise((_, rej) => setTimeout(() => rej(new Error("the engine didn't start")), 10000))]));
      const m = /^CONTROL (\d+)/.exec(line);
      if (!m) throw new Error("unexpected first line: " + line);
      if (proc === p) ctl = Number(m[1]);
      error = null;
      await call("POST", "/start", { Hostname: hostname(), AuthKey: env.TS_AUTHKEY || "", ControlURL: env.TS_CONTROL_URL || "" }, 30000);
      o.log.info(`built-in Tailscale started as "${hostname()}"`);
    } catch (e) {
      error = /** @type {Error} */ (e).message;
      o.log.warn("couldn't start:", error);
    }
    poll();
  }

  /** Every few seconds: how it is, and once joined, serving this server's port. */
  async function poll() {
    if (timer) clearTimeout(timer);
    if (!proc || stopping) return;
    try {
      const st = await call("GET", "/status", null, 4000);
      const was = last && last.state;
      last = st;
      if (st.state === "Running" && !st.serving) {
        await call("POST", `/serve?port=${o.port}&upstream=${encodeURIComponent("http://127.0.0.1:" + o.upstream())}`, null, 10000);
        last = await call("GET", "/status", null, 4000);
        restarts = 0;
        o.log.info(`on your tailnet: ${address() || "(no address yet)"}${last && last.dns_name ? " — " + trimDot(last.dns_name) : ""}`);
      } else if (st.state !== was && st.state === "NeedsLogin") {
        o.log.info("waiting to be signed in: Settings → Away from home");
      }
      if (await watch(st)) return;
    } catch (e) { /* asked again shortly */ }
    if (!proc || stopping) return;
    timer = setTimeout(poll, POLL_MS);
  }

  /** The watchdog: true when the engine was started afresh (the poll is start()'s then). @param {EngineStatus} st */
  async function watch(st) {
    const health = Array.isArray(st.health) ? st.health : [];
    const bad = st.state === "Running" && (st.online === false || health.length > 0);
    if (!bad) { badSince = null; rebound = 0; return false; }
    const now = Date.now();
    if (badSince == null) { badSince = now; return false; }
    const why = st.online === false ? "not online on the tailnet" : "Tailscale warns: " + health.join("; ");
    if (rebound && now - rebound >= restartMs) {
      o.log.warn(`${why} for ${Math.round((now - badSince) / 1000)} s after a rebind: starting the engine afresh`);
      badSince = null; rebound = 0;
      await restart();
      return true;
    }
    if (!rebound && now - badSince >= rebindMs) {
      rebound = now;
      o.log.warn(`${why} for ${Math.round((now - badSince) / 1000)} s: asking Tailscale to rebind`);
      try { await call("POST", "/rebind", null, 12000); } catch (e) { o.log.warn("rebind:", /** @type {Error} */ (e).message); }
    }
    return false;
  }

  /** A sign-in link (a new one after signing out, or when a key has expired). */
  async function login() {
    if (!proc) await start();
    if (!proc) throw Object.assign(new Error(available() ? "Tailscale is switched off" : "Tailscale isn't in this install"), { status: 409 });
    const st = await call("POST", "/login", null, 20000);
    last = { ...(last || {}), ...st };
    return status();
  }

  async function logout() {
    if (!proc) return status();
    await call("POST", "/logout", null, 15000);
    await restart();   // started afresh, it asks to be signed in again
    return status();
  }

  /** @param {boolean} on */
  async function setEnabled(on) {
    fs.mkdirSync(path.dirname(o.settingsFile), { recursive: true });
    fs.writeFileSync(o.settingsFile, JSON.stringify({ enabled: !!on }));
    if (on) await start(); else stop();
    return status();
  }

  async function restart() { stop(); await new Promise((r) => setTimeout(r, 300)); await start(); }

  function stop() {
    stopping = true;
    if (timer) clearTimeout(timer);
    const p = proc;
    proc = null; ctl = null; last = null;
    if (!p) return;
    try { p.stdin?.end(); } catch (e) { /* gone */ }
    setTimeout(() => { try { if (p.exitCode === null) p.kill(); } catch (e) { /* gone */ } }, 1500).unref();
  }

  return { start, stop, status, address, ownIps, login, logout, setEnabled, available, enabled };
}

module.exports = { makeTailscale };
