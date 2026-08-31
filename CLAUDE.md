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

3. **Never let the iOS display geometry regress.** `lib/safearea.test.js`
   runs in `npm test` and must stay green: the modal family
   (`.modal-close`, `.modal-share`, `.modal-edit`, `.modal-fav`,
   `.modal.np-mode .modal-home`, `.modal.np-mode .modal-body`,
   `.modal-body`'s top padding) must **never** carry
   `env(safe-area-inset-top)`, and `.topbar` / `.menu-drawer` / the bottom
   reserves must always keep theirs. Four releases (v1.0.69, .70, .75, .76)
   shipped fixes that measured GREEN in every harness and all failed on the
   owner's phone, because **no harness here can see this**: headless Chromium
   has no notch, so every inset resolves to 0 and a rule that wrongly carries
   one is indistinguishable from one that does not.
   THE RULES, in order:
   - If that test fails, the change is wrong. Don't "fix" the test.
   - Adding a safe-area inset pushes content OUT of the safe area. If a report
     says a screen should be *overlaying* / *covering* the safe zone, the
     answer is to REMOVE an inset, not add one. That misreading is exactly
     what caused this.
   - When a display bug only shows on the owner's device, **do not theorise
     about iOS** — find the last version that looked right and diff it. The
     Roon build is the reference and **v1.6.50** is the last good one:
     `git -C /workspace/musicd-remote show v1.6.50:public/style.css`
     (`add_repo meltface-80/MusicD-Remote`, then
     `git fetch --depth 1 origin tag v1.6.50` on a shallow clone). Two
     commands, and they would have ended this in one pass instead of five.
   - Don't reach for viewport-unit arithmetic, `dvh` juggling, panel-height
     overrides or a `visualViewport` runtime shim. All four were tried and all
     four were reasoning about a symptom from geometry that was already
     correct. Any new fixed/pinned element still needs an assertion in
     `e2e-v69-safearea.js`, which substitutes real iPhone insets so geometry
     can be measured — but it can only catch a rule that moves, never one that
     shouldn't have.

4. **Bump the version once a new build is ready.** When a feature set /
   fix set is complete and verified, bump `version` in `package.json` and
   commit it as `Release v1.0.x` with short release notes in the commit
   body. **Keep v1.0.x numbering for now** — the owner will advise
   explicitly when to move to v1.1.x or beyond.

5. **The version bump IS the release.** This repo has no tags or GitHub
   releases; the in-app updater resolves the latest version from
   `package.json` on `main` and downloads the `main` tarball. Merging the
   bump publishes the update to every install.

6. **Keep the GitHub Pages site in step with every release.** The docs /
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
  release (§5 above), so an unmerged bump means no install gets the update.
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
  addressed by URL (there is no track_id form, and `title:` must NOT be sent —
  see below), so `/api/playlists/add` loops. That's why TRACK_TAGS carries `u` —
  the url is the only handle. The
  rejected alternative was playlistcontrol-into-the-queue + `playlist save`,
  which clobbers whatever is playing. A track with no url is skipped and
  counted, never fatal. `playlists new` on a NAME COLLISION creates nothing and
  returns `overwritten_playlist_id` — surfaced as `created:false` so the UI can
  say "added to the existing one" instead of implying a new playlist.
- The album screen's PLAYBACK ROW is the app-Favourite heart plus one split
  pill (v1.0.79, owner decision; heart moved to the LEFT in v1.0.81 and back to
  the RIGHT in v1.0.82, where the pill also stopped stretching the full row):
  `[ Play Now | v ] [heart]`. The pill body plays now; the caret opens the
  app's ONE dropdown with Play next / Add to queue. The heart is a SIBLING of
  `#modal-actions`, not a child — the builders wipe that node's innerHTML — so
  its place in the row is decided by DOM order in index.html and nothing else. This REVERSES two earlier notes. (a) The heart used to be an icon in
  the modal chrome, "never a pill in `#modal-actions`", because on a Qobuz
  album it would sit beside the Qobuz heart meaning something else entirely —
  that hazard is real and is now carried instead by the two looking different:
  the Qobuz one is a LABELLED pill that paints pink, this one is a bare
  `--accent` heart that spells itself out in its title/aria-label. (b) The row
  used to be Play Now + Queue + a three-dots `.overflow-btn`; three controls
  for one decision, in a `flex-wrap: nowrap` row that could never have grown a
  fourth. `buildSplitPlayButton(main, items, label)` builds it, and draws NO
  caret when the menu would be empty — a dead affordance on the control the
  screen is built around reads as a broken button. The dropdown machinery is
  unchanged (`openOptionsMenu` → `.dropdown-menu` into `<body>`); it is closed
  from `closeModal()` and `openAlbum()` because rebuilding the row destroys its
  trigger. The Qobuz album path gets the same pill but NO "Play next" row: the
  plugin exposes only play and add, so offering one would be a button that
  lies. App favourites paint with `--accent`; the Qobuz heart stays pink —
  different colour, different system.
  Tiles mark app-favourites via `data-fav-key` + `.is-app-fav`, repainted by
  `window.__repaintFavMarks()` from `/api/favourites/keys` (that endpoint exists
  for exactly this — don't fetch the whole collection to mark a grid).
- The album modal's top corners: BACK at the top LEFT, a flex cluster at the top
  RIGHT. The × became a back chevron and moved left (v1.0.80) — a back
  affordance on the right reads as a dismiss. It keeps `data-close`, which is
  what actually wires it (it has no id, and `.modal-close` is only CSS + the
  safe-area test). It is pinned on its own, NOT a member of `.modal-chrome`, for
  the same reason `.modal-home` isn't: that cluster is an absolutely positioned
  containing block pinned to the RIGHT, so a `left` inside it measures from the
  wrong edge. Back and np-mode's Home share the left slot, which is safe only
  because np-mode hides Back.
  `.modal-chrome` (v1.0.79) holds Share and Edit as a FLEX row rather than
  buttons at hardcoded `right:` offsets. `flex-direction: row-reverse` means DOM
  order runs right-to-left, so Share is written first and sits hard in the
  corner. The point is that whichever button a mode hides or loses, the rest
  CLOSE UP: qobuz-mode hides `.modal-edit` and used to leave a 48px hole at
  `right: 108px`; np-mode needed an explicit `.modal.np-mode .modal-share {
  right: 12px }` to move Share into the ×'s spot; and Share taking that corner
  when Back left for the other side cost no rule at all. All three are now
  automatic — that is the whole reason the cluster exists, so don't reintroduce
  a per-button `right:`.
  The individual rules `.modal-close` / `.modal-share` / `.modal-edit` /
  `.modal-fav` must STAY STANDALONE AND INSET-FREE, even the ones that carry no
  geometry any more: `lib/safearea.test.js` looks each one up by exact selector
  text and hard-fails with "rule not found" if it is renamed, folded into a
  comma list, or given a `>` combinator. Vertical geometry is untouched — 12px
  top, 38px buttons, so `.modal-body`'s 64px top padding still clears them.
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
- The app's favourites are MIRRORED INTO LMS's OWN Favorites (v1.0.83, owner
  decision). Still two stores, and ours is authoritative: it can hold an album
  that is not in the library at all, keys on title+artist so it survives a
  rescan, and is what the heart paints from. The LMS write RIDES ALONG and is
  best-effort — it must never fail the local favourite — but it is never
  silent either: `syncFavouriteToLms()` returns a `reason` CODE
  (ok/no-address/not-connected/failed) and the client words each differently.
  "no-address" is the EXPECTED answer for a catalogue album the library has not
  scanned — LMS has nothing to point a favourite at — so it reads as
  information, not an error; matching on message text to tell those apart would
  break the first time a string changed.
  Verified against the LMS source (`Slim/Plugin/Favorites/Plugin.pm`):
  every `favorites` verb is needs-client 0, so the player id is `""`, NEVER a
  real one. `add` takes url + title and nothing else worth sending (icon is
  re-derived on load for `db:`/`file:` urls; hotkey is dead). `add` does NOT
  de-duplicate on the CLI path — the Perl API does, `cliAdd` bypasses it — so
  `favouritesAdd()` reads the list back first or a second tap makes a second
  entry. `delete` accepts `url:` as well as the documented `item_id:`, and the
  url form is the only stable handle: `item_id` is a POSITIONAL index that
  shifts when anything before it is removed. `favorites items` returns urls
  ONLY with `want_url:1`, and must NOT be sent `menu:1` — with it XMLBrowser
  replaces a `db:` item's url with a coderef and then omits it, so every album
  favourite comes back with no url. A bad param is a status error, which
  `JSONRPC.pm` answers by CLOSING THE SOCKET — it arrives as a bare "socket
  hang up", the same shape as the missing-playlist-dir case. The Favorites
  plugin is `enforce`d in its install.xml and cannot be disabled, so there is
  no "is it installed" probe to write.
- The URL an album favourite is filed under is `extid || db:album.title=…&
  contributor.name=…` (`Slim::Schema::Album::url`), matched by EXACT STRING —
  so the escaping has to be LMS's. LMS bundles its own `CPAN/URI/Escape.pm` and
  puts it first on `@INC`; its unsafe set is `[^A-Za-z0-9\-_.!~*'()]`, exactly
  `encodeURIComponent`'s — so that is the escaper, and `!*'()` must stay
  literal. `favouriteUrl()` therefore PREFERS the server's own answer: LMS 9.0
  puts `favorites_url` on every `albums` row ungated by any tag, carried
  through `albumRecord` → `indexRecord` as `favUrl`. Reconstruction is the 8.x
  fallback only. The artist half is the ALBUM's contributor (tag `a`), not
  `row.artist || row.albumartist`, and `origTitle`/`origArtist` are used rather
  than the display strings — an owner edit renames the row in OUR index while
  LMS still knows the album by its scanned name. The record is resolved by
  OFFSET when the client sends one: `findRecordByName()` goes through
  `search.normalize()`, which folds a symbol-only title to nothing and would
  answer null for exactly the albums v1.0.82 rescued.
- THE HEART MEANS EXACTLY ONE THING (v1.0.82, owner decision): "favourite, in
  my collection", i.e. `lib/favourites.js`. The Qobuz control that used to be a
  second heart — save this catalogue album to the QOBUZ ACCOUNT — is now a
  PLUS, with a tick for "already in the account": `setHeart()` in app.js still
  has its old name (six call sites) but paints `＋ / ✓`, and the album modal's
  pill reads "＋ Add to Qobuz" / "✓ In Qobuz" instead of "♡ Favourite". Smart
  Picks had already settled this convention with its `＋ Add` button; the
  Qobuz surfaces now match it. `--heart` (pink) is likewise RESERVED for the
  app's own favourite — `.modal-fav.is-fav` and `.album-fav-mark`, and nothing
  else. The Qobuz plus paints `--accent`. Before this both systems painted
  pink and were distinguished only by position, which is why the album modal
  putting them in one row was a problem worth solving rather than styling
  around.
- A favourite key must survive a title with NO ALPHANUMERICS (v1.0.82).
  `norm()` strips everything outside `[a-z0-9]`, so "<|°_°|>", "+", "!!!" and
  "÷" all fold to the empty string and `keyFor()` answered null — which meant
  the album could not be favourited AT ALL: `has()` was false so the heart
  painted hollow forever, and `toggle()` bailed with `return false`, which the
  client reads as the NEW state and reported as "Removed from Favourites".
  A failure wearing the wording of the opposite success, on real albums
  (Caravan Palace, Ed Sheeran, !!!). `keyFor()` now falls back to `symFold()`,
  which keeps the symbols. The fallback is on the TITLE ONLY, deliberately:
  the title is what gated null, so it can only rescue albums that had no key
  and therefore no stored row — doing the same on the artist would MOVE the key
  of an already-stored favourite by a symbol-named artist and strand it.
  `public/app.js` carries a SECOND COPY of `keyFor` (no build step, and the
  client decides which hearts paint filled), so `lib/favourites.test.js`
  extracts that copy and runs both over one table — a change to either alone
  fails the build. Album edits, rescued artwork and merges key the same way and
  have the SAME defect; only favourites is fixed.
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
- THE MODAL FAMILY MUST **NOT** CARRY `env(safe-area-inset-top)` (v1.0.78).
  `.modal-close`, `.modal-share`, `.modal-edit`, `.modal-fav`,
  `.modal.np-mode .modal-home` and `.modal.np-mode .modal-body` use plain
  12px/14px, and `.modal-body` uses the plain `padding: 64px 18px 40px`
  shorthand. (Since v1.0.79 the top-right three of those get their 12px from
  the `.modal-chrome` cluster that holds them, and `.modal-fav` from the
  playback row — same numbers, same inset-free geometry; the rules themselves
  still exist standalone because the test looks them up by name.)
  THIS REVERSES v1.0.69 AND v1.0.70, which were wrong: the owner's
  report was that the now-playing screen and the screens with the mini
  transport were "not OVERLAYING the iOS safe zone", and those releases read it
  backwards — a top inset pads content OUT of the safe area, which is the
  opposite of what was asked, and it pushed the whole screen down the display
  by the status-bar height. HOW IT WAS FINALLY FOUND: the Roon build's
  **v1.6.50** is the last version that displays correctly on the owner's phone;
  diffing it against Roon v1.7.43 (which carries the same fault as ours) shows
  every one of these top insets present ONLY in the broken version, and nothing
  else in the vertical box chain differing. Our six rules are now byte-equal to
  v1.6.50's. The insets that REMAIN are in v1.6.50 too and are correct —
  `.topbar`, `.filter-bar`, `.filter-panel`, the browse-sheet pin,
  `.menu-drawer`. BOTTOM insets are untouched everywhere: the home indicator is
  a real obstruction. Pinned by `lib/safearea.test.js`, a STATIC test, because
  headless Chromium has no notch — every inset resolves to 0 there, so a rule
  that wrongly carries one measures identically to one that does not.
- FOUR RELEASES OF WRONG FIXES SIT BEHIND THAT ONE LINE — don't repeat them.
  v1.0.69/70 added padding inside the panels; v1.0.75 re-sized the panel off
  its fixed parent instead of `dvh`; v1.0.76 added a `--app-gap` runtime shim
  that moved every full-screen fixed container off a `visualViewport`
  measurement, and switched the status-bar style. All four measured GREEN in
  every harness and all four failed on the device, because each was reasoning
  about a symptom from geometry that was already correct. v1.0.78 removed the
  shim and the standalone height overrides entirely. THE LESSON: when a display
  bug only shows on the owner's phone, do not theorise about iOS — find the
  last build that worked and diff it. `git -C /workspace/musicd-remote show
  v1.6.50:public/style.css` is two commands and would have ended this in one
  pass. The status-bar style stays `black` (not `black-translucent`): that part
  of v1.0.76 is independently right, since `black` lays the web view out below
  the status bar as an ordinary viewport, which is the geometry v1.6.50 gets.
- OPT-IN FEATURES are gated so the WORK stops, not just the paint (v1.0.72,
  `lib/features.js`). Labels and Smart Picks both reach the network on their
  own schedule, so both default OFF. The gates: ONE funnel for Smart Picks
  (`kickSmartPicks`, and the check sits ABOVE the `force` handling — the
  rebuild button is reachable from any client showing a stale settings pane);
  the labels gate wraps the reseed+scan at the index rebuild, the hourly
  auto-rescan tick, and every write/network route (409). `startSmartPicksMaintenance`
  returns null while off, so there is not even a timer; the settings route
  starts/stops it so enabling needs no restart. TWO DELIBERATE DIVERGENCES from
  the Roon build, both because its version has the defect: (a) SEARCH is
  explicitly gated rather than relying on `labelsIndex.map` being empty — the
  reference relies on emptiness and has a live path that fills the map anyway,
  silently un-omitting labels; (b) the flag is THREE-VALUED (true/false/ABSENT)
  and absent infers from evidence of prior use, so an upgrade cannot switch an
  existing install off — and an UNREADABLE store defers rather than answering
  "no", because writing that down would switch someone off permanently and
  repairing the store would not undo it. Test the gate by COUNTING OUTBOUND
  REQUESTS, not by checking a row is hidden.
- Playlist track rows carry ARTWORK and play FROM THAT TRACK (v1.0.74). The art
  was already on the wire — `/api/playlist/tracks` has returned `image_key` per
  track since it was written — and was simply never drawn. A playlist is the one
  list where consecutive rows are usually DIFFERENT albums, which is why the
  cover and the `artist · album` sub-line matter there and not on an album
  screen. `/api/playlist/play-track` is TWO STEPS because LMS has no "load this
  playlist starting at track N": `playlistcontrol load` then `playlist index N`,
  the same shape as /api/play-from-here. The index is BOUNDS-CHECKED against the
  loaded queue rather than trusted — a playlist can be edited from LMS's own web
  UI between the list being drawn and a row being tapped — and an out-of-range
  index plays from the top rather than erroring, because a tap that clearly
  meant "play this" should not become a dialog.
- Random album radio IS in Settings → Playback again (v1.0.73), reversing the
  v1.0.7 removal. That removal's stated reason was "the backend route was never
  ported" — `lib/radio.js` and `/api/radio` exist now and the feature is live,
  so the reason is spent, and the pill on the now-playing screen was its only
  entry point. It does NOT duplicate DSTM: the two coexist by design, radio
  standing down while LMS's own Don't Stop The Music is on for that player.
  The switch is PER ZONE and follows the zone picker directly above it —
  showing another zone's state there would be worse than not showing it.
- `libView.prefix` must be held OUT of `saveLibView` (v1.0.73). Stringifying
  the whole object persisted it while the loader's sanitiser dropped it again,
  so the stored view churned between two shapes for the same view and the
  Live-Playlist "abandoned edit restores your own view" test caught it.
- The Library NAME FILTER matches on `sortTitle`/`sortArtist` (v1.0.73), i.e.
  `search.sortKey` — the SAME fold A-Z ordering uses, so "The Bends" is found
  under "be". A filter that disagreed with the order it filters would be its
  own bug. It matches title OR artist, is folded server-side into the cache
  signature (an empty prefix must not be set, or the same view gets two cache
  entries), and `libView.prefix` is deliberately NOT persisted: a filter set to
  find one album must not still be narrowing the Library next session with
  nothing on screen to say why. The input is DEBOUNCED — a query per keystroke
  re-fetched a screenful of covers for states nobody asked to see.
- "Recently played" reads the play log's NEWEST rows (v1.0.72,
  `playsLog.recentAlbums`). Two things it must not do: (a) key on title alone
  — "Greatest Hits" is not one album, so it folds on title+artist; (b) confuse
  its 30-day DISPLAY window with the log's ~13-month RETENTION. "Not played in
  6 months", the play-count sort and Focus→Never-played all read further back,
  and the sibling Roon build shipped exactly that conflation and destroyed a
  year of history on one Home visit. It sorts by `ts` rather than walking the
  array backwards: the log is append-ordered in practice, but "newest first"
  is the contract. A play naming an album no longer in the library is SKIPPED,
  never painted as a tile that opens nothing.
- HOME ROWS are server-persisted as an ordered `[{id,on}]` array (v1.0.72) —
  one array, because order and membership are one fact and splitting them is
  how they end up contradicting each other. `HOME_ROW_TITLES` in app.js is the
  single vocabulary (exposed as `window.__homeRowTitles`), matched to each
  section's `data-row` in index.html. Traps, all pinned by the e2e: reordering
  must `appendChild` the EXISTING node (it moves it, preserving every tile's
  listeners) and never rebuild from markup; `applyHomeLayout` must `toggle`
  `.hidden`, never `add`, or a row switched back on stays hidden for the
  session because the renderers write into the inner carousel; `saveHomeRows`
  must REDRAW the settings list after the POST, because the server answers
  with fresh `{id,on}` objects and replacing the array orphans every checkbox
  handler still closing over the old one; an id the stored layout has never
  heard of is appended switched ON, or shipping a new row would mean nobody
  with an existing install ever saw it.
- `/diag.html` REPORTS THE DEVICE'S OWN GEOMETRY (v1.0.71). Three releases of
  safe-area guesswork failed because headless Chromium cannot reproduce an
  iPhone: at 393x852 with real insets substituted the now-playing screen
  measures CORRECT (`tab-album` applied, padding-bottom 48px, panel height ==
  innerHeight, artwork 305px against a 357px cap) while the owner's phone
  shows ~146px of dead space. The page prints, from the real device:
  standalone vs browser, `innerHeight` against `100dvh`/`100vh`/`100svh`/
  `100lvh`, the resolved insets, and then loads the app in a hidden same-origin
  iframe, drives it to the now-playing screen and measures the live rects. It
  is READ-ONLY and self-contained. LEADING HYPOTHESIS it exists to test: that
  `100dvh` under-reports in a standalone PWA — `.modal-panel` is sized with it,
  so if it comes back short, no padding arithmetic INSIDE the panel can reach
  the bottom of the display. (That hypothesis is the one v1.0.75 acted on; see
  below.) Copy uses `execCommand` first (plain http, so the clipboard API
  usually does not exist).
- THE STATUS-BAR STYLE IS `black`, NEVER `black-translucent` (v1.0.76).
  `public/index.html` (and `public/diag.html`, which must MIRROR it or its
  readings do not describe the app). This was THE difference from the Roon
  build: a rule-by-rule audit found the two stylesheets' layout CSS
  byte-identical, the DOM chains identical, and no ancestor in either build
  capturing fixed positioning — the only thing we add that touches VIEWPORT
  GEOMETRY is this meta, and Roon has no equivalent because it ships no PWA
  metas at all and can only run in Safari on iOS. `black-translucent` makes
  iOS run the web view FULL-BLEED under the status bar; installed, that shifted
  the app down the display and took its bottom off the screen, so the mini
  transport and the bottom of the Now playing screen sat below the visible
  area. NOTHING INSIDE THE PAGE CAN FIX THAT — three releases of safe-area
  padding (v1.0.69/70) and panel-height arithmetic (v1.0.75) all measured green
  in every harness and all failed on the device, because the geometry really
  was correct relative to a viewport that had been pushed off the display.
  `black` keeps the app installed and chrome-free but has iOS lay the web view
  out below the status bar as an ordinary viewport — the same geometry Roon
  gets in Safari. `env(safe-area-inset-top)` then resolves to 0 and every top
  reserve collapses to its base value; the bottom inset stays live for the home
  indicator, so don't strip the insets from the rules — they are correct, they
  simply measure 0 in this mode.
- The np RADIO control is an ICON IN `.np-secondary`, never a row of its own
  (v1.0.71, Roon parity): device left, radio centre, volume right. It was a
  labelled pill on its own centred row, which cost a whole row on a screen
  that NEVER SCROLLS and where the artwork is the flexible piece — so every
  extra row comes straight off the artwork. `.np-vol` is `flex: 0 0 auto` for
  the same reason: with `flex: 1` it claimed the slack and pushed the radio
  off centre.
- iOS SAFE AREAS are per-rule, and a rule that forgets the inset CANNOT be
  caught by the e2e harness (v1.0.69). `viewport-fit=cover` is set on both app
  pages and ~30 rules carry `env(safe-area-inset-*)`, so the machinery works —
  what goes wrong is one rule missing it while its neighbours have it. Headless
  Chromium has no notch or home indicator, so every inset resolves to 0 and a
  broken rule measures IDENTICALLY to a fixed one; `e2e-v69-safearea.js`
  therefore fetches the real stylesheet, substitutes concrete iPhone values
  (59px top / 34px bottom) and appends it as an override sheet, then measures
  geometry for real. Any new fixed/pinned element needs an assertion there.
  What was wrong: `.modal.np-mode .modal-home` was the ONLY one of the modal's
  five corner buttons without the top inset, so installed as a PWA (the app is
  `apple-mobile-web-app-status-bar-style: black-translucent`, i.e. drawn under
  the status bar) it sat on the clock; `.modal-body`'s `padding-top: 64px` was
  inset-free while the buttons pinned above it move down by the inset, so they
  collided once the inset passed ~52px; `.toast` at `bottom: 28px` was under
  the 34px home indicator; and `.settings-info-toast`'s hardcoded `bottom:
  88px` did not track the inset, so it rendered BEHIND the transport bar (use
  `calc(78px + env(safe-area-inset-bottom))`, the same anchor as
  `.mt-vol-popover`). The mini transport itself was already right: its
  background reaches the physical bottom edge while its controls are lifted by
  the inset — keep both halves.
- An `env(safe-area-inset-*)` inside an IFRAME is always 0 — insets resolve
  only in the top-level browsing context (v1.0.69). So the embedded LMS
  settings frame could not have fixed itself; the reserve lives on OUR side, as
  `padding-bottom` on `.lmsset-overlay`. Same reason `index.js`'s
  `LMS_EMBED_VIEWPORT` needs no `viewport-fit=cover`.
- Use padding LONGHANDS on anything whose bottom reserve carries a safe-area
  inset (v1.0.69). `main`'s responsive `padding:` shorthands reset
  `padding-bottom`, and the inset-bearing rule survived only by coming later in
  source order — one reordering from silently losing it. `display.css`'s
  `.bottombar` had the live version of this bug: a 3-value shorthand applied
  `env(safe-area-inset-left)` to BOTH sides, so the wrong edge was padded in
  landscape.
- The mini transport is HIDDEN while the side menu is open (`body.menu-open`,
  v1.0.66) and no z-index will do it instead. `.app` is `z-index: 0`, i.e. its
  own stacking context, and `#menu-overlay` lives INSIDE it while
  `#mini-transport` is a root-level sibling at 70 — so the overlay's 95 is
  scoped to .app and can never rise above the bar. With something playing on a
  360x740 phone the bar sat straight over Settings, and scrolling was no
  rescue: the drawer's content FITS (scrollHeight === clientHeight), so the row
  was occluded, not clipped. The v1.0.56 e2e passed on the broken build because
  its fake LMS reported `mode: "stop"` and the bar never appeared — ANY menu
  test must assert the transport is really up, and must wait for it: the first
  poll fires before `#zone-select` is populated and the loop then backs off to
  6s, so the bar cannot exist before then.
- THE APP IS GLASS (v1.0.84), ported from the Roon build's v1.7.81–v1.7.89 arc.
  FOUR TOKENS, in every palette, and `lib/glass.test.js` recomputes them rather
  than trusting the file:
  `--bg-veil` is THE palette's own `--bg` written as `rgba()` at `.84` — never a
  lighter colour of its own. That identity is the whole design: over an
  unscrolled page the backdrop IS `--bg`, so a veiled bar composites to exactly
  the page and there is no seam at the top of any screen; once the page moves,
  album art passes underneath and tints it. The Roon build shipped a lighter
  translucent surface (`--glass-bg`) for one release and had to take it back
  out — two materials meant to look alike never quite did. `--glass-edge` is the
  lit 1px edge and is LOAD-BEARING, not decoration: where nothing is behind a
  veil it settles to the page colour, and that border plus the drop shadow are
  all that stop it reading as a hole. `--glass-fill` / `--glass-fill-strong` are
  a white/ink wash for CONTROLS sitting on a veiled surface — a control painted
  `--bg-veil` on the page ground composites to exactly the page and vanishes.
  Those two are OURS, not the reference's: the Roon build never glassed Settings.
  `--bg-translucent` is gone (it was the top bar's, and `--bg-veil` replaced it).
  NEVER ADD A `backdrop-filter` TO ANYTHING THAT SITS OVER A SCROLLER — the top
  bar, the transport pill, the volume/zone popovers, the sheets, any sticky
  band. iOS Safari re-samples and re-blurs every pixel beneath one on EVERY
  scroll frame, and `saturate()` does not only soften the backdrop, it BRIGHTENS
  it, so a filter toggled on scroll is visibly two different bars over a wall of
  covers. The Roon build shipped that twice and removed it twice. Dismiss
  BACKDROPS keep theirs and are at 14px: nothing moves behind a scrim, so it is
  composited once. The five 2px blurs left are the TILE CHIPS, welded to a cover.
  TWO SURFACES ARE DELIBERATELY OPAQUE: `.dropdown-menu` and the toasts. They
  float over LIVE CONTENT with no scrim and carry TWO TIERS OF TEXT (a row plus
  the sentence under it), and at 84% over album art the second tier stops being
  readable — measured, not guessed. They keep the lit edge so they still belong
  to the set. Same rule for the wall display's `.playpanel` in `display.css`,
  which stays hand-written (no tokens: the wall is always dark and has no
  palettes) but is matched to the dark palette's numbers.
- THE TOP BAR OVERLAYS THE SCROLLER (v1.0.84): `position: absolute` on `.app`,
  not a flex sibling of `<main>`. That is what puts album art behind it — before
  this it could never be see-through, because nothing was ever behind it. Three
  parts, and all three are needed: (a) the bar is absolute; (b) `<main>` reserves
  its height in a `padding-top` rule that comes AFTER all three responsive
  `padding` shorthands, or one of them resets it; (c) anything else that was an
  in-flow child of the shell moved INSIDE `<main>` (the Docker banner and the
  update toast), or it would sit under the bar. `--topbar-h` is MEASURED by a
  ResizeObserver at the end of app.js and written onto `.app` — the height is not
  a constant (it grows with the status-bar inset, and changes when the search row
  hides or `#album-select-row` replaces the normal one). The `:root` value is
  only the one frame before that runs. `.filter-bar` and `.filter-panel` read the
  same variable; they used to hard-code 56px/60px and drift.
- DISMISS CONTROLS NEVER SCROLL AWAY (v1.0.84, owner decision). THREE
  MECHANISMS, and which one a screen uses is decided by WHAT SCROLLS on it:
  (1) the scroller is `<main>` → the control lives in `.topbar`, which is pinned
  and glass, or in a band that sticks to the top of the scroller
  (`.labels-bar`); (2) the screen is a full-screen PANEL → the control is
  absolutely positioned on `.modal-panel`, outside `.modal-body`
  (`.modal-close`, `.modal-home`, `.modal-chrome` — these were always right);
  (3) the screen is a SHEET, i.e. the sheet itself is the scroller → the head is
  `position: sticky; top: 0` and takes THE SHEET'S OWN VEIL (two 84% layers
  composite to 97.4%, so rows passing under are invisible AND there is no step
  between head and sheet; an opaque head on a translucent sheet is a seam).
  A SINGLE-LAYER sticky band over live content — `.filter-bar`,
  `.track-select-row`, `.labels-bar` — must stay OPAQUE: one veil there ghosts
  the rows at 16%.
  TRAP, and it cost a pass: a sticky child's offset is measured from the
  SCROLLER'S CONTENT BOX, and `<main>`'s content box already starts below the
  overlaid bar. `top: var(--topbar-h)` inside `<main>` therefore counts the bar
  twice and parks the band a bar-height too low. Use `top: 0`.
  That is why the ARTIST VIEW'S Back is now `#topbar-back` rather than a band of
  its own: `window.__setTopbarBack(fn)` overrides where the shared chevron goes
  (the artist view returns to the screen it was opened from, not Home), and
  `setTopbarNav()` clears the override so a screen that leaves without its own
  teardown cannot send the next Back somewhere unrelated. `.artist-view-back` is
  gone and the test fails if it comes back.
  The four overlays that were missing from the old id list — Favourites, Merged
  albums, Playlists, Dynamic Playlists — carry exactly the `.qobuz-sheet` /
  `.qobuz-pin` markup and were 86vh with an unpinned head. The rules are keyed
  on the CLASS now, which cannot fall out of step with the markup the way that
  list did. Settings home and the Filter sheet had NO dismiss control at all
  (backdrop or Escape only); both have a close button now, and the
  `data-settings-close` handler had to move from `hasAttribute` to `closest`
  because a tap lands on the SVG inside the button.
- THE TRANSPORT IS A FLOATING PILL (v1.0.84). LIFT OR PAD, NEVER BOTH: its
  `bottom` carries `env(safe-area-inset-bottom)`, which moves the whole pill
  clear of the home indicator; the old full-bleed bar padded instead, and doing
  both puts 34px of dead glass INSIDE the pill on any device that has one.
  `lib/safearea.test.js` counts the inset across EVERY `.mini-transport` block
  and requires exactly one, because headless Chromium reports every inset as 0
  and measures a perfectly proportioned pill either way. The COVER (`#mt-art`,
  54px) is the pill's height driver and every control is sized under it, which
  is how the bar got taller without the extra room becoming empty glass. The
  progress line is the TOP BORDER of a full-height fill clipped to a pill-shaped
  box, not a 2px strip: CSS scales a radius down until two corners fit their
  side, so an 18px radius on a 2px strip becomes 2px and the line overhangs the
  glass at both ends. The clip lives on `.mt-progress`, never on
  `.mini-transport` — that element's overflow must stay visible or the zone and
  volume popovers cannot open upwards out of it.
- NOW PLAYING LEADS WITH THE ARTWORK (v1.0.84). Full-bleed, `object-fit: cover`,
  no radius, no shadow, masked to transparent at the bottom so the title and
  transport sit in the tail of the image. THE BLEED ARITHMETIC HAS TO STAY
  EXACT: `width: calc(100% + 36px); margin: 0 -18px` are literally
  `.modal-body`'s 18px horizontal padding, and `.modal-body` declares
  `overflow-y: auto` — a box with one axis scrollable and the other `visible`
  computes the visible one to `auto`, so a bleed ONE PIXEL wider than the
  padding it cancels does not spill, it gives the whole screen a horizontal
  scrollport. The fade is a MASK, not an overlaid gradient: masking to
  transparent lets whatever `--bg` is for the current palette come through, so
  one rule works in all four. TEXT NEVER OVERLAPS THE ARTWORK — two palettes
  have a near-white ground and near-black text, so a title over an unknown
  sleeve is invisible on the first cover tried. Landscape ≥720px puts the framed
  card back (contain, radius, shadow, `mask-image: none`); keep
  `.modal.np-mode.tab-album .modal-body { overflow: hidden; flex: 1 1 auto;
  min-height: 0 }` and `.modal-art { flex: 1 1 0; min-height: 0 }` or the art
  collapses to zero height. The ALBUM view was deliberately NOT given the same
  hero: that would need `.modal-body`'s `padding-top: 0`, and `lib/safearea.js`
  pins that rule's shorthand.
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
- The SAME NAME means different things in different `app.js` IIFEs (v1.0.57).
  In the main IIFE `selectedZoneId` is a string variable; in the mini-transport
  IIFE it is a FUNCTION that reads `#zone-select`. The v1.0.54 shuffle / repeat /
  radio handlers landed in the transport IIFE and used it bare: the truthy guard
  passed (a function is truthy) and `JSON.stringify` then DROPPED the key
  entirely, so `/api/radio` answered 400 and the mode commands went to whatever
  player the server defaulted to. Always CALL it there. The e2e missed it because
  the fake LMS ignored player identity — a transport test must assert WHICH
  player the command named, not just that the command was sent.
- The Library control row is FLAT and Focus-first (`.lib-ctl`, v1.0.57), matching
  the Roon extension: Focus carries a COUNT BADGE (`.lib-ctl-badge`), Sort
  carries the direction arrow inside its own label, and there is no separate
  direction button — re-tapping the selected sort row reverses it. The old
  `.lib-pill` / `.lib-dir-btn` pair is gone; anything selecting controls
  positionally must remember Focus is index 0 and Sort index 1.
- A STREAMING track has no cover in LMS's database, and asking for one gets you
  a PLACEHOLDER AT HTTP 200, not a 404 (v1.0.60). `Slim::Schema::RemoteTrack`
  sets `id => -int($self)` and `coverid => id`, so tag `c` answers a NEGATIVE
  number; `Slim::Web::Graphics` sees the leading "-" and serves
  `html/images/radio_<size>.png` — its grey radio tower — with a 200. So the
  image loads, `onerror` never fires, and a Qobuz playlist's mosaic fills with
  towers. `coverKey()` therefore treats a negative id as NO cover (local ids are
  8-hex, `artwork_track_id` is a positive track id — nothing legitimate is
  negative) and falls through to `artwork_url`. Tag `K` does NOT return the
  origin URL either: it returns `proxiedImage($url)` = a RELATIVE
  `/imageproxy/<enc>/image.jpg`, which re-wrapped in another imageproxy call
  404s — so `coverKey()` unwraps it, and returns null for anything that isn't
  absolute afterwards rather than minting a key that resolves to a placeholder.
  ANY fixture for this must use the real shapes; the old one used a raw absolute
  `artwork_url`, which no live server sends, and every one of these defects
  passed because of it.
- `playlistArt()` stops on `want` COVERS, never on `want` ALBUMS (v1.0.60).
  Stopping at four albums meant a playlist whose first four albums carried no
  usable cover returned ONE cover and a mosaic of placeholders. It dedupes on
  album+artist (album alone collapsed two different records sharing a title) and
  SKIPS a row with no album name — keying those on the cover made an album-less
  playlist show four different placeholders, because a remote row's pseudo-id is
  unique per track.
- `playlistArtCache` must be dropped for any playlist that is WRITTEN to
  (`forgetPlaylistArt()`, called from create/delete/rename/add/share-save), and
  an EMPTY probe is never cached at all (v1.0.60). The comment used to claim it
  was keyed on the track count as well; it was not, and a probe that ran while a
  playlist was still empty — exactly the state between "created" and "tracks
  appended", which the add-to-playlist sheet's own listing can observe — pinned
  "0 tracks" and a blank mosaic on the tile for the full 10-minute TTL while
  opening that same playlist showed its tracks. Not caching an empty result is
  the half that does NOT depend on our knowing about the write: LMS's web UI,
  the Material skin and the Qobuz plugin all fill playlists without telling us.
  A generation counter stops a probe already in flight writing its stale answer
  back over an invalidation.
- Playlist / Live-Playlist mosaic tiles get their four covers from the INDEX, not
  from LMS artwork ids: `lms.playlistArt()` returns `albums:[{album,artist,cover}]`
  and `findRecordByName(title, artist)` resolves each to a record whose image key
  the tile can use. A stored playlist's tracks can name albums LMS gives no
  usable cover id for, which is why the mosaics were blank.
- Playlist SHARES are an INTEROP CONTRACT with the sibling Roon build
  (`lib/share.js`, v1.0.58) — both apps read and write `MDRP1:<base64url(gzip(
  JSON, level 9))>`, a JSPF/ListenBrainz document. Every coercion is matched to
  that implementation and changing the semantics stops shares round-tripping:
  the marker is found ANYWHERE after all whitespace is stripped and matched
  case-INSENSITIVELY (iOS autocorrect lowercases it on paste) while the payload
  is left untouched (base64url is case-significant); trailing prose is separated
  by SHAVING up to 40 characters and retrying, because gzip's CRC is the only
  thing that can tell the sender's words from the payload; empty values are
  OMITTED, never `""` (in JSPF an empty string is a positive claim); and
  `playlist.track` is assigned AFTER pruning because an absent list means
  malformed where an empty one means a playlist with no tracks. Widening the
  format means MDRP2, never redefining MDRP1. A share describes MUSIC — no LMS
  id, offset, image key or file URL may enter one, which `shareTrackEntry()`
  enforces by building a fresh literal from a fixed field list. Import is
  ALBUM-granular through `findRecordByName()` (which refuses a coin toss); the
  track is matched by title only at SAVE time, because a stored LMS playlist is
  addressed by track URL and nothing else can supply one. Owner decision: import
  builds the playlist from whatever matched and ignores the rest — the misses
  are listed, never silently dropped. Copy uses `execCommand("copy")` FIRST and
  `navigator.clipboard` as the fallback: the app is served over plain http, so
  the secure-context API usually does not exist at all.
- Album QUALITY (`quality`/`hires`) rides in on the added-time sweep, not a
  second pass — the format tags (o/r/T/I) arrive on the very same `titles` rows
  as tag D, so `albumAddedTimes()` returns `{added, format}` and both land in
  `data/added-times.json` under one signature. A cache written before formats
  existed still supplies the added times and simply re-sweeps for the rest.
  Owner decision: the album's format is its FIRST track's (lowest tracknum).
  LOSSY MUST NOT PRINT A DEPTH: an MP3 reports 16/44.1 because that is what it
  decodes to, so `albumQualityLabel()` returns the container name for anything
  outside `LOSSLESS_TYPES`. The badge value is ALWAYS in the payload and shown
  or hidden by one class on `<body>` (`show-quality`, Settings → Appearance,
  default off) — rendering it conditionally would leave every tile already on
  screen showing its old state until something rebuilt it, so the toggle would
  look broken until you navigated away and back.
- Live Playlists carry `limit` and `order` BESIDE the view, never inside it
  (v1.0.58): two playlists can share a rule set and differ in both, and folding
  them into the query would make `libraryView()`'s memo — keyed on the query
  alone — hand them one cache entry and one limit. `livePlaylistAlbums()` is the
  single ordering function so the screen that LISTS a playlist and the button
  that PLAYS it can't disagree; it returns UNSLICED because the caller's slice is
  what makes "100 of 1,179" honest, and a random playlist of 100 must be 100
  drawn from the whole match rather than the first 100 by title then jumbled.
  The shuffle is SEEDED off `view.seed` (paging would otherwise repeat and skip).
  There is deliberately no "unlimited": 0 or garbage falls back to the default
  (100), not to unbounded. `total` is what it delivers, `matched` what the rule
  found — reporting only the second made every capped playlist read as a failure.
- Track multi-select uses the SAME Options dropdown as albums (v1.0.58); the old
  `#track-action-bar` is gone. Its trigger lives in `#track-select-row` above the
  track list, NOT in the top bar, because the album view paints over the top bar
  and a row up there would be invisible from the one screen that needs it. The
  menu is rendered into `<body>` off the button's rect and `albumOptionsOwner`
  records which button opened it, so `exitTrackSelectMode()` can tear down a menu
  that would otherwise outlive its row. Note for tests: `addLongPress` on a track
  row ALSO selects the row it was held on, so a long-press leaves one already
  picked.
- Stored-playlist WRITES depend on the server having a Playlists FOLDER, and a
  server without one fails in the most confusing way possible (v1.0.59).
  `playlistsNewCommand` bails with `setStatusBadConfig()` when `getPlaylistDir()`
  is empty, and `Slim/Web/JSONRPC.pm` answers ANY status error by CLOSING THE
  SOCKET rather than returning an error payload — so it reaches us as a bare
  "socket hang up". Same failure shape as a missing plugin verb. `playlistCreate`
  therefore probes `pref playlistdir ?` ONLY AFTER a create has already failed
  with a socket error, never before: a preflight that blocked on the probe would
  turn any server whose pref answer we mis-parsed into one that cannot make
  playlists at all — trading a confusing message for a broken feature. The
  answer is cached and dropped on every `refreshConnection()` so fixing the
  setting doesn't need an app restart.
- NEVER send `title:` with `playlists edit cmd:add` (v1.0.59). Verified against
  LMS's own `playlistsEditCommand`: the `add` branch does
  `$playlistTrack->title($title)` + titlesort + titlesearch + `->update`, i.e. it
  RENAMES THE LIBRARY'S OWN TRACK ROW, not the playlist entry. That is a metadata
  write back into LMS, which this app never does. `url:` alone is sufficient and
  is what the Material skin sends. The command also DE-DUPLICATES on url (a track
  already in the playlist is silently not appended, and still reports success),
  and each add rewrites the whole list and wipes LMS's caches — so adds must stay
  sequential.
- `/api/playlists/add` resolves track indices through `tracksForRecord()`, NOT
  `albumTracks(rec.id)` (v1.0.59). Track identity is (offset, array index) and
  the client's indices come from `/api/album`, which walks every `partIds` entry;
  `albumTracks` returns only the PRIMARY part, so on a MERGED multi-disc album a
  disc-2 pick either added the wrong track or fell off the end — and if every
  pick was on a later disc the request 409'd with "Nothing to add", which read to
  the user as the playlist silently not being created.
- A failed playlist add must still TEAR DOWN multi-select (v1.0.59). The
  `window.__afterPlaylistAdd` teardown used to sit after the `throw` in `send()`,
  so any non-2xx left the user stranded in select mode with the selection still
  lit and no obvious way back — reported as "the UI is stuck", which is really
  "the server call failed". It now runs in a `finally` and the handle is nulled
  so a stale teardown can't fire against a later selection. Same defect had
  applied to the album path.
- `openLibSheet()` restores the body scroll-lock it FOUND, never `""`
  (v1.0.59): a sheet can be opened over the album modal, which sets
  `overflow:hidden` itself, and blanking it unlocked the page behind a still-open
  modal.
- Import lives in the SIDE MENU (owner decision, v1.0.61), matching the Roon
  build — it is a thing you DO, not a place you browse. The menu now mirrors
  Roon v1.7.43's list (v1.0.62): Home / Random albums | Labels, Favourites,
  Merged albums, Qobuz, Pitchfork, Dynamic Playlists, Playlists, Import a
  playlist | Rescan library | Settings. Favourites and Merged albums are this
  app's own and have no other entry point; Roon's whole-house zone rows stay in
  the zone picker (v1.0.56). The v1.0.56 e2e measures the budget with the Qobuz
  row FORCED VISIBLE — the fake reports the plugin absent, so measuring as-is
  read the menu one row shorter than the owner's phone. 817px of 900.
- DELETING a stored playlist is the one action in this app that destroys
  something irrecoverably (v1.0.61). LMS's `playlistsDeleteCommand` →
  `_wipePlaylist` → `removePlaylistFromDisk` does a bare `unlink` of the .m3u —
  no trash, no backup, and a rescan restores nothing. It does NOT touch music
  files or library track rows (the read-only rule holds). So: the confirm names
  the playlist AND its track count and says plainly that it cannot be undone.
  Delete is OFFERED ONLY for a playlist the owner made — `isOwnPlaylist()`
  requires no `extid`, `remote` falsy, and a url that is a playlist FILE
  (`file:` or a bare path ending .m3u/.pls/.xspf); any other scheme, or no url
  at all, hides the button. It FAILS CLOSED because a missing button is an
  annoyance and the alternative is someone's playlist collection. Two reasons it
  must never be offered on an imported playlist: `removePlaylistFromDisk` falls
  back to unlinking `<playlistdir>/<title>.m3u` when the recorded path doesn't
  exist (which is every remote playlist), so it can take a same-titled LOCAL
  file with it; and deleting one is either useless (the plugin re-imports it on
  the next online-library scan, which begins by wiping and re-fetching all of
  them) or misleading (it makes no Qobuz API call, so the playlist is still in
  the account). The provenance comes from `tags:uEx` on the `playlists` query —
  `extid` and `remote` are what actually answer the question; a title prefix is
  cosmetic. A failed delete is INDETERMINATE, never a clean failure: a stale id
  makes LMS close the socket, and the id goes stale precisely because the
  playlist is already gone — so the client returns to the list and re-reads
  rather than asserting either outcome.
- UI PARITY WITH THE ROON BUILD is checked against the FULL files, not a cached
  copy (v1.0.62). The copies in the scratchpad had been truncated (app.js 365KB
  against a real 474KB) and three "gaps" derived from them were wrong in both
  directions: the album action row really IS 2 pills + an overflow menu, the
  Home unheard tile really IS in the Roon build, and the side menu really has
  NO "Filter" or "Play something unheard" row. Re-download before auditing:
  `raw.githubusercontent.com/meltface-80/MusicD-Remote/<branch>/public/<file>`.
- "Play something unheard" is the FIRST TILE of the Not-played row plus the
  top-bar icon — not a side-menu row (v1.0.62, matching Roon). It is built
  AFTER `renderHomeUnplayed`'s empty check, deliberately: appending it ahead of
  that check made `rowHasContent()` always true, so the "Loading…" placeholder
  never showed and a failed load was never retried. Any test that clicks "the
  first .album" must exclude `.home-unheard-tile`.
- The Focus sheet's `openSections` is THREE-VALUED (`{}` with undefined =
  untouched), never a Set (v1.0.62). A boolean Set recomputed `activeCount > 0`
  on every repaint, so tapping the header of a section that had filters in it
  re-opened it instantly — it read as stuck. Once the user writes a value it is
  authoritative. Held across repaints, dropped on each opening.
- Focus chips DO NOT re-run the wall (v1.0.62). `applyLibView()` is called once,
  from "Show albums" — a tap-per-query fired a request for every intermediate
  state the user never asked to see. The e2e counts `/api/library/albums`
  requests, because the difference is invisible in the DOM.
- The theme picker is STAGED: pick, then Apply (v1.0.62). The row that is
  actually applied says "· in use" in its own label; the tick follows the
  PENDING choice. `pendingThemeId` is cleared on every Settings open so the
  sheet never reopens mid-decision. The swatch carries `data-theme`/
  `data-palette` and reads `--bg`/`--accent` off ITSELF, so a preview cannot
  drift from the stylesheet — the old version resolved them through a hidden
  probe element.
- Format / Sample rate / Bit depth facets ride in on the added-time sweep
  (v1.0.62): `stampAlbumFormats()` keeps `fmtType`/`fmtRate`/`fmtBits` beside
  the derived `quality` badge, so they cost no extra LMS traffic. There is NO
  Channels facet — the `titles` sweep carries no channel count and asking per
  track would be a query per track. `lib/liveplaylists.js` FACET_IDS must be
  kept in step or a saved playlist silently drops the new rules.
- SMART PICKS (v1.0.63) is six albums a day by artists NOT in the library:
  five "adjacent" from the neighbourhood it lives in, one "stretch" from a
  genre it barely touches. `lib/smartpickalgo.js` holds the CHOOSING as pure
  functions (like `radioDecision()`), `lib/discovery.js` the three keyless
  public reads (ListenBrainz similar-artists + world chart, MusicBrainz tag
  roster), `lib/smartpicks.js` the JSON store, `lib/smartcache.js` the TTL
  cache; index.js is the I/O around them. THE IDEA THAT MAKES IT WORK:
  similarity quality INVERTS with seed popularity — seed from the library's
  biggest names and you get Nirvana/Coldplay, seed from its obscure end and you
  get real finds. So seeds are the library's least famous WELL-PLAYED artists,
  "famous" is decided by the world listen chart, and if that chart can't be
  fetched THE WHOLE BUILD ABORTS rather than inverting.
- "Already owned" means the WHOLE collection, not `index.records` (v1.0.66,
  owner decision). `smartLibraryArtists()` folds in the streaming service's own
  favourites and this app's favourites, because an album favourited in Qobuz
  but never scanned exists in NEITHER the LMS index nor anywhere else the
  exclusion set could see it — and it came back as a "discovery". The Qobuz row
  artist carries a trailing year, so it must go through `qobuzRowArtist()`
  first or nothing matches. The service read is best-effort and must never fail
  a build: losing it costs precision, letting it throw costs the whole day.
- The STRETCH pick is TWO HOPS OUT IN THE TASTE GRAPH, not a genre (owner
  decision, v1.0.68). It was chosen by "a genre the library barely touches",
  and on the owner's real library that could never work: album genre comes from
  the file's GENRE tag via `albumGenre()`, that library carries essentially
  none, and `smartStretchGenres` therefore had nothing to select however the
  threshold was tuned (v1.0.66 tried two fixes; both were real defects and
  neither was the cause). Hop 1 is everything similar to a seed — where the
  five adjacent picks come from; hop 2 is what those near-neighbours are
  similar to. A candidate qualifies only if NOTHING near the library reaches it
  directly: not a seed, not a hop-1 artist, not anything owned (`nearCanons`,
  built from `cands` UNFILTERED — an already-owned hop-1 artist is still
  directly reachable, so what it reaches is not two steps out). It reuses
  `smartSimilarRows`, so hop 2 shares the same per-mbid 30-day cache as hop 1.
  `rankStretchCandidates` sorts MOST referrers first — the OPPOSITE of
  `rankSmartCandidates`, deliberately: everything here is already two hops out,
  so the question is "is this a real cluster" not "how far". There is NO
  minimum referrer count, because every hard filter added to this path has
  turned into a new way for the stretch to vanish. `smartPickReason` still
  emits the old genre wording when a stored pick carries `genre` — a reason is
  written once, at build time, and last week's card must not re-describe itself
  under the new rule. The genre helpers (`smartStretchGenres`,
  `genreTagCandidates`, `smartGenreWeights`, `smartTagArtists`) are GONE;
  `discovery.artistsByTag` survives unused, to stay in step with the Roon build.
- The stretch pick EXPLAINS ITSELF, in the app and in a diagnostic (v1.0.67).
  Naming the stage in the log was not enough: reading it means finding a log
  file, and five cards with no explanation still reads as broken. The build's
  reason is carried on the attempt record → `/api/smart-picks` `stretch_note` →
  a quiet `.pick-note` footnote under the cards. `GET /api/smart-picks/debug`
  walks the SAME pipeline and returns every decision (seeds, hop-1 size before
  and after exclusions, which near-neighbours were stepped beyond, the hop-2
  field, and per candidate its referrer count and WHICH exclusion caught it) — it writes nothing, so it is safe to open any
  time. `?resolve=1` adds the Qobuz lookups, bounded to 5 per genre because
  each is a menu walk. It holds TODAY'S OWN PICKS out of the `seen` set:
  marking a pick shown is the last thing a build does, so running the
  diagnostic straight afterwards — exactly when anyone runs it — would
  otherwise find every candidate "shown recently" and report an empty graph.
  Unlike `/api/qobuz/debug` it is NOT gated on debug logging: that one echoes
  raw plugin payloads and player ids, this one reports artist names from the
  owner's own library.
- A Smart Pick is PLAYED DIRECTLY, never added first (v1.0.65). Verified in the
  plugin source: `qobuz playlist play item_id:…` → `QobuzGetTracks` →
  XMLBrowser's playlist branch → `playlist loadtracks`, and nothing on that path
  consults the user's favourites or writes to the library (a remote track is an
  in-memory `Slim::Schema::RemoteTrack`). The Roon build MUST add before it can
  play, because Roon's API only plays what is in the library — that is THEIR
  constraint, and carrying it over here was a porting mistake. Add survives as a
  KEEP action (favourite it; a later scan imports it).
