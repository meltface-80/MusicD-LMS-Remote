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
  embedded frame. NOTE: since the native browser landed, `server-browse-toggle`
  opens `window.__openQobuzBrowse()` in-app, NOT the Material frame. The
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
- The app-Favourite control on the album screen is an ICON BUTTON IN THE MODAL
  CHROME (`.modal-fav`, beside Edit), never a pill in `#modal-actions`: that row
  is playback, and for a Qobuz album a heart pill there would sit beside the
  Qobuz heart meaning something else entirely. App favourites paint with
  `--accent`; the Qobuz heart stays pink — different colour, different system.
  Tiles mark app-favourites via `data-fav-key` + `.is-app-fav`, repainted by
  `window.__repaintFavMarks()` from `/api/favourites/keys` (that endpoint exists
  for exactly this — don't fetch the whole collection to mark a grid).
- Tiles built OUTSIDE `buildAlbumTile()` — the Qobuz search rows and the native
  Qobuz browser's grid — don't inherit its long-press, so they wire
  `window.__addLongPress` explicitly with `allowSelect:false` (no library offset
  to multi-select on). Any new bespoke tile builder must do the same or its
  albums silently can't be favourited.
- Album merges (`lib/albummerges.js`, `data/album-merges.json`) collapse the
  separate rows LMS makes for a multi-disc set. NOT like the label merge: labels
  are a derived projection, albums are the PRIMARY index. The collapse runs in
  `buildIndex()` AFTER the edit/artwork layering (so a renamed part still
  matches) and BEFORE `search.loadRecords` (which mints offsets and the maps).
  A merged record keeps `partIds` — every part's LMS album id in disc order —
  because LMS only understands one `album_id` at a time. `loadRecords` maps
  EVERY part id into `byId`, or the genre-facet remap and the album-of-the-day
  pick lose absorbed discs. `tracksForRecord()` and `playRecord()` in index.js
  are the only places that WALK `partIds` in order (`resolveMergeItems` reads
  `partIds[0]` for the primary's id); track identity is (offset, array
  index), so if the track list and the play path built that array differently
  the same index would mean different tracks — keep both going through
  `tracksForRecord()`. Parts key on normalised title+artist (durable across
  rescans); order is the USER'S selection order because an LMS album row
  carries no disc number (only tracks do, tag `i`). The merged title comes from
  `stripDiscSuffix()` on the primary part.
- Merge identity is the ORIGINAL LMS title+artist, never the displayed one
  (v1.0.51). The edit layer runs BEFORE `albummerges.apply()` and renames rows
  in place, so keying on the display title meant a rename changed the very
  string the key came from and the part fell out of its own merge. Rows carry
  `origTitle`/`origArtist` so both stages agree; `apply()` matches a row on
  EITHER key so pre-v1.0.51 merges still collapse. Renaming a merged album goes
  to the merge record (`albummerges.rename`), NOT to an album edit — an edit
  keys on a raw row and renaming that row is what broke it; year/artwork still
  layer onto the primary part because they don't touch the key. `apply()` sets
  the merged row's `origTitle`/`origArtist` from the primary part, or
  `search.indexRecord` would default them to the synthesised merged name that
  matches no album. `/api/albums/merge` takes each item's `offset` and resolves
  it server-side; an item that is itself a merge expands into that merge's
  parts AND carries its name over, so growing a set keeps both.
- A merge repair may only ever move the ARTIST half of a key, NEVER the title
  (v1.0.53). The title comes from the file's ALBUM tag; the artist is what LMS
  re-derives. v1.0.52 accepted a stored-id hit with no identity check, so a full
  rescan — which renumbers ids AND re-detects artists, i.e. exactly when repair
  runs — could hand a part whichever album now holds its old id. That album
  VANISHED from the library into someone else's merge and the merge file was
  rewritten with its key, unrecoverable except by unmerging. Repairs now require
  an exact normalised-title match; a moved artist additionally needs
  corroboration (another part still matching exactly, or the WHOLE set
  resolving) or it is refused.
- Owner-supplied artwork URLs go through `assertAllowedArtUrl()`, not
  `assertPublicUrl()` directly (v1.0.53): the MAI plugin returns cover
  candidates as URLs ON THE LMS HOST, which is nearly always a private address.
  Guarding those blindly made every MAI candidate unsavable — a bug that had
  been live since the guard landed. The LMS host is exempt because it is the
  server we are configured to talk to, not a caller-chosen target; everything
  else is guarded as before.
- Merge parts SELF-HEAL across a rescan (v1.0.52). The part key is
  title+artist and the ARTIST half is SCAN-DERIVED — `lib/lms.js` takes
  `row.artist || row.albumartist`, and a rescan re-runs various-artist
  detection and re-reads ALBUMARTIST/ARTIST, so a disc can come back as
  "Various Artists" with its title untouched and the key matches nothing.
  `apply()` therefore tries, in order: exact key (orig then current) → the
  stored LMS album `id` (survives a "new and changed" scan; a full rescan
  renumbers) → an UNAMBIGUOUS title match (exactly one album in the library
  carries that title). Anything recovered is written back via `healParts()` so
  the next rebuild is an exact match. It must never guess: two albums sharing a
  title with different artists is precisely the case that has to be left alone,
  because a wrong absorb HIDES an album. Album edits, rescued artwork and
  favourites key on the same fragile pair and have the same exposure — they
  just have no repair pass yet.
- NOTHING used to notice a rescan started outside the app (v1.0.52).
  `state.server.lastScan` was read only by `indexCacheSig`, and the only
  post-scan refresh was client-side (`watchLmsScan` → `POST /api/reindex`),
  which needs the page open and the scan to have been started from the app. A
  scan run from LMS's own web UI or on a schedule left a pre-scan index for up
  to `INDEX_MAX_AGE_MS` (12h). `indexScanStamp` records the lastScan each index
  was built from; `refreshConnection` rebuilds when it moves, and
  `ensureIndex()` treats a changed stamp as stale.
- Streaming services are GATED ON THE SERVER'S ACTUAL STATE (v1.0.51). Three
  states, and no single LMS command reports all three: plugin absent (the
  `<tag> items` verb was never registered, so LMS logs "not dispatchable" and
  CLOSES THE SOCKET — it surfaces as a rejected rpc, not an error payload),
  present but logged out (XMLBrowser hoists the plugin's `type:"textarea"`
  credentials row out of the list, so you get count:0 + `window.textarea`), and
  usable. `lms.serviceStatus(tag, playerId)` does `can <tag> items ?` then one
  root fetch. `can` is ADVISORY ONLY — a server that doesn't answer `_can` must
  not have its plugins declared missing, so null falls through to the root
  probe, which is authoritative. `apps 0 999` enumerates enabled app plugins
  but CANNOT see login state (neither the Qobuz nor Tidal plugin declares a
  `condition()`). `/api/services` caches for 5 min and coalesces concurrent
  probes; `requireService()` fronts every `/api/qobuz/*` route so an unusable
  service answers 503 `{unavailable:true}` instead of a raw socket-error 500.
  The client hides the side-menu entry and the tile heart, and skips the
  external Qobuz search entirely. The Qobuz SOURCE BADGE stays — the album
  really did come from Qobuz, and hiding it would make an online album look
  local; the heart goes because it is an action against an account that isn't
  there.
- Favourites (`lib/favourites.js`, `data/favourites.json`) are THIS APP'S own
  collection, nothing to do with the Qobuz heart (which writes to the Qobuz
  account). Keyed on normalised title+artist like album edits, because ids and
  offsets move on a rescan and a catalogue album has neither — so a favourite
  can hold an album that isn't in the library at all. `/api/favourites`
  re-resolves each one to a CURRENT offset by title+artist; a null offset means
  "not in the library right now", which the UI must handle rather than opening
  nothing.
- Long-press on an album tile ENTERS MULTI-SELECT (v1.0.50, reverting v1.0.45's
  context sheet), and only for a tile with a library `offset` — every batch
  action keys off it, so `handleAlbumTileSelect` returns early without one. A
  Qobuz catalogue tile still gets `window.__openAlbumSheet` (always with
  `allowSelect:false`): it is the only way to favourite a catalogue album from
  the screen you found it on. A playlist/Live-Playlist tile (`.is-playlist`,
  no offset, no token) gets NOTHING — it isn't an album. Long-press while
  ALREADY selecting just toggles. Track rows are unchanged.
- The album selection actions live in a TOP-BAR OPTIONS DROPDOWN, not a bottom
  bar. `#album-select-row` REPLACES `.topbar-row` while selecting (so the grid
  never shifts), and it works on every album grid for free because it is part of
  the global top bar. The old `#album-action-bar` was five text buttons plus a
  cancel in a `flex-shrink:0` row — `flex-wrap` could not save it and Merge fell
  off a phone's right edge; a vertical list cannot overflow. The menu is
  rendered into `<body>` with fixed positioning off the button's rect: `.topbar`
  is its own stacking context, so a nested menu could not sit above the
  full-screen dismiss backdrop. Do NOT write the "N selected" readout into
  `#album-count` — the Library wall rewrites that on every page fetch;
  `#album-select-info` exists for it. Teardown lives inside
  `exitAlbumSelectMode()` so all ~15 call sites get it without edits.
  Multi-favourite is add-only EXCEPT when the whole selection is already
  favourited, when the row flips to Remove (`/api/favourites/remove-multi`) —
  long-press no longer reaches the sheet's un-favourite, so the menu carries it.
- `window.__albumAction(items, kind)` is the one way to play/queue a set of
  albums from anywhere. Library albums go by `offset` through `/api/play-multi`;
  Qobuz catalogue albums have no offset and replay by token. When mixing, only
  the FIRST item may honour `play_now` — the rest must append or each would wipe
  the one before.
- The Home "Library" row follows the Library wall's SORT (not its Focus facets —
  the row is labelled "Library" and links to the whole thing, so silently
  filtering it would surprise). `applyLibView()` marks it stale; `showHome()`
  reloads it.
