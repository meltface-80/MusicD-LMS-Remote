# LMS settings catalog (Material-skin level)

A settings map for the PWA that manages a **Lyrion Music Server (LMS)** — formerly
Logitech Media Server — at the level of coverage that the community **Material Skin**
(CDrummond/lms-material) exposes, re-implemented on our own adapter (`lib/lms.js`).

This is the design blueprint for the LMS settings UI. Phase-1 ships the adapter
primitives and the pref get/set routes (`/api/lms/pref/*`, `/api/lms/player/:id/pref/*`);
the settings screens themselves are built on top of these in a later phase.

## How to read this document

- **Backing pref/command** names in `monospace` are the LMS preference keys read/written
  through the adapter. Server-wide keys use `getPref` / `setPref` (CLI `pref`); per-player
  keys use `getPlayerPref(playerId, …)` / `setPlayerPref(…)` (CLI `playerpref`).
- Where a value is **not** a stored pref but a live command (repeat, shuffle, sync,
  rescan), that is called out — those go through the adapter's transport / `rescan` / `sync`
  primitives, not `getPref`/`setPref`.
- Confidence: names verified against the LMS 9.0 source are stated plainly. Anything
  unverified is tagged **(pref name to verify)** rather than guessed with false confidence.

Adapter primitives assumed: `getPref/setPref`, `getPlayerPref/setPlayerPref`, `players()`,
`serverStatus()`, `rescan(mode)`, `transport/volume/mute/seek/sync/unsync`.

---

## 1. Player settings (per-player)

Scope for this entire group is **per-player** (CLI `playerpref`), unless noted. `playerId`
is the player's MAC/id from `players()`.

### 1.1 Identity

| Label | Backing pref/command | Type | Scope | Description |
|---|---|---|---|---|
| Player name | CLI `name` command (`<playerid> name <value>`) — set via the `name` command, not `playerpref` **(verify)** | text | per-player | Human-readable name shown in the UI and to other controllers. |
| Power | `power` | toggle | per-player | Current on/off (soft) power state; also drivable live via transport. Default `1`. |

### 1.2 Audio

| Label | Backing playerpref | Type | Scope | Description |
|---|---|---|---|---|
| Transition type | `transitionType` | dropdown: `0` None, `1` Crossfade, `2` Fade in, `3` Fade out, `4` Fade in & out | per-player | How one track blends into the next. Default `0`. |
| Transition duration | `transitionDuration` | number 0–10 s | per-player | Length of the crossfade/fade. Default `10`. |
| Smart transitions | `transitionSmart` | toggle | per-player | Suppress crossfade between contiguous gapless tracks. Default `1`. |
| Transition sample-rate restriction | `transitionSampleRestriction` **(values to verify)** | dropdown/number | per-player | Only crossfade when sample rates match. |
| Volume levelling (Replay Gain) | `replayGainMode` | dropdown: `0` Off, `1` Track, `2` Album (Smart), `3` Album | per-player | Apply ReplayGain tags to normalise loudness. Default `0`. |
| Remote stream gain | `remoteReplayGain` | number −20…20 dB | per-player | Fixed gain for remote/streamed tracks lacking RG tags. Default `-5`. |
| Digital volume control | `digitalVolumeControl` | toggle | per-player | Software volume vs fixed/pass-through output. Default `1`. |
| Fixed volume (100%) | derived from `digitalVolumeControl = 0` | toggle | per-player | "Output fixed volume" = disabling digital volume control. |
| Preamp / output level | `preampVolumeControl` **(range to verify)** | number | per-player | Analog/preamp attenuation for supported hardware. |
| Bass | `bass` | number 0–100 (50 flat) | per-player | Tone control (hardware-dependent). Default `50`. |
| Treble | `treble` | number 0–100 (50 flat) | per-player | Tone control (hardware-dependent). Default `50`. |
| Balance | `balance` **(range to verify, −100…100)** | number | per-player | Left/right balance. |
| Stereo width (StereoXL) | `stereoxl` **(values to verify)** | dropdown/number | per-player | Stereo-widening effect on supported players. |
| Output channels | `outputChannels` **(codes to verify)** | dropdown | per-player | Channel routing (stereo/left/right/mono). |
| Polarity inversion | `polarityInversion` | toggle | per-player | Invert absolute phase of the output. |
| Analog output mode | `analogOutMode` **(codes to verify)** | dropdown | per-player | Analog output routing/mode (model-specific). |
| Silence prelude (MP3) | `mp3SilencePrelude` **(range to verify)** | number | per-player | Brief silence before MP3 playback to prime some DACs. Default `0`. |

