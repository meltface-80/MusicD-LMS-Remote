<div align="center">

<img width="800" alt="MusicD LMS Remote" src="docs/IMG_8974.jpeg" />

# MusicD LMS Remote

**Your Lyrion library, rediscovered — on your phone, your browser and your wall.**

[Website & docs](https://meltface-80.github.io/MusicD-LMS-Remote/) ·
[Install](https://meltface-80.github.io/MusicD-LMS-Remote/install.html) ·
[Downloads](https://meltface-80.github.io/MusicD-LMS-Remote/downloads.html) ·
[What's in v1.0.87](https://meltface-80.github.io/MusicD-LMS-Remote/whats-new.html)

</div>

MusicD LMS Remote is a companion for [Lyrion Music Server](https://lyrion.org). It runs beside Lyrion in Docker and
turns your library into something to browse and rediscover — album of the day, label of the week, Smart Picks, a
record-shop Shelf — plays it to any of your Lyrion players, and puts it everywhere:

- **Mallard**, the Android app — every screen, lock-screen and notification controls, a widget, and your phone as a
  Lyrion player of its own.
- **The web app**, in any browser; added to an iPhone's home screen it looks and works as Mallard does.
- **The wall display**, a TV or tablet as a now-playing screen.

Away from home, Mallard plays on the phone itself — Opus 256 kbps at 24-bit/48 kHz or bit-perfect, with a USB DAC if
you like — over Tailscale, which is built into both the server and the app. Nothing is installed in Lyrion, and your
music files are never written to.

> **This release is meant to be tried.** The newest parts — This phone, going away and coming home, tracks kept ahead,
> a USB DAC, and Tailscale built in — are the least tested. If something doesn't work, please
> [open an issue](https://github.com/meltface-80/MusicD-LMS-Remote/issues) with the log
> ([how](https://meltface-80.github.io/MusicD-LMS-Remote/logs.html)).

## Install

The [configurator](https://meltface-80.github.io/MusicD-LMS-Remote/install.html) writes the command for your setup. On a
machine other than Lyrion's, it comes to this:

```bash
docker run -d --name musicd-lms-remote --restart unless-stopped --pull always \
  -p 3390:3390 \
  -e LMS_HOST=192.168.1.50 \
  -v musicd-lms-remote-data:/app/data \
  -v /path/to/your/music:/music:ro \
  ghcr.io/meltface-80/musicd-lms-remote:latest
```

Then open `http://<this machine>:3390`, or install [Mallard](https://github.com/meltface-80/MusicD-LMS-Remote/releases/download/mallard-latest/mallard.apk)
(a 64-bit phone, Android 8.0 or later), which finds the server by itself. The image is built for x86-64, 64-bit ARM and
32-bit ARM, and needs Docker 20.10 or later. A [`docker-compose.yml`](docker-compose.yml) is included.

- **The music folder** (`-v …:/music:ro`) is optional: with it, Mallard keeps the next tracks of your own music on the
  phone, and the record-label scan reads each file's tags. It is mounted read-only.
- **Everything the app keeps** — settings, favourites, play history, caches, its Tailscale identity — is in the
  `musicd-lms-remote-data` volume.
- **Updates** are offered in Settings → System, one tap; Mallard offers its own.

## What it does

- **Discovery** — a Home that changes every day: Random Album, Album of the Day, Label of the Week, albums not played in
  six months, recently played; Smart Picks (six albums a day by artists you don't own, each with its reason); random
  album radio, whole albums when a queue runs out.
- **The Library** — the whole collection, sorted every way and narrowed by genre, decade, label, format, sample rate,
  bit depth or listening history; saved as Dynamic Playlists that keep themselves up to date.
- **The Shelf** — the library as a record shop's racks, in both apps and on the wall.
- **Albums and artists** — large artwork, full credits, reviews and biographies from Lyrion's Music & Artist Information
  plugin, Pitchfork scores; merge a box set into one album; edit an album or rescue its artwork in the app's own
  database.
- **Audio Devices** — every Lyrion player, its power, random album radio, name and Lyrion's settings for it.
- **Plugins** — every Lyrion plugin's menus in the app (Apps & radio), and installing, updating and removing plugins.
- **Your Qobuz and TIDAL albums** beside your own files, through Lyrion's Online Music Library.
- **Playlists, favourites, share cards**, and four more themes.

The [features page](https://meltface-80.github.io/MusicD-LMS-Remote/features.html) has the detail, and
[This phone and away from home](https://meltface-80.github.io/MusicD-LMS-Remote/this-phone.html) how the phone plays.

## Configuration

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `3390` | The port the web app, the wall display and Mallard use |
| `LMS_HOST` | *(found on the network)* | Lyrion's address — needed when the server runs on another machine or network than Lyrion |
| `LMS_PORT` | `9000` | Lyrion's web port |
| `LMS_USER` / `LMS_PASS` | – | Lyrion's username and password, if set |
| `MUSIC_DIR` | `/music` | Where the optional read-only music folder is mounted: tracks kept ahead on the phone, record-label tags |
| `TS_AUTHKEY` | – | Joins the built-in Tailscale without signing in in a browser |
| `TS_HOSTNAME` | `musicd-lms` | The server's name on your tailnet |
| `TAILSCALE` | *(on)* | `off` leaves the built-in Tailscale out |
| `SLIMPROTO_PORT` | `3483` | Lyrion's player port, for the phones' own players, if you moved it |
| `LOG_LEVEL` | `info` | `error`, `warn`, `info`, `debug` or `trace` (`DEBUG=1` is `debug`) |
| `LOG_FILE` | `data/logs/musicd.log` | The rotating log file kept beside `docker logs`; `off` to switch it off |

Free API keys for label logos (FanArt.tv, Discogs) and music videos on the wall display (YouTube) go in Settings:
[how to get them](https://meltface-80.github.io/MusicD-LMS-Remote/configuration.html#keys).

## Test builds

Every change merged into `dev` is published once its tests pass: the server as
`ghcr.io/meltface-80/musicd-lms-remote:dev` (use `:dev` in place of `:latest`), and Mallard as
[mallard-test.apk](https://github.com/meltface-80/MusicD-LMS-Remote/releases/download/mallard-test/mallard-test.apk).
Test builds are never offered to anyone on a release.

## For developers

```
index.js            the server: Lyrion connection, the album index, /api routes
lib/                its modules — lms.js (Lyrion's JSON-RPC), search.js, the phones' players (relayplayer.js,
                    phoneplayers.js, phonefiles.js), Tailscale (tailscale.js), plugins (apps.js, lmsplugins.js) …
lib/*.test.js       the tests (npm test)
public/             the web app and the wall display — no build step
android/            Mallard: :core (pure Kotlin, tested on the JVM) and :app (Compose)
musicdnet/          the built-in Tailscale engine (Go, tsnet)
docs/               the website (GitHub Pages)
```

`npm install && npm run tools`, then `npm run check` runs the quality gates (file length, `@ts-check` + `tsc`, ESLint)
and every test. [CLAUDE.md](CLAUDE.md) has the working rules, and [CHANGELOG.md](CHANGELOG.md) what each version
changed.

## Licence

MIT. Lyrion Music Server is a separate project; Qobuz, TIDAL and Tailscale are trademarks of their owners. This is an
independent project, affiliated with none of them.