- Multi-select is TWO independent systems. Albums: `albumSelectMode` +
  `.album.is-selected` + `#album-select-row` (the top-bar Options row, v1.0.50 —
  the old `#album-action-bar` is gone), entered by long-press, and a tap in
  select mode toggles selection even on tiles with a custom onClick (Home
  carousels, Library wall) — EXCEPT a tile with no `offset`, which can't be
  selected and so opens as usual rather than being inert. It must be cleared on every view change —
  `showHome`/`showLibraryWall`/`exitLibraryWall`/`showUnplayedWall`/
  `exitArtistView`/search/labels — or the bar strands over the next screen.
  Track selection has its OWN control (`.t-check`) at the RIGHT of each row —
  a hollow ring, a filled tick when picked — and ONLY that control toggles.
  The row body deliberately does nothing while selecting: a track line carries
  clickable artist links, and a row-wide hit area made following one a coin
  toss. Keep the 44px padded tap target; the ring is drawn on `::before`.
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
- Cold-open cost is dominated by the INDEX, not the connection. The built
  index is cached to `data/index-cache.json`, keyed on LMS's own `lastScan` +
  album count, so a restart doesn't re-page the library; pages are fetched with
  bounded concurrency; and `lib/lms.js` uses ONE pooled keep-alive agent (Node's
  default is keepAlive:false, so every call was a fresh TCP handshake). The
  2.5s poll already self-heals, so "the connection never dies" — don't add a
  reconnect layer, cut work instead.
