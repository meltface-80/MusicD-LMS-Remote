# CLAUDE.md — working rules for this repo

MusicD LMS Remote: a music-discovery PWA + wall display for Lyrion Music
Server (LMS). Node/Express backend (`index.js`) talks to LMS over JSON-RPC
(`lib/lms.js`); the frontend is a no-build vanilla PWA (`public/`). See the
Layout section of README.md.

## Process — applies to every task

1. **Use agent workers for all tasks.** Fan out background subagents to
   research/map the codebase (and the web, where sources need verifying)
   before implementing. Implement in the main session on their findings —
   don't have multiple agents edit the same files.

2. **Verify before committing.** `npm test` must pass. For anything touching
   the server or frontend behaviour, run an end-to-end check against a fake
   LMS JSON-RPC server (spawn `index.js` with `LMS_HOST`/`LMS_PORT` pointed
   at a mock; drive the UI with Playwright — Chromium is at
   `/opt/pw-browsers/chromium`). Never leave a `data/` directory behind in
   the repo (it's gitignored, but clean up after test runs).

3. **Bump the version once a new build is ready.** When a feature set /
   fix set is complete and verified, bump `version` in `package.json` and
   commit it as `Release v1.0.x` with short release notes in the commit
   body. **Keep v1.0.x numbering for now** — the owner will advise
   explicitly when to move to v1.1.x or beyond.

4. **The version bump IS the release.** This repo has no tags or GitHub
   releases; the in-app updater resolves the latest version from
   `package.json` on `main` and downloads the `main` tarball. Merging the
   bump publishes the update to every install.

## Git

- Develop on the designated `claude/…` branch; after a PR merges, restart
  the same branch from `origin/main` (`git checkout -B <branch>
  origin/main`) — never stack onto merged history.
- Don't create PRs unless asked; the owner merges.

## Gotchas worth remembering

- LMS artist strings use " / " as the multi-artist separator; " & " is part
  of band names — never split on it. Artist identity comparisons go through
  `search.artistKey()` (stylization-folded: P!nk == Pink), display strings
  never do.
- Pitchfork review TEXT must never reach a client (UK-law compliance):
  score / Best-New-Music flag / link only. Review text comes from the LMS
  Music & Artist Information plugin or Qobuz (`lib/albuminfo.js`).
- The Qobuz client (`lib/qobuz.js`) uses the unofficial API — unsigned
  endpoints only, no stream URLs, nothing that needs an app_secret.
- Song/album LMS tag letters differ (`c` vs `j` for cover ids; see
  `lib/lms.js` TRACK_TAGS/ALBUM_TAGS comments) — check the comments before
  adding tags.
- `/api/queue` returns only current + upcoming tracks; `queue_item_id` is
  the REAL LMS playlist index (play-from-here/remove depend on it).
