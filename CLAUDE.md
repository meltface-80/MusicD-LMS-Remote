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
- **Land every finished build yourself** (owner decision, v1.0.38). Once
  `npm test` and the end-to-end checks are green and the version is bumped:
  push the branch, open the PR, and merge it. Don't wait to be asked, and
  don't leave `main` sitting behind a finished build — the merge IS the
  release (§4 above), so an unmerged bump means no install gets the update.
- Never push straight to `main`; changes reach it only through a merged PR,
  so the history keeps a reviewable record of each release.
- Report the merged version back to the owner so they know what's live.

## Gotchas worth remembering

- Artist strings split into separate clickable artists on ALL of " / ",
  "; ", ", ", " & ", " + " and feat./featuring/ft. (owner decision, v1.0.5 —
  band names containing " & " split too, knowingly; each part's artist page
  still lists the band's albums). Keep `lib/search.js` MAIN/ANY_SPLIT_RE and
  `public/app.js` ARTIST_SPLIT_RE in step. Artist identity comparisons go
  through `search.artistKey()` (stylization-folded: P!nk == Pink), display
  strings never do.
- Pitchfork review TEXT must never reach a client (UK-law compliance):
  score / Best-New-Music flag / link only. Review text comes ONLY from the LMS
  Music & Artist Information (MAI) plugin (`lib/albuminfo.js`).
- Reviews / bios / artwork / artist photos come from the LMS MAI plugin
  (`musicartistinfo` albumreview/biography/albumcovers/artistphoto[s]); there is
  NO app-side Qobuz/Tidal streaming integration — those were removed (v1.0.15).
  Browsing/adding online-library albums is done on the SERVER via its own
  Qobuz/Tidal plugins; the app opens the LMS Material skin (`/material/`) in the
  embedded frame (topbar "Browse Qobuz" button → server-browse-toggle). The
  `lib/labels.js` Qobuz label lookup is an unrelated PUBLIC web scrape (no
  account/API) and stays.
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
  fold, no disambiguation) → iTunes.
- Logging goes through the leveled logger `lib/log.js` (`makeLogger("tag")` →
  error/warn/info/debug/trace; `.child("sub")`, `.enabled(level)`). Level from
  env: `LOG_LEVEL` wins, else `DEBUG=1`→debug / `DEBUG=trace`→trace, else info.
  Prefer it over `console.*` in new code; pass a tagged `log:` into lib
  factories (lms/albumart/albuminfo already take one). Keep failure diagnostics
  at debug and per-request/per-command firehose at trace. COMPLIANCE: never log
  Pitchfork review TEXT — URL/score/status only. `setLogFile()` (called once in
  index.js) tees every line to a rotating file under `data/logs/` (8MB × 10
  archives, Roon-style); console output is unchanged so `docker logs` works.
- Transport polling is ADAPTIVE and must stay cheap: the phone app
  (`public/app.js` fetchState loop) and the wall (`public/display.js` pollLoop)
  poll `/api/zone-state` ~2s while playing, ~6s when paused/stopped (progress is
  interpolated client-side). `/api/zone-state` hits LMS live per call, so
  concurrent app+display polls are coalesced server-side (`playerStatusShared`).
  Don't reintroduce fixed fast polls.
- STORED playlists (`playlists …` CLI) are a different namespace from the live
  player queue (`playlist …` / `playlistcontrol`). Every `playlists` command is
  server-global — pass `""` as the player id, NOT a real one. In
  `playlistcontrol`, `playlist_id:` is a SOURCE filter (load that playlist onto
  a player) and never edits the saved list.
- Lyrion has NO bulk playlist add: `playlists edit cmd:add` appends ONE track
  addressed by title+URL (there is no track_id form), so `/api/playlists/add`
  loops. That's why TRACK_TAGS carries `u` — the url is the only handle. The
  rejected alternative was playlistcontrol-into-the-queue + `playlist save`,
  which clobbers whatever is playing. A track with no url is skipped and
  counted, never fatal. `playlists new` on a NAME COLLISION creates nothing and
  returns `overwritten_playlist_id` — surfaced as `created:false` so the UI can
  say "added to the existing one" instead of implying a new playlist.
