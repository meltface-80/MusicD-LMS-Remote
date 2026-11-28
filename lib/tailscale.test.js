// @ts-check
"use strict";
/*
 * The built-in Tailscale (lib/tailscale.js, lib/tailscale-routes.js) against a stand-in engine: a script that speaks
 * the real engine's control API (musicdnet/main.go) — CONTROL <port> first, X-Secret on every call, /start, /status,
 * /serve, /login, /logout — so the manager's handling of it is tested without a tailnet.
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const { makeTailscale } = require("./tailscale");
const { tailnetRange, tailnetCaller, letIn } = require("./tailscale-routes");
let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

ok("Tailscale's IPv4 range is the tailnet", tailnetRange("100.64.0.1") && tailnetRange("100.127.255.254") && !tailnetRange("100.128.0.1") && !tailnetRange("192.168.1.5"));
ok("Tailscale's IPv6 range is the tailnet", tailnetRange("fd7a:115c:a1e0::1") && !tailnetRange("fe80::1"));
ok("the caller is the last X-Forwarded-For entry", tailnetCaller({ headers: { "x-forwarded-for": "100.1.1.1, 100.101.102.103" } }) === "100.101.102.103");
ok("an IPv4-mapped caller is read as IPv4", tailnetCaller({ headers: { "x-forwarded-for": "::ffff:100.70.0.2" } }) === "100.70.0.2");
const own = new Set(["100.70.0.2"]);
ok("once the engine names the owner's nodes, only those get in", letIn("100.70.0.2", own) && !letIn("100.70.0.3", own));
ok("before it has, only Tailscale's ranges", letIn("100.70.0.3", null) && !letIn("192.168.1.9", null) && !letIn("", null));

// The stand-in engine.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ts-test-"));
const bin = path.join(dir, "engine.js");
fs.writeFileSync(bin, `#!/usr/bin/env node
const http = require("http");
let st = { state: "NeedsLogin" }, calls = [];
const s = http.createServer((req, res) => {
  if (req.headers["x-secret"] !== process.env.MUSICDNET_SECRET) { res.writeHead(403); return res.end('{"error":"bad secret"}'); }
  let b = ""; req.on("data", (d) => b += d); req.on("end", () => {
    const u = new URL(req.url, "http://x"); calls.push(req.method + " " + u.pathname + (b ? " " + b : "") + (u.search || ""));
    if (u.pathname === "/start") { const j = JSON.parse(b); if (j.AuthKey) st = { state: "Running", ips: ["100.80.0.5", "fd7a:115c:a1e0::5"], dns_name: "musicd-lms.tail.ts.net.", own_ips: ["100.70.0.2"] }; }
    if (u.pathname === "/login") st = { state: "NeedsLogin", auth_url: "https://login.tailscale.com/a/x" };
    if (u.pathname === "/serve") st.serving = true;
    if (u.pathname === "/calls") return res.end(JSON.stringify(calls));
    res.end(JSON.stringify(st));
  });
});
s.listen(0, "127.0.0.1", () => { console.log("CONTROL " + s.address().port); process.stdout.write(""); });
process.stdin.on("end", () => process.exit(0)); process.stdin.resume();
console.error("env authkey:" + (process.env.TS_AUTHKEY || "none") + " home:" + process.env.HOME);
`);
fs.chmodSync(bin, 0o755);
const lines = [];
const log = { info: (...a) => lines.push(a.join(" ")), warn: (...a) => lines.push(a.join(" ")), debug: (...a) => lines.push("debug " + a.join(" ")) };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (f, ms = 8000) => { const end = Date.now() + ms; while (Date.now() < end) { if (await f()) return true; await wait(100); } return false; };

(async () => {
  const off = makeTailscale({ dir: path.join(dir, "s0"), port: 3390, upstream: () => 4444, bin, log, settingsFile: path.join(dir, "x.json"), env: { TAILSCALE: "off" } });
  ok("TAILSCALE=off leaves it out", !off.available());
  const none = makeTailscale({ dir: path.join(dir, "s0"), port: 3390, upstream: () => 4444, bin: path.join(dir, "missing"), log, settingsFile: path.join(dir, "x.json"), env: {} });
  ok("no engine in this install, nothing available", !none.available() && none.status().available === false);

  const ts = makeTailscale({ dir: path.join(dir, "state"), port: 3390, upstream: () => 4444, bin, log, settingsFile: path.join(dir, "ts.json"),
    env: { PATH: process.env.PATH, TS_AUTHKEY: "tskey-abc", TS_HOSTNAME: "my-server" } });
  ok("an install with the engine has it, switched on by default", ts.available() && ts.enabled());
  await ts.start();
  ok("joined with the auth key and serving: an address on the tailnet", await until(() => ts.address() === "http://100.80.0.5:3390"));
  const st = ts.status();
  ok("the status the apps draw", st.state === "Running" && st.serving && st.dns_name === "musicd-lms.tail.ts.net" && st.hostname === "my-server");
  ok("the owner's own nodes, as the engine names them", ts.ownIps()?.has("100.70.0.2") === true);
  ok("the auth key is passed through the API, never inherited by the engine", lines.some((l) => /env authkey:none/.test(l)) && lines.some((l) => /home:.*state/.test(l)));
  ok("the tailnet's port is served to the server's own loopback port", lines.some((l) => /on your tailnet: http:\/\/100\.80\.0\.5:3390/.test(l)));
  const signin = await ts.login();
  ok("a sign-in link", signin.auth_url === "https://login.tailscale.com/a/x" && signin.address === null);
  const after = await ts.setEnabled(false);
  ok("switched off: stopped, and kept off", after.running === false && !ts.enabled() && JSON.parse(fs.readFileSync(path.join(dir, "ts.json"), "utf8")).enabled === false);
  ts.stop();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`\n${pass}/${pass + fail} tailscale tests passed.`);
  if (fail) process.exit(1);
  process.exit(0);
})();
