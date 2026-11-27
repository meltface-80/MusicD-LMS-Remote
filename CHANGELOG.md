# Changelog

What each version of MusicD LMS Remote (the server, its web app and the wall display) and Mallard (the Android remote)
changed. Work merged into `dev` goes under `## Unreleased` until the owner says to cut a version; then that heading is
renamed to the version and the Release workflow's notes are taken from it. Lines start Added / Changed / Fixed /
Removed. Versions before v1.0.86 are described in their GitHub releases.

## Unreleased

- Changed - The web app looks as the Mallard app does: a new default theme, Mallard — graphite and brass, Manrope with titles in Young Serif, brass buttons and icons, Home's rows titled in small brass capitals, the mini player's progress as a level meter, and Mallard's mark and name at the head of the menu. The other four themes stay in Settings → Appearance
- Added - Mallard has the Shelf too: Shelf in its side menu while the Shelf is switched on in Settings, the same racks, choices and orders as the web page, turning a case over into Mallard's own album screen
- Fixed - Mallard's Settings → LMS server → Server settings opens Lyrion's own settings pages (Material's when installed), not the web app's Home
- Fixed - Google Play Protect still blocked Mallard as harmful once signed with the private key. Mallard no longer installs its updates itself: Update opens the new APK in your browser, which downloads and installs it, so Mallard needs no permission to install apps
- Added - Shelf: the whole library as a record shop's racks, full screen at /shelf — flick through the spines, narrow by genre, artist, decade, label, format or favourites, file it by artist, label, year or at random, turn a case over to see its tracks and play it on any player. Off by default: Settings → Wall display → Shelf, then it is in the side menu (Mallard's Shelf follows)
- Changed - Mallard's update offer lists only what changed in Mallard, whole lines, never cut off mid-sentence
- Fixed - Google Play Protect blocked Mallard 0.2.0-t32 as harmful: it was signed with the public test key while asking to install its own updates. Every published Mallard is now signed with the owner's private key, as Mandarin is (one-time: uninstall the old Mallard, then install the new one)
- Added - Mallard updates itself, as Mandarin's app does: Home offers a newer build (Update / Later) and Settings → System has a Mallard row beside the server's. Test builds are offered each new test build; the download is checked against its SHA-256 before Android installs it over the top
- Changed - Mallard has its own mark, on the Android app, the web app (installed or not), the share card and the Pages site: half the MusicD duck in Mandarin's brass beside half of Lyrion's waveform in its green
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