- Multi-select is TWO independent systems. Albums: `albumSelectMode` +
  `.album.is-selected` + `#album-action-bar`, entered by long-press, and a tap
  in select mode ALWAYS toggles selection even on tiles with a custom onClick
  (Home carousels, Library wall). It must be cleared on every view change —
  `showHome`/`showLibraryWall`/`exitLibraryWall`/`showUnplayedWall`/
  `exitArtistView`/search/labels — or the bar strands over the next screen.
  Tracks: `trackSelectMode` + `.t-row.is-picked` + `#track-action-bar`, scoped
  to the open album modal and reset by `closeModal()` AND `openAlbum()`.
  Deliberately NOT `.is-selected` — that selector is shared with the
  labels-merge `.label-tile.is-first-selected` flow. Long-press only on rows
  built by `fetchAlbumDetail()`; `fetchQobuzAlbumDetail()` rows share `.t-row`
  but have no album offset, so they must stay unselectable.
- The client is never given LMS track ids — track identity is (album `offset`,
  array index). `/api/play-track` and `/api/play-tracks` therefore re-read the
  album and resolve indices POSITIONALLY, with a 409 when nothing resolves; the
  batch endpoint skips indices that have gone out of range rather than failing
  the whole request (a rescan mid-selection must not lose the valid tracks).
- The Library wall (`/api/library/albums`, `/api/library/facets`) is the ONLY
  deterministic browse — every other wall is a random sample. Semantics mirror
  the Roon build: facet values OR within a group, groups AND together; `dir`
  literally reverses the comparator for every sort; ties break on `sortTitle`
  so equal-ranked albums can't reshuffle between pages. TRAPS: (a) `year` must
  NOT be a plain reverse — undated albums are held out, sorted separately and
  appended, or "newest first" floats every undated album to the top; (b)
  `random` uses `seededRank()` (a pure hash of title+artist+seed), never
  Math.random(), so paging is stable with no stored permutation; (c) offset
  clamps to `total`, not total-1 — asking past the end returns an empty page,
  which is how the client's infinite scroll detects the end (it never does
  offset+count<total arithmetic). Sorted views are memoised per parameter
  combination, keyed partly on `index.builtAt` so a reindex invalidates them.
- `search.sortKey()` powers A-Z ordering ("The Beatles" files under B) and is
  DISTINCT from `normalize()` (search matching) and `artistKey()` (identity
  folding) — don't collapse them. `sortTitle`/`sortArtist` are precomputed at
  index time and must be recomputed in `reindexRecord()` when an owner edit
  renames an album, or it stays filed under its old name.
- Play history (`lib/plays.js`) is a title-keyed JSON log capped at ~13 months
  / 5000 rows, so "Most played" / "Last played" are approximations over recent
  history, NOT all-time, and two albums sharing a title share a bucket. The
  Library sort notes say so — keep any new UI equally honest.
- Album genre comes from LMS tags g/G via `albumGenre()` (both letters sent,
  as with e/E, because which one carries genre varies by LMS version; a
  multi-genre album keeps its FIRST genre). An album LMS gives no genre for
  simply drops out of the genre facet — never throws.
- Theming is TWO axes on `<html>`: `data-theme` (dark|light) x `data-palette`
  (classic|copper) = 4 themes (Dark, Light, Copper dark, Brass light), matching
  the MusicD Remote Roon extension. Component CSS must read TOKENS only, never a
  palette literal — a new palette should be a token block and nothing else.
  `--on-accent` is ink ON an accent fill; `--accent-text` is accent used AS text
  (they differ on light palettes, which need white on the fill). Theme list lives
  in `public/app.js` `THEMES` and is exposed as `window.__themes` so the Settings
  picker can't drift from the CSS. Persisted under `rra-theme-v2` (the v1
  `rra-theme` key auto-migrates); `applyTheme()` reads `--bg` back out and syncs
  the `theme-color` meta.
- `public/app.js` is a series of sibling IIFEs (separate scopes, NOT one closure)
  — there is ONE shared HTML-escaper `esc()` at script top-level for all of them.
  Any LMS/network string put into `innerHTML` MUST go through `esc()` (album/
  artist/track names carry markup, esp. online-library titles the owner didn't
  author). Prefer `textContent`/DOM building where possible.