### 1.3 Playback

| Label | Backing command/pref | Type | Scope | Description |
|---|---|---|---|---|
| Repeat | CLI `playlist repeat <0\|1\|2>` (live command, not a stored pref) | dropdown: `0` Off, `1` One, `2` All | per-player | Repeat mode for the current queue. Read via `playlist repeat ?`. |
| Shuffle | CLI `playlist shuffle <0\|1\|2>` (live command, not a stored pref) | dropdown: `0` Off, `1` Songs, `2` Albums | per-player | Shuffle mode. Read via `playlist shuffle ?`. |
| Fade in on play/resume | `fadeInDuration` | number seconds | per-player | Volume ramp when starting/resuming. Default `0`. |
| Buffer threshold | `bufferThreshold` | number (KB) | per-player | Amount buffered before playback starts. Default `255`. |

> Repeat/shuffle are queue **modes**, not preferences — drive them through the transport
> layer, mirroring what `serverStatus()`/`status` report.

### 1.4 Power behaviour

| Label | Backing playerpref | Type | Scope | Description |
|---|---|---|---|---|
| On resume (power-on action) | `powerOnResume` **(option list to verify)** | dropdown | per-player | What playback does when powered back on. Default `PauseOff-PlayOn`. |
| Screensaver (on/idle/off) | `screensaver`, `idlesaver`, `offsaver` | dropdown of savers | per-player | Screensaver per power state. |
| Screensaver timeout | `screensavertimeout` | number (s) | per-player | Idle delay before the screensaver engages. Default `30`. |

### 1.5 Sync groups

| Label | Backing command/pref | Type | Scope | Description |
|---|---|---|---|---|
| Sync this player with… | adapter `sync`/`unsync`; membership from `serverStatus()` | multi-select of players | per-player | Group players for synchronous playback. |
| Keep sync when powering members | `syncPower` | toggle | per-player | Power state follows the sync group. Default `0`. |
| Sync volume across group | `syncVolume` | toggle | per-player | Volume changes apply to the whole group. Default `0`. |
| Maintain sync (drift correction) | `maintainSync` | toggle | per-player | Continuously correct timing drift. Default `1`. |
| Min sync adjust | `minSyncAdjust` | number (ms) | per-player | Smallest correction the server applies. Default `30`. |

### 1.6 Alarm basics

Alarms are a first-class CLI family (`alarm`, `alarms`), not simple prefs — model them as
their own editor screen backed by those commands. Only the alarm **defaults** (fade,
default volume, snooze) are player prefs, and their exact names are **to verify**.

---

## 2. Server / Library (server-wide, CLI `pref`)

### 2.1 Media & playlist folders

| Label | Backing pref | Type | Description |
|---|---|---|---|
| Music/media folders | `mediadirs` | text (array of paths) | Roots the library is scanned from. Array-valued. |
| Playlist folder | `playlistdir` | text (path) | Where `.m3u`/`.pls` playlists are read/saved. |
| Ignore in audio scan | `ignoreInAudioScan` | text (path list) | Subfolders to skip during audio scanning. |

### 2.2 Rescan actions

