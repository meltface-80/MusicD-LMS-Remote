// @ts-check
"use strict";
/*
 * scrub.js — a log line without what is the owner's alone, for the diagnostic log (lib/diagnostics.js): the server's
 * twin of Mallard's LogRedact.kt (and Mandarin's LogScrub.cs), the same rules, so a line hides the same things whichever
 * side wrote it. Keep the two in step.
 *
 * Hidden: keys, tokens and passwords ("token=[…]", "Bearer […]", a URL's login, a run of 32 or more letters and figures
 * that isn't a UUID); email addresses ([email]); internet (WAN) addresses ([wan]); Tailscale's addresses, 100.64.0.0/10
 * and fd7a:115c:a1e0::/48, and its names, *.ts.net ([tailscale]); the user's own folder in a path (/home/[user]/…).
 * Kept: everything else — home-network addresses, rooms' and devices' names and ids, what was played, files, errors.
 */

const HIDDEN = "[…]";
const URL_LOGIN = /(:\/\/)[^/\s:@]+:[^/\s@]+@/g;
const BEARER = /\b(Bearer|Basic)\s+[^\s&,;]+/g;
const SECRET = /\b([\w-]*(?:token|password|passwd|pwd|secret|apikey|api[_-]key|sig|signature|authorization|cookie))(\s*[=:]\s*)(?!\[…\])[^\s&,;]+/gi;
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const LONG = /\b[A-Za-z0-9_-]{32,}\b/g;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const IPV4 = /(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?:\/\d{1,2})?(?![\w.])/g;
const IPV6 = /(?<![\w:])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?)(?:%[\w.-]+)?(?![\w:.])/g;
const TAILNET = /\b[\w-]+(?:\.[\w-]+)*\.ts\.net\b/g;
const HOME = /(?<![\w.])(\/home\/|\/Users\/|[A-Za-z]:\\Users\\)[^/\\\s]+/g;

/** An IPv4 address's kind when it is hidden ("[wan]", "[tailscale]"), or null when it is kept. @param {string} s */
function v4(s) {
  const o = s.split("/")[0].split(".").map(Number);
  if (o.some((n) => n > 255)) return null;
  const [a, b] = o;
  const home = a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) || (a === 192 && b === 0 && o[2] === 0);
  return home ? null : a === 100 && b >= 64 && b <= 127 ? "[tailscale]" : "[wan]";
}

/** An IPv6 address's kind when it is hidden, or null when it is kept. @param {string} s */
function v6(s) {
  const t = s.toLowerCase();
  if (t.startsWith("fd7a:115c:a1e0:")) return "[tailscale]";
  const head = t.split(":")[0];
  if (!head) return null;
  const n = parseInt(head, 16);
  return n >= 0x2000 && n <= 0x3fff ? "[wan]" : null;
}

/** [s] without what is the owner's alone. @param {string} s */
function scrub(s) {
  let t = String(s).replace(URL_LOGIN, (_m, a) => a + HIDDEN + "@");
  t = t.replace(BEARER, (_m, a) => a + " " + HIDDEN);
  t = t.replace(SECRET, (_m, a, b) => a + b + HIDDEN);
  t = t.replace(EMAIL, "[email]");
  t = t.replace(LONG, (m) => (UUID.test(m) ? m : HIDDEN));
  t = t.replace(IPV4, (m) => v4(m) || m);
  t = t.replace(IPV6, (m) => v6(m) || m);
  t = t.replace(TAILNET, "[tailscale]");
  return t.replace(HOME, (_m, a) => a + "[user]");
}

module.exports = { scrub };
