# Bug and feature list

The open work, numbered. Each item is a branch and a pull request into `dev` of its own (CLAUDE.md §4). An item is
marked **Fixed** only when the owner says so; until then a merged item reads **On dev (testing)**.

The owner rule behind most of this list: **the web app and Mallard mirror each other** — every feature exists in both.

## Open

1. **Adopt Mandarin's development rules.** CLAUDE.md, quality gates, `dev` test builds (server `:dev` image, Mallard
   `mallard-test` APK), manual releases, CHANGELOG. — *In progress.*
2. **Mallard: share card.** The web app makes a share card for an album / now playing; Mallard has none. (Owner report,
   Mallard 0.2.0.)
3. **The web app (PWA) becomes Mallard.** Same name, look and features as the Android app (owner decision): Mandarin's
   hifi palette, Manrope + Young Serif, then shell, Home/Library, Album/Now playing, Artist/Apps, in phases. The web app
   still has the old UI (owner report). The iOS safe-area rules in CLAUDE.md hold throughout.
4. **Rename everything to Mallard** (owner decision): server, docs, Pages, Docker image. Needs a careful transition —
   publish both image names for a while, keep the data volume, keep the MDRP1 share format, the repo rename is the
   owner's action and the Pages URL does not redirect.
5. **Mallard parity — playlists.** Stored playlists, Add to playlist, Dynamic (Live) Playlists.
6. **Mallard parity — Qobuz.** Qobuz in search, the Qobuz album page, tile marks.
7. **Mallard parity — library management.** Merged albums (list, unmerge, rename), album edit and artwork rescue,
   Remove from Favourites in multi-select.
8. **Mallard parity — Labels.** The Labels browser and its settings; labels in search.
9. **Mallard parity — small items.** Smart Picks queue, single-zone mute, radio icon, player rename, sample-rate badge,
   the LMS settings link, share import/export, the random filter sheet, themes.
10. **Web app parity.** Apps/Radio browser, Play next, power and grouping from Now playing, artist Play all,
    More by / Appears on, Random Album dialog, previous/next album, size settings.
11. **Plugins phases 3–5.** Context menus, plugin settings, Material-only extras gated on detecting Material.
12. **Mallard: remove the Mandarin code that is no longer reached.**
