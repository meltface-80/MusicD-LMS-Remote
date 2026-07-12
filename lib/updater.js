// ---------------------------------------------------------------------------
// Update NOTIFIER — checks GitHub for a newer release and nudges the user to
// rebuild their container. It deliberately does NOT self-update.
//
// WHY notify-not-apply: this app ships as a Docker image. The container
// filesystem is ephemeral and read-only-ish in practice — an in-app "download
// and swap the code" flow would be wiped on the next `docker run`, and can't
// touch the image the container was built from anyway. The correct upgrade path
// is `docker pull`/rebuild + recreate the container (see the README). So all we
// do here is compare versions and surface a "new version available" banner; the
// apply route in index.js intentionally returns an instructional 400.
//
// The GitHub REST API requires a User-Agent header on every request (it 403s
// otherwise); unauthenticated access is fine for public repos. Unauthenticated
// callers are limited to 60 requests/hour per IP, so we cache the last result
// and only re-check at most once an hour (see maybeCheck / CHECK_TTL_MS) with a
// single-flight guard so overlapping status polls can't fan out into a burst.
//
// Injected deps (owner/repo/currentVersion/debug) keep this file free of any
// dependency on index.js so it's unit-testable offline — the same pattern as
// lib/labels.js's makeLabels().
// ---------------------------------------------------------------------------
"use strict";

const CHECK_TTL_MS = 60 * 60 * 1000; // re-check GitHub at most hourly (60 req/hr unauth cap)
const FETCH_TIMEOUT_MS = 8000;       // deadline every request so a wedged endpoint can't hang
const NOTES_CAP = 600;               // release bodies can be huge — keep the banner readable

// Small semver-ish compare: split on ".", compare numeric parts left to right,
// missing parts count as 0, and any non-numeric suffix (e.g. "1.2.0-beta") is
// ignored. Returns >0 if a>b, <0 if a<b, 0 if equal. We only ever use this to
// answer "is the remote version newer than ours?" so exact pre-release ordering
// isn't needed — a coarse numeric compare is enough to decide whether to nudge.
function _cmp(a, b) {
  const pa = String(a || "").split(".");
  const pb = String(b || "").split(".");
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const na = parseInt(pa[i], 10) || 0;
    const nb = parseInt(pb[i], 10) || 0;
    if (na > nb) return 1;
    if (na < nb) return -1;
  }
  return 0;
}

// Strip a single leading "v" so a "v0.2.0" tag compares against a bare "0.2.0"
// package version.
function stripV(s) {
  return String(s || "").trim().replace(/^v/i, "");
}

function makeUpdater({ owner, repo, currentVersion, debug } = {}) {
  const current = stripV(currentVersion) || "0.0.0";

  // Last cached result — served synchronously by getStatus(). Seeded to a
  // "no update, never checked" state so the UI has something coherent before
  // the first network check completes.
  let cached = {
    available:  false,
    latest:     current,
    current:    current,
    notes:      null,
    url:        null,
    isDowngrade: false,
    checkedAt:  0,
    error:      null,
  };

  let inFlight = null; // single-flight promise so overlapping checks coalesce

  function log(msg) { if (debug) console.error("[updater] " + msg); }

  // One deadlined GitHub request. Returns parsed JSON, or null on any
  // non-2xx / network / abort — callers treat null as "this source had
  // nothing" and fall through to the next source.
  async function ghFetch(url) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), FETCH_TIMEOUT_MS);
    try {
      const res = await fetch(url, {
        signal: ctl.signal,
        headers: {
          // GitHub 403s requests with no User-Agent; unauthenticated is fine.
          "User-Agent": "MusicD-LMS-Remote-updater",
          "Accept": "application/vnd.github+json",
        },
      });
      if (!res.ok) { log(url + " -> HTTP " + res.status); return { _status: res.status }; }
      return await res.json();
    } catch (e) {
      log(url + " -> " + e.message);
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  // Resolve the newest published version from GitHub. Tries three sources in
  // priority order, stopping at the first that yields a version:
  //   1. latest release (richest — carries notes + a canonical html_url)
  //   2. tags list (covers repos that tag but don't cut formal releases)
  //   3. package.json on the default branch (last resort — always present)
  // Returns { latest, notes, url } or null if every source came up empty.
  async function resolveLatest() {
    const releasesUrl = "https://github.com/" + owner + "/" + repo + "/releases";

    // 1. Latest release.
    const rel = await ghFetch("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest");
    if (rel && rel.tag_name) {
      let notes = null;
      if (typeof rel.body === "string" && rel.body.trim()) {
        notes = rel.body.trim().slice(0, NOTES_CAP);
      }
      return { latest: stripV(rel.tag_name), notes, url: rel.html_url || releasesUrl };
    }

    // 2. Tags (fallback when there are no formal releases — 404 above).
    const tags = await ghFetch("https://api.github.com/repos/" + owner + "/" + repo + "/tags");
    if (Array.isArray(tags) && tags.length && tags[0] && tags[0].name) {
      return { latest: stripV(tags[0].name), notes: null, url: releasesUrl };
    }

    // 3. package.json on the default branch (final fallback).
    const pkgJson = await ghFetch("https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json");
    if (pkgJson && typeof pkgJson.version === "string") {
      return { latest: stripV(pkgJson.version), notes: null, url: releasesUrl };
    }

    return null;
  }

  // Force a check now. Never throws: on any network/parse/rate-limit failure we
  // keep available:false and record a short error string in the cache.
  async function checkNow() {
    if (inFlight) return inFlight; // coalesce concurrent callers onto one request
    inFlight = (async () => {
      try {
        const found = await resolveLatest();
        if (!found) {
          cached = {
            available: false, latest: current, current, notes: null, url: null,
            isDowngrade: false, checkedAt: Date.now(),
            error: "Couldn't reach GitHub to check for updates.",
          };
          return cached;
        }
        const latest = found.latest || current;
        // We only ever nudge forward, so isDowngrade stays false even if the
        // remote somehow reports an older version than we're running.
        const available = _cmp(latest, current) > 0;
        cached = {
          available,
          latest,
          current,
          notes: found.notes || null,
          url: found.url || null,
          isDowngrade: false,
          checkedAt: Date.now(),
          error: null,
        };
      } catch (e) {
        // Belt-and-braces: resolveLatest already swallows per-request errors,
        // but never let checkNow reject its callers.
        cached = {
          available: false, latest: current, current, notes: null, url: null,
          isDowngrade: false, checkedAt: Date.now(),
          error: "Update check failed: " + (e && e.message ? e.message : "unknown error"),
        };
      } finally {
        inFlight = null;
      }
      return cached;
    })();
    return inFlight;
  }

  // Return the last cached result synchronously (the HTTP status route serves
  // this immediately and kicks a background maybeCheck()).
  function getStatus() {
    return cached;
  }

  // Fire a check only if it's been more than an hour since the last one and no
  // check is already running — respects GitHub's 60 req/hr unauthenticated cap.
  // Fire-and-forget: callers don't await it (the status route returns the cache).
  async function maybeCheck() {
    if (inFlight) return;                                   // already running
    if (Date.now() - cached.checkedAt < CHECK_TTL_MS) return; // checked recently
    checkNow().catch(() => { /* checkNow never throws, but be safe */ });
  }

  return { getStatus, checkNow, maybeCheck, _cmp };
}

module.exports = { makeUpdater, _cmp };