- Two Smart Picks constraints are forced by LMS and differ from the Roon build.
  (a) A pick CANNOT carry a stored handle: the LMS Qobuz plugin exposes no album
  ids, only menu nodes that expire with the session — so a pick stores artist +
  album TITLE and BOTH `/api/smart-picks/play` and `…/add` re-resolve by search
  at the moment they are tapped, through the one `smartResolveRow()` so the two
  buttons on a card cannot mean different albums. `smartResolveAlbum` returns
  the live `favItemId` but STRIPS it before caching. (b) Every `qobuz` dispatch
  is needs-client=1, so a build needs a CONNECTED PLAYER; with none it is
  skipped WITHOUT marking the day attempted, so it retries rather than writing
  the day off.
- `.pick-block` is the DISMISS button only (it blocks the artist). Secondary
  pills share its skin as `.pick-secondary` — reusing `.pick-block` for Queue
  and Add made "the dismiss button" un-selectable, which an e2e caught.
- Auto-add defaults OFF here, ON in the Roon build (v1.0.63). There it makes
  Roon import overnight; on LMS favouriting is favourite-only (no rescan,
  owner decision v1.0.22), so it would write five albums a day into the owner's
  Qobuz account unasked and they still wouldn't play until a scan.
- The Qobuz menu row's ARTIST carries a trailing year — the label is
  "Album\nArtist (Year)" and `qobuzTitleArtist` keeps the whole second line. Any
  identity check against it must strip that first (`qobuzRowArtist`, and
  `qobuzFavKey` does its own): without it `artistKey("Labradford (1997)")` never
  matches and every resolve silently returns nothing.
