// ---------------------------------------------------------------------------
// In-app self-updater — checks GitHub for a newer release and, on request,
// downloads that build, overlays it onto the install dir, runs `npm install` if
// dependencies changed, and restarts INTO the new code. No `docker build`.
//
// HOW THE RESTART WORKS: the container's PID 1 is launcher.js, which supervises
// index.js. When the running app is asked to update it stages the new build
// under `dir/.update/`, writes a READY marker, and exits with code 75. The
// launcher sees exit 75, applies the staged files WHILE THE APP IS STOPPED (so
// nothing rewrites itself in place), and relaunches. If the app is somehow run
// without the launcher (RRA_VIA_LAUNCHER unset) it overlays the files itself
// before exiting 75, relying on Docker's restart policy / an external
// supervisor to bring it back up.
//
// GitHub REST requires a User-Agent header (it 403s otherwise); unauthenticated
// access is fine for public repos (60 req/hr/IP), so we cache the last check and
// only re-check hourly (maybeCheck / CHECK_TTL_MS) behind a single-flight guard.
//
// Injected deps (owner/repo/currentVersion/dir/viaLauncher/debug) keep this file
// free of any dependency on index.js so it's unit-testable offline — the same
// pattern as lib/labels.js's makeLabels(). applyStaged/copyOverlay/topLevelDir
// are also exported at module level because launcher.js applies staged builds.
// ---------------------------------------------------------------------------
"use strict";

const https = require("https");
const http = require("http");
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const CHECK_TTL_MS = 60 * 60 * 1000; // re-check GitHub at most hourly (60 req/hr unauth cap)
const FETCH_TIMEOUT_MS = 8000;       // deadline every request so a wedged endpoint can't hang
const NOTES_CAP = 600;               // release bodies can be huge — keep the banner readable
const USER_AGENT = "MusicD-LMS-Remote-updater";

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

// A GitHub source tarball wraps everything in one top-level dir (owner-repo-sha/);
// an uploaded asset built by this project wraps it in its own dir. Either way,
// find that single top dir (or null if the archive isn't shaped that way).
function topLevelDir(root) {
  const entries = fs.readdirSync(root);
  if (entries.length === 1 && fs.statSync(path.join(root, entries[0])).isDirectory()) {
    return entries[0];
  }
  return null;
}

// Recursively copy src over dest, skipping names in `skip` (so we never clobber
// the user's data dir, node_modules, the staging dir, or .git).
function copyOverlay(src, dest, skip) {
  skip = skip || [];
  for (const name of fs.readdirSync(src)) {
    if (skip.includes(name)) continue;
    const s = path.join(src, name), d = path.join(dest, name);
    const st = fs.statSync(s);
    if (st.isDirectory()) {
      fs.mkdirSync(d, { recursive: true });
      copyOverlay(s, d, skip);
    } else {
      fs.copyFileSync(s, d);
    }
  }
}

// Overlay a staged build onto the install dir and `npm install` if deps changed.
// Shared by the in-app updater (no-launcher path) and by launcher.js. Excludes
// `data` — that's this app's persistent settings/cache dir, which must survive
// an update (the Roon build excludes config.json here; ours is a directory).
function applyStaged(stagedDir, targetDir, opts) {
  opts = opts || {};
  const log = opts.log || (() => {});
  const readPkg = (p) => {
    try { return JSON.parse(fs.readFileSync(path.join(p, "package.json"), "utf8")); }
    catch (e) { return {}; }
  };
  const oldDeps = JSON.stringify(readPkg(targetDir).dependencies || {});
  copyOverlay(stagedDir, targetDir, [".git", "node_modules", ".update", "data", "cache"]);
  const newDeps = JSON.stringify(readPkg(targetDir).dependencies || {});
  if (oldDeps !== newDeps) {
    log("dependencies changed — running npm install");
    const r = spawnSync("npm", ["install", "--omit=dev", "--no-audit", "--no-fund"],
      { cwd: targetDir, stdio: "inherit", shell: true, timeout: 5 * 60 * 1000 });
    if (r.status !== 0) log("npm install exited with status " + r.status + " (continuing)");
  }
}

