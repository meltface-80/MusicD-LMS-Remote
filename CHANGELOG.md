# Changelog

What each version of MusicD LMS Remote (the server, its web app and the wall display) and Mallard (the Android remote)
changed. Work merged into `dev` goes under `## Unreleased` until the owner says to cut a version; then that heading is

- A phone away from home gets an LMS player of its own (server half; Mallard's half follows). On a phone's hello the
  server runs squeezelite for it — a real LMS player named after the phone, its MAC fixed per phone so LMS keeps its
  settings — so LMS plays to it losslessly and every LMS player setting applies: volume, ReplayGain levelling,
  crossfade, Don't Stop The Music. Its sound (to stdout, resampled to 48 kHz) is read at real time and made into one
  continuous Ogg stream for the phone: Opus 256 kbps, or lossless FLAC 24-bit/48 kHz. Over the tailnet, from the phone
  that said hello, the zone list is that player alone, as "This phone". The Docker image now installs squeezelite and
  ffmpeg. Routes: `POST /api/phone/hello`, `GET /api/phone/stream`, `POST /api/phone/bye`.

- Mallard: Qobuz, as the web app's Browse Qobuz — a side-menu row under the plugin's own name while the server's Qobuz
  plugin is signed in (`/api/services`), walking its menus a level at a time (categories as rows, albums as tiles with
  the account favourite's ＋ / ✓), and an album's page: Play Now, Add to queue, ＋ Add to Qobuz / ✓ In Qobuz, its
  tracks (a tap plays that one). Search's Qobuz albums now read this server's answer (they came back with no artist
  or cover) and open there; their ＋ adds them to the account, where both used to say "open it from the page".

- Mallard: Labels, as the web app has it — a side-menu row (while Record labels is on) to every label as a tile, its
  logo or its name in words, with its album count and "N merged". A tap opens its albums with Set logo (Discogs'
  logos, or a pasted address); a long press picks labels to merge ("Merge N into …"), and "N merged" undoes one.
  While the scan runs it says how far it has got; with none found, Rescan now. Settings → Labels gains the web's
  Label album order, Minimum albums per label and Force rescan.

- Mallard: save the Library's view as a Dynamic Playlist, and edit one's rules, as the web app does. The Focus sheet's
  foot is Clear all · Save as Dynamic Playlist · Show albums; Save asks for a name (suggested from the rules on, as the
  page's) and says what it plays. A Dynamic Playlist's page gains Edit rules: its rules open in the Library's Focus
  with its Order and Playlist size first, and Save changes writes them back; dismissing the sheet puts your own view
  back. Unlike the web's Edit, it keeps the playlist's size and order rather than resetting them to 100 / Album order.

- Mallard: Edit album, as the web app has it — the pencil left of Share at an album page's top right opens the same
  editor: Title, Artist, Year; Artwork (Find artwork lists the covers found, a tap picks one, or paste an address);
  Remove edits (when it carries some), Cancel, Save. Kept in the app's database; files and LMS never touched. What
  is saved shows at once on the page and on the walls. `/api/album` now says `edited`, so both apps know when to
  offer Remove edits.

- Mallard: Add to playlist, as the web app has it — from the albums' multi-select (every track of the picked albums),
  the tracks' multi-select on an album's page, and (new in both senses) an album's ⋯. The same sheet: what is being
  added, "+ New playlist…", then Existing; the same toasts ("Added 12 tracks to new playlist “Road”", "existing …"
  when the name was taken, "(2 skipped)"), and the selection let go whatever came of it.

- The web page has Mallard's bottom bar (owner: the two apps are exact copies): Menu, Home, Library, Shelf while it is
  on, and Search, evenly across the foot of the screen on a phone or a tablet (never with a mouse), with the mini
  player lifted above it and the top bar's menu button moved into it. It steps aside for an album's page and Now
  playing, and while the side menu is open, as the mini player does. Geometry: the mini player's lift is a variable
  inside its one `bottom`, so the home-indicator inset is still applied once (`lib/safearea.test.js`); nothing here
  touches a top inset.

- Mallard has its own Tailscale (owner request: "No Mallard android app tailscale login yet"), as Mandarin's phone
  does: Settings → Away from home → This phone signs the phone in to Tailscale (the same engine as the server's,
  `musicdnet/`, built into the APK for 64-bit ARM by `mallard.yml`). Away from home — the saved address not answering
  over the Wi-Fi, or no Wi-Fi — Mallard then reaches the server through it by itself, at the server's tailnet address,
  which the server says in `/api/status` and Mallard remembers; at home the engine is stopped. It uses its own local
  port (34390), not Mandarin's, so both apps can be away on one phone. The server answers `/api/ping`, the engine's
  probe.

- The Shelf's switch lives in Settings → Wall display in both apps (owner: "Shelf isn't in wall display"); its own
  Settings row is gone, which saves a row. In Mallard, switching it on also puts Shelf in the bottom bar between
  Library and Search, as Mandarin's row does (`NavShelf`), as well as in the menu. The web app has no bottom bar, so
  there it is in the menu.
- Away from home is no longer in the web app's Settings (owner decision): the server's built-in Tailscale is managed
  from Mallard. The server side is unchanged.

- Plugin repositories (owner request: a developer's own plugins to try). Settings → LMS server → Plugins has a third
  tab, Repositories, in both apps: LMS's extra repository addresses, each with Remove, and a box to paste a new one's
  .xml address. The server saves LMS's own plugin page with the list changed (LMS keeps only `http(s)://….xml`, the
  same rule both apps check before sending), LMS reads the repository, and its plugins appear under Available under
  the repository's name, ready to install.

- Gentler on LMS (owner report: Lyrion stuttering and restarting). While LMS scans — an online-library import is the
  heaviest thing it does — this server no longer rebuilds its index on every album-count change (each rebuild re-read
  the whole track table, every 5 minutes for the length of the import); it rebuilds once when the scan ends. The Qobuz
  label lookup through LMS's album info now asks one album at a time, 1.5 s apart, never while LMS scans, stops after
  five failures in a row, and remembers the albums LMS had no label for (asked again after 30 days) instead of
  re-asking them all on every rebuild.
renamed to the version and the Release workflow's notes are taken from it. Lines start Added / Changed / Fixed /
Removed. Versions before v1.0.86 are described in their GitHub releases.

## Unreleased

- Changed - LMS's own settings pages — a plugin's Settings, and Lyrion's classic settings — open in Mallard's look in both apps: the dark ground, Manrope, each setting's name and note as Mallard's rows, Mallard's input boxes, brass radio buttons and a brass Save Settings. Material's pages keep their own look
- Changed - Mallard's Settings has one row fewer: the server's address (and Change) is now at the top of Settings → System, and the separate Mallard row is gone (its version line was already in System)
- Fixed - Genres were missing everywhere (the Shelf's Genres tab, the Library's genre filter): LMS's album list carries no genre, so each album's genre is now its first track's, read in the background sweep that already reads dates and formats
- Added - Record labels for Qobuz albums come straight from LMS's own album info (the Qobuz plugin's "Music Label", as Material shows it), so Labels and the Shelf's Labels tab fill in minutes rather than after hours of web lookups
- Changed - The web app's Home starts as Mallard's: the date and "Good evening", then Random Album beside the Album of the Day, then Not played in 6 months with its albums only. In the Mallard theme every Home row is a single row on tablets too
- Fixed - Artist biographies (and reviews) from LMS's Music & Artist Information plugin broke mid-sentence at every line of the source text, in both apps; their lines are joined back into paragraphs
- Added - Away from home, built in: Tailscale is part of the server's Docker image. Settings → Away from home (web app and Mallard) signs it in once (or set `TS_AUTHKEY`), and the server is on your tailnet by itself — nothing to install on the machine, no ports opened, ordinary bridge networking. Only your own devices on the tailnet are let in. `TS_HOSTNAME` names it, `TAILSCALE=off` leaves it out
- Changed - The Shelf's switch has its own row, Settings → Shelf, in both the web app and Mallard (as Mandarin's), instead of sitting inside Wall display, and Mallard can now switch it on
- Fixed - Mallard's Server settings showed Material's "Basic Settings" with nothing under it: Material's pages only lay out inside Material's own app, so Mallard now opens Material's settings the way Material does (its app with the server-settings dialog open)
- Fixed - The LMS proxy dropped the body of JSON requests (Material's live updates and JSON-RPC calls arrived empty), because the server had already read them
- Changed - Mallard opens LMS's settings pages inside the app (Settings → LMS server → Server settings, and a plugin's Settings), with Back stepping back through the pages, instead of in the phone's browser. The web app is unchanged
- Added - Mallard's Shelf has the web Shelf's Labels: a Labels tab (each label with its covers and count) and the "By label and catalogue №" order, with the label on the case's line
- Changed - The Shelf's Types tab shows only when something says what kind of release an album is, which LMS never does, in both apps
- Added - Mallard's side menu has the web app's collections: Merged albums (with Unmerge), Smart Picks, Dynamic Playlists (each playlist's albums, Play now, Add to queue, Delete), Playlists (tracks with covers, Play now, Add to queue, play from a track, Delete for your own playlist files) and Import a playlist (paste a share, see what matched and what's missing, save it). The rows and their icons are the web menu's, and Shelf and Smart Picks follow the server's switches as the web menu does
- Changed - The web app's Settings is drawn as Mallard's: full screen, the brass close or back disc before the title, a flat list with Mallard's own icons and words, compact buttons in the panes, and "MusicD LMS Remote v…" with View on GitHub at the foot
- Fixed - The web app's side menu head is solid, as Mallard's is, so the rows no longer show through it when the menu scrolls
- Added - Mallard installs LMS plugins too: Settings → LMS server → Plugins, the same list, buttons, warning and Restart LMS as the web app
- Added - Install LMS plugins from the web app: Settings → LMS server → Plugins lists what is installed and what LMS's plugin repositories offer, with Install, Update, Remove, Enable / Disable, each plugin's own settings, and Restart LMS to apply. It works through LMS's own plugin page, so LMS downloads and checks every plugin itself, and asks once (as LMS does) that you accept plugins are third-party software
- Changed - The LMS settings-page lookup (`/api/lms/settings-info`) moved into `lib/lms-settings-routes.js`, and now sends LMS's login when it checks for Material
- Added - The web app browses every LMS plugin, as Mallard does: Apps & radio in the side menu — each plugin's menus, search in its own words, play now, play next or add to the queue on the chosen player, settings choices, links. A plugin that doesn't answer says so
- Added - Away from home, first step: the README and the Pages site say how to use Mallard and the web app anywhere with Tailscale, no ports opened
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