- ONE MusicBrainz rate gate for the whole app (`lib/musicbrainz.js`, v1.0.63).
  albumart, albuminfo and labels each held their own `mbLast` timestamp, so
  three lookups could fire in the same second — and WITHIN one module the
  timestamp compare let N concurrent callers all read the same `last` and go
  together (measured: 4 callers, all at 1103ms). The shared gate serialises on
  a promise CHAIN instead. Smart Picks is the heaviest consumer (~24 seed
  lookups a build), which is what forced the fix.
- `enterFullWall(title)` is the shared entry ritual for every screen that takes
  over `#album-grid` (Not played, Library, Smart Picks) — it clears the other
  views, drops the genre filter, sets the top-bar chrome and TAKES THE GRID
  EMPTY. That last part matters: each wall paints asynchronously, so inheriting
  the previous one's content leaves it on screen until the fetch lands.
  `showLibraryWall` must set `libraryWallActive` AFTER calling it, because
  `enterFullWall` calls `exitLibraryWall()`.
- The app is an INSTALLABLE PWA wearing the MusicD duck (v1.0.64):
  `public/manifest.webmanifest` + `public/icons/`, generated from the owner's
  logo. `any` and `maskable` are SEPARATE artwork, never one file listed twice
  — Android crops a maskable icon to a shape of its choosing and only
  guarantees the central 80%, so the maskable pair is drawn at 0.72 inset to
  sit inside that safe zone while `any` fills the tile at 0.88. iOS reads NONE
  of the manifest's icons, so `apple-touch-icon` + the apple-mobile-web-app
  metas are what make an iPhone install it rather than bookmark a screenshot.
  The 16/32px favicons get a contrast boost before they are committed: the
  logo is a fine engraving and its hatching averages to grey mush at that size.
  Regenerate from a new logo with `tools/make-icons.py <logo.png>`; don't
  hand-resize, or the sizes stop framing the art identically.
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
  the client never submits a raw LMS command. NOT yet generalised to Tidal/Deezer
  (v1.0.65 audit): the MECHANISM is service-neutral — `menuAction` handles both
  Qobuz's response-level `base.actions` and Tidal's per-item `itemActions` — but
  every browse/search/favourite function hardcodes the `qobuz` verb and none
  takes a tag. Known blockers if the tag were simply swapped: Tidal's root has
  NO favourites node and its album menu no favourite child (both favourite
  functions need a SECOND IMPLEMENTATION, not a parameter); its root "MY MIX" is
  `type:playlist`, which `qobuzBrowse` would render as an album; explicit albums
  get " [E]" welded onto the title, breaking `qobuzFavKey` matching; its search
  categories are localised AND carry no icon, so `isAlbumCategory` fails on a
  non-English server; and `_qobuzSearchNode` is a single un-keyed module slot,
  so a naive tag parameter would let one service reuse the other's cached search
  node. Tidal DOES expose durable album ids (`tidal://album:<id>`), so the
  expiring-token design is a Qobuz limitation, not a general one. Result covers reuse the `url-…` image_key →
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
