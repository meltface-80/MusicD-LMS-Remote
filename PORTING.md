# Roon → LMS porting blueprint

How the Roon build maps onto Lyrion Music Server, and the per-route status of the
port. The guiding rule: **keep every `/api/*` response shape identical** to the Roon
build so the shared PWA frontend runs unchanged; only the backend behind each route
is swapped.

## Primitive mapping

| Roon primitive | LMS equivalent | Adapter method |
|---|---|---|
| Pairing / `core_paired` | none — HTTP host:port (+ UDP 3483 discovery) | `createLms`, `discover`, `ping`, `serverStatus` |
| Browse `albums` hierarchy (paged) | `albums` DB query with `tags:` | `listAlbums`, `countAlbums` |
| Album's tracks (drill in) | `titles album_id:<id>` | `albumTracks` |
| `RoonApiImage.get_image(key)` | HTTP `GET /music/<coverid>/cover_<s>x<s>_o.jpg` | `artworkUrl` (+ server-side proxy/cache) |
| Filtered "Play Now/Next/Queue" action item | single `playlistcontrol cmd:load\|insert\|add album_id/track_id` | `playAlbum`, `playTracks` |
| `subscribe_zones` (zones + now-playing) | `players` + per-player `status` (polled) | `players`, `playerStatus` |
| `subscribe_queue` | `status 0 N` (playlist_loop) | `queue` |
| `transport.control` | `play` / `pause` / `stop` / `playlist index ±1` | `transport` |
| `transport.seek` | `time <s>` | `seek` |
| `transport.change_volume` / `mute` | `mixer volume` / `mixer muting` | `setVolume`, `setMute` |
| `transport.play_from_here(queue_item_id)` | `playlist index <n>` (queue_item_id **is** the index) | `playIndex` |
| `transport.transfer_zone` | `sync` target then `unsync` source | `syncPlayers` + `unsync` |
| Roon Settings service (radio, update) | LMS `pref` / `playerpref` + app-local settings | `getPref/setPref`, `getPlayerPref/setPlayerPref` |

### Offsets & image keys (contract shims)

- The frontend addresses albums by **`offset`** (position in the full library list)
  and images by **`image_key`**. LMS gives a stable album **`id`** and a **`coverid`**.
- The album index stores `{offset, id, image_key: coverid, …}`. `/api/album?offset=N`
  and `/api/play {offset}` look up the record by offset to get the LMS `id`;
  `/api/image/:image_key` treats `image_key` as the coverid. The frontend never sees
  the difference.

## Route status

### ✅ Ported & tested (phase 1)

Core library + playback. All verified end-to-end against a mock LMS
(`lib/*.test.js` + integration run: connect, index build, zones, random-albums,
search, album/tracks/actions, artwork, play, control, zone-state).

`/api/status` · `/api/zones` · `/api/random-albums` · `/api/search` ·
`/api/search-status` · `/api/artist-albums` · `/api/library-stats` ·
`/api/music-mount` · `/api/album` · `/api/image/:image_key` · `/api/play` ·
`/api/play-multi` · `/api/play-track` · `/api/play-from-here` · `/api/control` ·
`/api/seek` · `/api/volume` · `/api/transfer-zone` · `/api/zone-state` ·
`/api/album/now-playing` · `/api/queue` · `/api/reindex` · `/api/shortcut/zones` ·
`/api/play-unheard`

New LMS-specific routes (back the settings UI):
`/api/lms/connection` (GET/POST) · `/api/lms/discover` · `/api/lms/pref/:name`
(GET/POST) · `/api/lms/player/:id/pref/:name` (GET/POST) · `/api/lms/rescan`

### ✅ Ported (phase 2 — discovery rows + labels)

Home discovery rows and the whole record-label subsystem:

`/api/home/unplayed` · `/api/home/album-of-the-day` · `/api/home/label-of-the-week` ·
`/api/filters/labels` · `/api/label-albums` · `/api/labels-scan-status` ·
`/api/labels/rescan` · `/api/labels/rescan-force` · `/api/labels/logo-image/:filename` ·
`/api/labels/logo-candidates` · `/api/labels/logo` · `/api/labels/merge` (POST/DELETE) ·
`/api/labels-scan-log`. `/api/search` now also returns matching `labels`.