- "Date added" has NO album-level LMS tag, and `sort:new` is capped by the
  server's `browseagelimit` pref (default 100 albums — rows AND count), so it
  cannot drive a full-library sort. `addedAt` is derived by sweeping the TRACK
  table (`titles` tags `e`+`D`) and taking the EARLIEST added time per album,
  mirroring LMS's own MIN(tracks_persistent.added). The sweep runs in the
  BACKGROUND (never blocks first paint) and is cached in `data/added-times.json`
  under the same signature as the index cache. Unknown `addedAt` is held out of
  the ordering exactly like an unknown `year`.
- Album of the Day / Label of the Week are PERSISTED (`lib/homepicks.js`,
  `data/home-picks.json`) keyed by date / ISO week. Both used to be positional
  (`pool[hash(period) % pool.length]`) over an in-memory array rebuilt on every
  restart, and the label's cache invalidated whenever the label map GREW — which
  the background scan does for as long as it runs. Store the stable IDENTITY
  (album id, label key), never the position, and re-validate on read so a pick
  that no longer exists is re-picked rather than returned.
- Focus facets come from ONE table, `libFacetDefs()` (v1.0.55). Counting
  (`/api/library/facets`) and filtering (`libraryView`) call the SAME `values()`
  function per facet, because they used to be written twice — three bespoke
  `if` blocks against three hand-built Maps — and could silently disagree about
  what a count meant. Adding a facet is now one entry. An album with no value
  returns `[]` and matches only while that facet is unselected.
- Facet values are STRINGS end to end, and a leading `!` means EXCLUDE
  (`facetMatch()`): excludes always win, and a selection made only of excludes
  needs no positive hit. Never parse a facet value — `sanitizeView()` in
  lib/liveplaylists.js used to `parseInt` the decade, which destroyed `!1990`
  before the matcher saw it; validation must split the prefix off, check the
  value, and put it back. The same strings persist into Live Playlists, so the
  wire shape, the stored shape and the matcher agree by construction.