| Label | Backing command | Type | Description |
|---|---|---|---|
| Look for new & changed | `rescan('new')` (CLI `rescan`) | action-button | Incremental scan. |
| Clear & rescan everything | `rescan('wipe')` (CLI `wipecache` + `rescan`) | action-button | Wipe DB and rebuild. |
| Rescan playlists only | `rescan('playlists')` | action-button | Re-read the playlist folder only. |
| Scan progress/status | `serverStatus()` (`rescan`, `progressname/done/total`) | read-only | Live scan phase + progress bar. |

### 2.3 Automatic rescan

| Label | Backing pref | Type | Description |
|---|---|---|---|
| Automatic library rescan | `autorescan` | toggle | Watch media folders and rescan on change. |
| Auto-rescan poll interval | `autorescan_stat_interval` | number (s) | Poll interval when FS change events aren't available. |

### 2.4 Artwork / cover preferences

| Label | Backing pref | Type | Description |
|---|---|---|---|
| Pre-cache artwork | `precacheArtwork` | toggle | Pre-generate resized covers during scan. |
| Use local image proxy | `useLocalImageproxy` | toggle | Resize/proxy remote artwork locally. |
| Custom artwork sizes | `customArtSpecs` | text (list) | Extra resize specs for skins/endpoints. |

### 2.5 Ignored articles & sorting

| Label | Backing pref | Type | Description |
|---|---|---|---|
| Ignored articles | `ignoredarticles` | text (space-separated) | Leading words ignored when sorting. Default `The El La Los Las Le Les`. |
| List separators to split | `splitList` | text | Characters that split multi-value tags. |
| Group multi-disc sets | `groupdiscs` | toggle | Present multi-disc releases as one album. |
| Various Artists auto-detect | `variousArtistAutoIdentification` | toggle | Auto-assign compilations to the VA bucket. |
| Various Artists label | `variousArtistsString` | text | Display name for the VA bucket. Default `Various Artists`. |
| Use TPE2 as album artist | `useTPE2AsAlbumArtist` | toggle | Treat ID3 TPE2 as album artist. |

### 2.6 Browse / menu items

| Label | Backing pref | Type | Scope | Description |
|---|---|---|---|---|
| Home / browse menu items | `menuItem` | reorder list | **per-player** | Which items appear on the player's Home menu. |
| Substring search | `searchSubString` | toggle | server-wide | Match anywhere, not just at word start. |
| Release-type grouping | `groupArtistAlbumsByReleaseType`, `ignoreReleaseTypes`, `cleanupReleaseTypes` | toggle/text | server-wide | How albums/EPs/singles are grouped/hidden. |

---

## 3. Network / streaming