- Endpoints that fetch a USER-SUPPLIED URL server-side (album-edit `art_url`,
  label-logo `url`) must pass it through `assertPublicUrl()` (`lib/urlguard.js`)
  first — it rejects loopback/private/link-local/ULA targets (SSRF guard). It
  validates the request TARGET; an HTTP redirect to a private address is a known
  residual gap. Don't add new server-side fetches of caller URLs without it.
- Qobuz-catalogue search (albums NOT in the library) is driven through the LMS
  Qobuz plugin over JSON-RPC by MENU-ACTION REPLAY, the same mechanism Material
  uses — not by parsing Qobuz ids. `lib/lms.js` walks `qobuz items` (root →
  Search node, cached; descends into an Albums category if search returns
  sub-menus) and captures each album row's `play`/`add` menu actions
  (`menuAction()` merges response-level `base.actions` with per-item `params`);
  `qobuzRunAction()` replays them as `qobuz playlist play|add …`. Every `qobuz`
  dispatch is needs-client=1, so a real player id is required (search uses
  `state.players[0].id`). Actions are held SERVER-SIDE in `qobuzActionStore`
  keyed by an opaque token (30-min TTL) the client echoes to `/api/qobuz/play` —
  the client never submits a raw LMS command. This generalises to Tidal/Deezer
  (same interface, different tag). Result covers reuse the `url-…` image_key →
  `/api/image` → LMS imageproxy path. NOTE: the exact plugin menu shapes are
  unverified against a live server — keep it defensive and logged.
- Qobuz FAVOURITES (the heart) also use menu-action replay — the plugin has no
  favourite CLI verb, so `qobuzAlbumFavoriteToggle()` descends into an album's
  menu (its captured `go` action), finds the Add/Remove-favourite child and
  invokes it only when the state must change. Favouriting is FAVOURITE-ONLY (no
  library rescan, owner decision). Library albums expose `qobuz_id`
  (`search.qobuzIdFromExtid` off the `qobuz:album:<id>` extid); the client fills
  a heart on any tile whose id is in `/api/qobuz/favorites` (server-cached 60s
  via `qobuzFavoriteAlbums`, which walks the root Favorites node). Search-result
  hearts POST `/api/qobuz/favorite` (token → stored `go`); library-tile hearts
  POST `/api/qobuz/favorite-id` (qobuz_id → the favourites cache's `go`). Same
  live-server caveat — defensive + logged.
- Qobuz menu-row fields (confirmed against a live plugin, v1.0.22): the display
  label is `text` (NOT name/title), and a row's browse id lives in its
  `actions.go.params.item_id` (NOT a top-level `id`) — use `qLabel()` and
  `qobuzItemId()` in lib/lms.js, never `it.id`/`it.name`. Navigation stays on
  `menu:1` via `qobuzNav()`. `GET /api/qobuz/debug?q=…` dumps the raw plugin
  menu responses for when shapes differ again. Search is TWO-STEP: the Search
  node returns a "New search" input whose go action template carries
  `search:"__TAGGEDINPUT__"` + `item_id:0.0` — substitute the term and run it;
  results come back as CATEGORY groups (albums are "Releases", not "Albums" —
  `isAlbumCategory()` matches Releases/Albums/icon). Album rows are labelled
  "Album\nArtist (Year)" (`qobuzTitleArtist()` un-swaps them) and their cover is
  a RELATIVE `/imageproxy/<enc-url>/image.jpg` in `icon` (`qobuzImageKey()`
  unwraps the embedded URL). Strip per-row `isContextMenu` from replayed actions.
- The native Qobuz BROWSER (`qobuzBrowse()` → `/api/qobuz/browse`, side-menu
  "Browse Qobuz") walks the same menu tree, classifying rows as navigable NODEs
  (`addAction:"go"`/`type:link`) or playable ALBUMs; the frontend overlay
  (`initQobuzBrowse`) renders nodes as category rows and albums via the shared
  `window.__buildQobuzRow` (same Play/Queue/Heart used by search). Replaced the
  old Material-frame deep-link (Material has no app deep-link param).
