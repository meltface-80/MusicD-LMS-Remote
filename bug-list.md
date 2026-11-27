# Bug and feature list

The open work, numbered. Each item is a branch and a pull request into `dev` of its own (CLAUDE.md §4). An item is
marked **Fixed** only when the owner says so; until then a merged item reads **On dev (testing)**.

The owner rule behind most of this list: **the web app and Mallard mirror each other** — every feature exists in both.

## Open

1. **Adopt Mandarin's development rules.** CLAUDE.md, quality gates, `dev` test builds (server `:dev` image, Mallard
   `mallard-test` APK), manual releases, CHANGELOG. — *In progress.*
2. **Mallard: share card.** The web app makes a share card for an album / now playing; Mallard has none. (Owner report,
   Mallard 0.2.0.) Both apps to be visually the same: both now draw Mandarin's card, from twin layouts
   (`public/sharecard-layout.js` / `ShareCard.kt`) held to one set of test cases. — *On dev (testing).*
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
13. **Shelf** (owner request): Mandarin's full-screen record-shop browser of the whole library (spines in 3D, choices
    by genre/artist/decade/format/favourites, its own player bar), in the web app and Mallard, behind a Settings switch
    that defaults off. Port from the Roon build's Node version (`lib/shelf.js`, `public/shelf*`) and Mandarin's Kotlin.
    Its fixed 4 s poll must become the adaptive 2 s/6 s one; its new page needs the iOS safe-area checks.
    Web app and server: *On dev (testing).* Mallard's native Shelf (Mandarin's, ported): *On dev (testing).*
14. **Away from home** (owner request). Mandarin's away mode is its built-in Tailscale (a userspace `tsnet` engine in
    the server and the Android app) plus its OWN phone player — not Slimproto. Phases: (0) document remote control over
    an ordinary Tailscale install; (1) Mallard as an LMS Slimproto player on the LAN (Mandarin Bridge's `Slim.kt`,
    adapted to real LMS — new ground, never run against LMS); (2) Tailscale built into the server image, relaying
    LMS's 3483/9000; (3) Tailscale built into Mallard.
15. **Mallard's own logo** (owner request): as Rouen's is the MusicD duck blended with Roon's mark, Mallard's is the duck
    blended with Lyrion's waveform. Launcher icon, the PWA's icons, the share card's corner tile, the Pages site.
    The owner chose the flipped brass duck with the green waveform. — *On dev (testing).*
16. **Mallard updates itself** (owner request, as Mandarin): `mallard.json` beside every APK, Home's banner with
    Mandarin's update symbol, a Mallard row in Settings → System, a by-hand Mallard release channel. — *On dev (testing).*
17. **Play Protect blocked Mallard as harmful** (owner report, 0.2.0-t32). Cause: published APKs were signed with the
    public debug key, and t32 added REQUEST_INSTALL_PACKAGES for the self-updater. Fix: Mandarin's rule — publish only
    with the owner's private key (secrets), fail otherwise. Still blocked (t41, signed): the in-app installer itself
    (REQUEST_INSTALL_PACKAGES + downloading and launching an APK) is the trigger for a new app with no install history.
    Fix 2: Update hands the APK to the browser; Mallard installs nothing itself. — *On dev (testing).*
18. **Mallard's "Server settings" opened the web app's Home** (owner report), not the LMS settings pages. Now opens
    the page /api/lms/settings-info names, through this server's LMS proxy. — *On dev (testing).*
19. **Plugins, more** (owner request): adding plugins and making sure they work — install/enable LMS plugins from the
    apps (LMS's own plugin manager), and every plugin's menus working in Mallard and the web app. Widens item 11.
