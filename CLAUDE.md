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

5. **Keep the GitHub Pages site in step with every release.** The docs /
   configurator page (`docs/index.html`, served at
   https://meltface-80.github.io/MusicD-LMS-Remote/) shows the current
   version and must be updated in the same release commit: bump the
   `#version-badge` fallback text to match `package.json` (the badge also
   self-updates from `package.json` on `main` via fetch, but the static
   fallback must not drift), and reflect any new user-facing features or
   changed setup/config steps in the page's content.

## Git

- Develop on the designated `claude/…` branch; after a PR merges, restart
  the same branch from `origin/main` (`git checkout -B <branch>
  origin/main`) — never stack onto merged history.
- Don't create PRs unless asked; the owner merges.

## Gotchas worth remembering

- Artist strings split into separate clickable artists on ALL of " / ",
  "; ", ", ", " & ", " + " and feat./featuring/ft. (owner decision, v1.0.5 —
  band names containing " & " split too, knowingly; each part's artist page
  still lists the band's albums). Keep `lib/search.js` MAIN/ANY_SPLIT_RE and
  `public/app.js` ARTIST_SPLIT_RE in step. Artist identity comparisons go
  through `search.artistKey()` (stylization-folded: P!nk == Pink), display
  strings never do.
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
- The music mount is READ-ONLY: never write artwork/metadata back to files or
  LMS. Owner album edits (`lib/albumedits.js`, `data/album-edits.json`) and
  rescued cover art (`lib/albumart.js`, `data/artwork/` + cache) live in the
  app's own DB, keyed by the ORIGINAL LMS title+artist so they survive
  rescans. Both are layered onto the LMS rows in `buildIndex` before
  `search.loadRecords`; a record carries `origTitle/origArtist/origYear/
  origImageKey` so "Remove edits" can restore LMS values. Rescued/edited
  covers use content-addressed `art-…` image keys served straight from disk by
  `/api/image` (a new cover mints a new key — immutable HTTP caching stays
  safe). Artwork sources, best-first: MAI `albumcovers` → Cover Art Archive by
  MBID (LMS tag M = release id) → MusicBrainz release-group search (artistKey
  fold, no disambiguation) → Qobuz → iTunes.