- `addedAt` is epoch SECONDS (LMS tag `D`), not milliseconds. The "Added in the
  last" facet compares it against `Date.now()`, so it must scale first — the
  first cut didn't, and every album read as ~55 years old so no window matched.
  Its windows NEST deliberately: an album returns every window containing it,
  so picking "3 months" can't exclude what arrived this week.
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
- Live Playlists (`lib/liveplaylists.js`) store a saved LIBRARY VIEW, never a
  track list — opening one re-runs `libraryView()`, so there is no second query
  engine. The rule vocabulary is INJECTED from `LIB_SORTS` so a saved rule can
  never name a sort the Library doesn't implement, and every record is
  re-sanitised on READ (not just on write) so an old or hand-edited file
  degrades instead of breaking. Facet values are STRINGS end to end.
  Three traps, all pinned by tests: (a) the edit target must be a PARAMETER of
  `openLibFocusSheet`, never module state, or an abandoned edit silently
  overwrites the next playlist you save — and the Focus pill must call
  `() => openLibFocusSheet(null)`, since a bare handler receives a click Event
  that would arrive as an edit target; (b) opening Edit must apply the saved
  rules WITHOUT persisting them, restoring the user's own view on any dismissal
  (X, backdrop, or a footer button that doesn't commit); (c) saving under an
  existing NAME with no id is a save-over, not a new playlist, and the 50-item
  cap applies only to genuinely new records so edits still work when full.
- Whole-house actions (pause / mute / unmute EVERY zone) live in the ZONE
  PICKER, above "Play on", under an "All zones" heading — not in the side menu
  (v1.0.56). Three menu rows took the list to 16 items / 905px of content and
  pushed Settings off the bottom of a phone. They belong with the zone picker
  anyway, since that is where "which zones" is already the subject. Both
  pickers carry the section (`#mt-zone-popover` and `#np-device-popover`);
  the handler is ONE delegated `[data-all]` listener at app.js top level,
  because those two popovers live in separate sibling IIFEs.
- The side-menu drawer is `height: 100dvh` (with `100%` as the preceding
  fallback) and its bottom reserve lives on `.menu-list`, NOT on `.menu-drawer`
  (v1.0.56). `100%` resolves against iOS Safari's LARGE viewport, so the last
  row sat under the retracted toolbar however far you scrolled; and
  `padding-bottom` on an `overflow-y:auto` container is not added to scrollable
  overflow in WebKit, so the reserve there bought nothing. NOTE: headless
  Chromium reproduces NEITHER — it has no retracting toolbar and no safe-area
  inset — so a Playwright hit test PASSES on the broken build. Verified by
  running the new test against the pre-fix commit. The e2e guards the other
  half (a menu that outgrows the screen); the iOS half is guarded by the CSS.
  Keep the menu under ~800px of content on a 375x667 phone.
- The UI is FLAT everywhere (Home v1.0.39, album modal + Queue v1.0.42). No
  section or panel carries a background fill, radius, padding or watermark
  motif — structure comes from hairline separators and whitespace only, so the
  artwork carries the page. This matches the Roon extension exactly; don't
  reintroduce a tint to "group" anything. `--panel-hairline` survives only for
  genuinely RAISED surfaces (bottom sheets); flat content uses `--border`.
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
- Random Album Radio (`lib/radio.js`, `data/radio-zones.json`) queues WHOLE
  ALBUMS when a player's queue runs down — that is the point of it, and why it
  isn't just shuffle. `radioDecision()` is pure so it can be tested without a
  server: it tops up while the LAST track plays (appending after the queue
  drains would gap), starts something when stopped-and-empty, and never touches
  a paused player. It STANDS DOWN when LMS's own Don't Stop The Music is on for
  that player — two queue-fillers would interleave an album with DSTM's tracks.
  It rides the existing 2.5s poll rather than owning a timer, and `radioBusy`
  holds a per-player lock ~4s past the append so the next tick sees the new
  queue length, not the pre-append one.
- Transport MODES are painted FROM the poll, never from a local guess:
  `playerStatus` reports `playlist shuffle` / `playlist repeat` (LMS spells
  those keys with SPACES), `/api/zone-state` passes them through, and each
  control sends a CONCRETE mode. Another client — or LMS's own web UI — can
  change them, and a client-side toggle would then send the wrong value.
  Repeat cycles off -> queue(2) -> track(1); the badge marks the track case.
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
