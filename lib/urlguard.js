"use strict";

// Bounded SSRF guard for the few endpoints that fetch a USER-SUPPLIED URL
// server-side (album-edit art_url, label-logo url). It rejects targets that
// resolve to loopback / private / link-local / ULA space so a caller can't
// point the server at 169.254.169.254 (cloud metadata) or an internal service.
// It guards the request TARGET; a follow-on HTTP redirect to a private address
// is a residual gap (the initial resolve is what defeats internal DNS names).
const dns = require("dns").promises;
const net = require("net");

function isPrivateIPv4(ip) {
  const p = ip.split(".").map(Number);
  if (p.length !== 4 || p.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return true; // malformed → unsafe
  const [a, b] = p;
  if (a === 0 || a === 10 || a === 127) return true;      // "this host", private, loopback
  if (a === 169 && b === 254) return true;                // link-local (incl. 169.254.169.254 metadata)
  if (a === 172 && b >= 16 && b <= 31) return true;       // private
  if (a === 192 && b === 168) return true;                // private
  if (a === 100 && b >= 64 && b <= 127) return true;      // CGNAT
  if (a >= 224) return true;                              // multicast / reserved
  return false;
}

function isPrivateIPv6(ip) {
  const s = ip.toLowerCase().split("%")[0];               // strip zone id
  if (s === "::1" || s === "::") return true;             // loopback / unspecified
  if (s.startsWith("fe80")) return true;                  // link-local
  if (s.startsWith("fc") || s.startsWith("fd")) return true; // unique-local
  const m = s.match(/::ffff:(\d+\.\d+\.\d+\.\d+)$/);      // IPv4-mapped
  if (m) return isPrivateIPv4(m[1]);
  return false;
}

function ipIsPrivate(ip) {
  const v = net.isIP(ip);
  if (v === 4) return isPrivateIPv4(ip);
  if (v === 6) return isPrivateIPv6(ip);
  return true;                                            // not an IP → unsafe
}

// Resolves and validates `raw`. Returns the parsed URL on success; throws with
// a short reason otherwise. Async because a hostname must be resolved to catch
// public names that map onto internal IPs.
async function assertPublicUrl(raw) {
  let u;
  try { u = new URL(String(raw)); } catch (e) { throw new Error("invalid URL"); }
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("only http/https URLs are allowed");
  const host = u.hostname.replace(/^\[|\]$/g, "");        // unwrap [::1]
  if (net.isIP(host)) {
    if (ipIsPrivate(host)) throw new Error("URL points to a private/loopback address");
    return u;
  }
  let addrs;
  try { addrs = await dns.lookup(host, { all: true }); }
  catch (e) { throw new Error("could not resolve host"); }
  if (!addrs.length) throw new Error("could not resolve host");
  for (const a of addrs) if (ipIsPrivate(a.address)) throw new Error("URL resolves to a private/loopback address");
  return u;
}

module.exports = { assertPublicUrl, ipIsPrivate, isPrivateIPv4, isPrivateIPv6 };