Bitrate/transcoding hints are **per-player** (they parameterise that player's stream);
server-wide transcoding rules live in `convert.conf`, not a pref.

| Label | Backing pref | Type | Scope | Description |
|---|---|---|---|---|
| Bitrate limit (transcode target) | `maxBitrate` **(codes to verify)** | dropdown: No limit / 320 / 256 / 192 / 128 / 96 / 64 | per-player | Cap the stream bitrate; drives on-the-fly transcoding. |
| LAME quality | `lameQuality` | number 0–9 | per-player | MP3 encoder quality when transcoding. |
| MP3 streaming method | `mp3StreamingMethod` **(codes to verify)** | dropdown | per-player | How MP3 is delivered to this player. |
| Start delay / play delay | `startDelay`, `playDelay` | number (ms) | per-player | Timing offsets for sync/latency tuning. Default `0`. |
| Packet latency | `packetLatency` | number (ms) | per-player | Network latency compensation for sync. Default `2`. |
| Transcoding rules | `convert.conf` (file, not a pref) | read-only | server-wide | Codec conversion table; edited on the host, not via `setPref`. |

---

## 4. Interface / UX — app-local (NOT LMS prefs)

**Everything here is stored by our PWA (local/app settings). None are LMS `pref`/`playerpref`
keys — do not route them through `setPref`.** These mirror the existing MusicD Remote
settings surface (Playback / Appearance / Wall display / System panes).

| Label | Store | Type | Description |
|---|---|---|---|
| Theme (light/dark/auto) | app-local | dropdown | PWA colour theme. |
| Wall display enable | app-local | toggle | Ambient `/display` mode. |
| Wall rotation interval | app-local | number (s) | Cycle cadence for the wall display. |
| Wall display modes | app-local | multi-select | Which panes rotate. |
| Active player selection | app-local (seeded from `players()`) | dropdown | Which player the UI controls — app-local UI state targeting a real `playerId`. |

---

## 5. Advanced / maintenance

| Label | Backing command/pref | Type | Description |
|---|---|---|---|
| Full rescan (clear & rebuild) | `rescan('wipe')` (CLI `wipecache` then `rescan`) | action-button | Nuclear DB rebuild — confirm first, long-running. |
| Restart server | CLI `restartserver` **(availability to verify)** | action-button | Not always permitted (Docker/systemd restart the container/service) — gate on capabilities and warn. |
| Server priority / scanner priority | `serverPriority`, `scannerPriority` | number/dropdown | OS scheduling priority for server/scan processes. |
| DB high-memory mode | `dbhighmem` | toggle | Trade RAM for scan/browse speed. |
| Max playlist length | `maxPlaylistLength` | number | Cap on queue length. |
| Server / version info | `serverStatus()` (`version`, `uuid`, counts) | read-only | LMS version, track/album counts, last scan. |
| Plugins | plugin manager + namespaced prefs (`plugin.<name>:<key>`) | info + link-out | Plugin install/enable is managed by LMS; deep config out of core scope. |

---

## Confidence summary

**Verified against LMS 9.0 source** (`Slim/Player/*`, `Slim/Web/Settings/**`):
player — `transitionType/Duration/Smart`, `replayGainMode`, `remoteReplayGain`,
`digitalVolumeControl`, `preampVolumeControl`, `bass`, `treble`, `balance`, `stereoxl`,
`outputChannels`, `polarityInversion`, `analogOutMode`, `mp3SilencePrelude`, `maxBitrate`,
`lameQuality`, `mp3StreamingMethod`, `powerOnResume`, `fadeInDuration`, `bufferThreshold`,
`power`, `syncPower`, `syncVolume`, `maintainSync`, `minSyncAdjust`, `packetLatency`,
`startDelay`, `playDelay`, `screensaver`, `idlesaver`, `offsaver`, `screensavertimeout`,
`menuItem`; server — `mediadirs`, `playlistdir`, `ignoreInAudioScan`, `ignoredarticles`,
`splitList`, `groupdiscs`, `variousArtistAutoIdentification`, `variousArtistsString`,
`useTPE2AsAlbumArtist`, `searchSubString`, `ignoreReleaseTypes`, `cleanupReleaseTypes`,
`groupArtistAlbumsByReleaseType`, `precacheArtwork`, `useLocalImageproxy`, `customArtSpecs`,
`maxPlaylistLength`, `autorescan`, `autorescan_stat_interval`, `serverPriority`,
`scannerPriority`, `dbhighmem`.

**To verify before shipping as certain:** player name write path (`name` command vs
`playername` pref), alarm-default pref keys, option-code values for
`preampVolumeControl`/`analogOutMode`/`outputChannels`/`maxBitrate`/`mp3StreamingMethod`,
`powerOnResume` full list, and `restartserver` availability.

**Not preferences at all** (drive via commands/files, never `setPref`): repeat/shuffle
(`playlist repeat`/`playlist shuffle`), rescan (`rescan`/`wipecache`), sync (`sync`/`unsync`),
alarms (`alarm`), transcoding rules (`convert.conf`), plugin management.

Sources: Lyrion CLI reference (players, alarms), elParaguayo LMS-CLI-Documentation,
LMS-Community/slimserver 9.0 source, CDrummond/lms-material (Material Skin).