The label pipeline lives in `lib/labels.js` (`makeLabels(deps)`). It is a faithful
port of the Roon build's multi-pass scanner — file tags (needs `-v /music:ro`) then
the free metadata APIs (iTunes → Qobuz → TheAudioDB → MusicBrainz → Discogs), with
Fan Art TV + Discogs logo fetches, label grouping/merging, and the same rate limits
and 429/403 circuit breakers. The one deliberate deviation: caches persist as **JSON
files** under `data/cache/` (`labels-cache.json`, `labels-mbid.json`, `labels-logo.json`,
`labels-merges.json`) rather than the sibling's better-sqlite3 DB, keeping this repo
free of native dependencies (see the Dockerfile). `music-metadata` is an
**optional** dependency loaded via dynamic import; without it the file-tag pass is
skipped and labels fall back to the API cascade. album-of-the-day / label-of-the-week
reuse the in-memory album index and the JSON plays log for their deterministic
daily/weekly picks.

### ✅ Ported (phase 2 — Qobuz browse, Pitchfork, wall display)

- **Qobuz browse** (the Qobuz page/tab): `/api/qobuz/new-releases` · `/api/qobuz/featured` ·
  `/api/qobuz/search` · `/api/qobuz/artist-albums` · `/api/qobuz/favorite` ·
  `/api/qobuz/unfavorite`, backed by the already-ported `lib/qobuz.js` with the
  favourite-ids + featured caches and silent-relogin plumbing.
- **Pitchfork**: `/api/pitchfork/reviews` (Latest / Best New Music listings) and
  `/api/pitchfork/review` (per-card library match; the written review is never
  served — UK-law compliance). The listing scraper (preloaded-state walk + RSS
  fallback) now lives in `lib/pitchfork.js` alongside the single-album lookup.
- **Global external search**: `/api/search/external` returns Qobuz + Pitchfork
  sections (Tidal stays `null` — not ported).
- **Wall display**: `/display`, `/api/settings/display` (GET/POST),
  `/api/settings/youtube-key` (GET/POST), and `/api/display/content`. The content
  endpoint drives the rotation from the zone's now-playing track: library
  recommendations (more by the artist + label-mates, from the in-memory album +
  label indexes — no API keys) plus a best-effort YouTube video clip when a key
  is set. Artist photos / album reviews / artist bios depend on the larger
  FanArt/Wikipedia scraping subsystems that are **not yet ported**, so those
  fields degrade to empty and the page rotates art + recommendations + video.

### 🟡 Stubbed — safe empty response (phase 2)

Return neutral shapes so the UI degrades gracefully instead of erroring. Each is
marked `// PHASE 2` in `index.js`.

| Route(s) | LMS plan |
|---|---|
| `/api/home/genre-groups`, `/api/filters/genres` | LMS `genres` query. |
| `/api/filters/decades` | LMS `years` query (or per-album year already in the index). |
| `/api/filters/tags` | Map to LMS moods/genres, or drop. |
| Wall-display artist photos / album review / artist bios | FanArt.tv artist images + Qobuz/Wikipedia bio scraping — larger subsystems; `/api/display/content` returns them empty for now. |
| `/api/update/*` | LMS-repo self-updater (adapt `lib/updater.js` to this repo). |
| `/api/settings/tidal`, `/api/tidal/*` | Needs `lib/tidal.js` + OAuth device-flow port. |
| `/api/settings/discogs-token`, `/api/settings/fanart-key`, `/api/settings/label-folder-depth` | Settings persistence — done. |

### ⛔ Not applicable (Roon-only)

- Roon **Settings service** layout (`makeSettingsLayout`), **Roon Radio** zone
  behaviour, and **scrobble-to-Roon** state have no LMS analogue. LMS repeat/shuffle
  and alarms replace the "radio" idea; see `SETTINGS.md`.
- Roon pairing persistence (`roonstate.json`) → replaced by `data/lms-settings.json`
  (host/port/credentials).

## Phase-2 order (suggested)

1. Plays table + `/api/home/unplayed` + `/api/home/album-of-the-day` (highest-value
   discovery rows; index already in memory).
2. `/api/filters/genres` + `/api/filters/decades` (direct LMS queries).
3. Lift the backend-agnostic modules verbatim: Qobuz, Tidal, Pitchfork, labels
   logo/merge pipeline, wall display.
4. Material-skin settings UI on top of `/api/lms/pref/*` (see `SETTINGS.md`).
5. Self-updater retargeted at `MusicD-LMS-Remote`.