// Stream a tarball to disk. Follows redirects (release/tarball URLs on
// api.github.com 302 to codeload/object storage). Only sends the auth token to
// GitHub's own API host, never to the redirected storage host.
function downloadFile(url, dest, token, redirectsLeft) {
  if (redirectsLeft == null) redirectsLeft = 6;
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const headers = { "User-Agent": USER_AGENT, "Accept": "application/octet-stream" };
    if (token && u.hostname === "api.github.com") headers.Authorization = "Bearer " + token;
    const client = u.protocol === "http:" ? http : https;
    client.get({ hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, headers }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        if (redirectsLeft <= 0) return reject(new Error("too many redirects"));
        return resolve(downloadFile(new URL(res.headers.location, u).toString(), dest, token, redirectsLeft - 1));
      }
      if (res.statusCode !== 200) { res.resume(); return reject(new Error("download HTTP " + res.statusCode)); }
      const f = fs.createWriteStream(dest);
      res.pipe(f);
      f.on("finish", () => f.close(() => resolve()));
      f.on("error", reject);
    }).on("error", reject);
  });
}

function makeUpdater({ owner, repo, currentVersion, dir, viaLauncher, token, debug } = {}) {
  const current = stripV(currentVersion) || "0.0.0";
  const installDir = dir || process.cwd();
  const viaLaunch = !!viaLauncher;
  const authToken = token || null;

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
  // Tarball URL for the cached `latest`, resolved alongside it in checkNow().
  // Kept out of getStatus() (it's an implementation detail apply() consumes).
  let downloadUrl = null;
  // Live apply progress the frontend polls: idle → downloading → extracting →
  // restarting, or → error. Survives across getStatus() calls.
  let applyState = { phase: "idle", error: null, version: null };

  let inFlight = null; // single-flight promise so overlapping checks coalesce

  function log(msg) { if (debug) console.error("[updater] " + msg); }

  // One deadlined GitHub request. Returns parsed JSON, or { _status } on a
  // non-2xx, or null on network/abort — callers treat those as "this source had
  // nothing" and fall through to the next source.
  async function ghFetch(url) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), FETCH_TIMEOUT_MS);
    try {
      const res = await fetch(url, {
        signal: ctl.signal,
        headers: {
          "User-Agent": USER_AGENT,
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
  // priority order, stopping at the first that yields a version, and records the
  // matching tarball URL so apply() knows what to download. The download URL is
  // always the PUBLIC github.com/{owner}/{repo}/archive/... form (which reliably
  // 302s to codeload and needs no auth token), never the api.github.com/tarball
  // endpoint (that 415s/403s in real deployments):
  //   1. latest release (richest — notes + a canonical html_url; prefer a
  //      .tgz/.tar.gz release asset, else the archive tarball for the tag)
  //   2. tags list (covers repos that tag but don't cut formal releases)
  //   3. package.json on the default branch (last resort — always present)
  // Returns { latest, notes, url, downloadUrl } or null if every source was empty.
  async function resolveLatest() {
    const releasesUrl = "https://github.com/" + owner + "/" + repo + "/releases";
    const apiRepo = "https://api.github.com/repos/" + owner + "/" + repo;
    const archiveTag = (tag) =>
      "https://github.com/" + owner + "/" + repo + "/archive/refs/tags/" + encodeURIComponent(tag) + ".tar.gz";

    // 1. Latest release.
    const rel = await ghFetch(apiRepo + "/releases/latest");
    if (rel && rel.tag_name) {
      let notes = null;
      if (typeof rel.body === "string" && rel.body.trim()) {
        notes = rel.body.trim().slice(0, NOTES_CAP);
      }
      const assets = Array.isArray(rel.assets) ? rel.assets : [];
      const tarAsset = assets.find((a) => a && /\.(tgz|tar\.gz)$/i.test(a.name || ""));
      const dl = tarAsset ? tarAsset.browser_download_url : archiveTag(rel.tag_name);
      return { latest: stripV(rel.tag_name), notes, url: rel.html_url || releasesUrl, downloadUrl: dl };
    }

    // 2. Tags (fallback when there are no formal releases — 404 above).
    const tags = await ghFetch(apiRepo + "/tags");
    if (Array.isArray(tags) && tags.length && tags[0] && tags[0].name) {
      const tag = tags[0].name;
      return {
        latest: stripV(tag), notes: null, url: releasesUrl,
        downloadUrl: archiveTag(tag),
      };
    }

    // 3. package.json on the default branch (final fallback).
    const pkgJson = await ghFetch("https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json");
    if (pkgJson && typeof pkgJson.version === "string") {
      return {
        latest: stripV(pkgJson.version), notes: null, url: releasesUrl,
        downloadUrl: "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/main.tar.gz",
      };
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
          downloadUrl = null;
          return getStatus();
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
        downloadUrl = found.downloadUrl || null;
      } catch (e) {
        // Belt-and-braces: resolveLatest already swallows per-request errors,
        // but never let checkNow reject its callers.
        cached = {
          available: false, latest: current, current, notes: null, url: null,
          isDowngrade: false, checkedAt: Date.now(),
          error: "Update check failed: " + (e && e.message ? e.message : "unknown error"),
        };
        downloadUrl = null;
      } finally {
        inFlight = null;
      }
      return getStatus();
    })();
    return inFlight;
  }

  // Return the last cached result synchronously, plus live apply progress and
  // whether we're supervised by the launcher (the frontend poll needs both).
  function getStatus() {
    return Object.assign({}, cached, {
      apply: { phase: applyState.phase, error: applyState.error, version: applyState.version },
      viaLauncher: viaLaunch,
    });
  }

  // Download → extract → stage the newer build, then exit(75) so the launcher
  // (or, un-launched, an external supervisor) restarts into it. Never throws:
  // failures land in apply.phase="error" with a message the UI surfaces.
  async function apply() {
    const busy = ["downloading", "extracting", "restarting"];
    if (busy.includes(applyState.phase)) return getStatus();

    applyState = { phase: "checking", error: null, version: null };
    if (!cached.available || !downloadUrl) {
      await checkNow();
      if (!cached.available || !downloadUrl) {
        applyState = { phase: "error", error: cached.error || "No update available", version: null };
        return getStatus();
      }
    }
    const target = cached.latest;
    const upd = path.join(installDir, ".update");
    const dlFile = path.join(upd, "download.tgz");
    const exRoot = path.join(upd, "extract");
    try {
      fs.mkdirSync(upd, { recursive: true });
      try { fs.rmSync(exRoot, { recursive: true, force: true }); } catch (e) {}
      fs.mkdirSync(exRoot, { recursive: true });

      applyState = { phase: "downloading", error: null, version: target };
      log("downloading " + downloadUrl);
      await downloadFile(downloadUrl, dlFile, authToken);

      applyState = { phase: "extracting", error: null, version: target };
      const ex = spawnSync("tar", ["-xzf", dlFile, "-C", exRoot], { stdio: "ignore", shell: true });
      if (ex.status !== 0) throw new Error("extraction failed (is `tar` installed and on PATH?)");
      const top = topLevelDir(exRoot);
      const staged = top ? path.join(exRoot, top) : exRoot;
      if (!fs.existsSync(path.join(staged, "index.js")) ||
          !fs.existsSync(path.join(staged, "package.json"))) {
        throw new Error("downloaded build is missing index.js/package.json");
      }

      if (viaLaunch) {
        fs.writeFileSync(path.join(upd, "READY"), JSON.stringify({ staged, version: target }));
        applyState = { phase: "restarting", error: null, version: target };
        log("staged; exiting 75 for launcher to apply + restart");
        setTimeout(() => process.exit(75), 400);
      } else {
        applyStaged(staged, installDir, { log });
        try { fs.rmSync(upd, { recursive: true, force: true }); } catch (e) {}
        applyState = { phase: "restarting", error: null, version: target };
        log("applied in place; exiting 75 for supervisor to restart");
        setTimeout(() => process.exit(75), 400);
      }
    } catch (e) {
      applyState = { phase: "error", error: e.message, version: target };
      log("apply failed: " + e.message);
    }
    return getStatus();
  }

  // Fire a check only if it's been more than an hour since the last one and no
  // check is already running — respects GitHub's 60 req/hr unauthenticated cap.
  // Fire-and-forget: callers don't await it (the status route returns the cache).
  async function maybeCheck() {
    if (inFlight) return;                                   // already running
    if (Date.now() - cached.checkedAt < CHECK_TTL_MS) return; // checked recently
    checkNow().catch(() => { /* checkNow never throws, but be safe */ });
  }

  // Test-only hook: seed an available update + tarball URL so apply() can run
  // against a local server without touching GitHub. Mirrors labels.js's _set*.
  function _forceState({ available, latest, notes, url, downloadUrl: dl } = {}) {
    if (available != null) cached.available = !!available;
    if (latest != null) { cached.latest = stripV(latest); }
    if (notes !== undefined) cached.notes = notes;
    if (url !== undefined) cached.url = url;
    if (dl !== undefined) downloadUrl = dl;
    cached.checkedAt = Date.now();
    return getStatus();
  }

  return { getStatus, checkNow, apply, maybeCheck, _cmp, _forceState };
}

module.exports = { makeUpdater, applyStaged, copyOverlay, topLevelDir, downloadFile, _cmp };
