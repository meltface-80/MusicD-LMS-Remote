# Changelog

What each version of MusicD LMS Remote (the server, its web app and the wall display) and Mallard (the Android remote)
changed. Work merged into `dev` goes under `## Unreleased` until the owner says to cut a version; then that heading is
renamed to the version and the Release workflow's notes are taken from it. Lines start Added / Changed / Fixed /
Removed. Versions before v1.0.86 are described in their GitHub releases.

## Unreleased

- Changed - Mallard has its own mark, on the Android app, the web app (installed or not), the share card and the Pages site: the MusicD duck beside Lyrion's waveform, as Rouen's pairs it with Roon's
- Added - Mallard has the Share card: Share on the album screen and on Now playing, with Copy image and Download (to Pictures/Mallard)
- Changed - The web app's Share card is redrawn to match Mallard's, the two now being one design (Mandarin's): the cover softened as the ground under a glass pane, a larger cover, the release, title and artist, the album's review across the card with whose words they are, Pitchfork's score and Best New Music, and the MusicD duck in the corner. The card appears at once and fills in the year and review as they arrive
- Added - Test builds from `dev`: the server image `ghcr.io/meltface-80/musicd-lms-remote:dev` (named, e.g., 1.0.87-t1) and Mallard's `mallard-test.apk` (0.2.0-tN), each published once CI passes. A test build says so in Settings → System and is updated by pulling `:dev` again, never by the in-app updater
- Changed - Releases are cut only when asked for (a `v*.*.*` tag on main, or Run workflow), never by a merge
- Changed - Quality gates from Mandarin: a file-length ratchet, `@ts-check` + `tsc`, and ESLint against a shrink-only baseline, run by a pre-commit hook and by CI on every push and pull request
- Changed - The online-library explanation (`/api/library/online`) and the build stamp moved into modules of their own (`lib/onlinelib.js`, `lib/build.js`)

## v1.0.86

- Added - Settings → LMS server says how many Qobuz / TIDAL albums are in the library and, when there are none, why (LMS's Online Music Library off, or its per-service import switched off), with Import now
- Fixed - Albums LMS imports from a streaming service in the background now appear without a rescan (the album count is watched)
- Added - Mallard, the native Android remote (`android/`), with Apps and Radio browsing every LMS plugin
