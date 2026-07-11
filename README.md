# MusicD LMS Remote

A music-discovery PWA and wall display for **[Lyrion Music Server](https://lyrion.org)**
(LMS, formerly Logitech Media Server). It is the LMS port of
[MusicD Remote](https://github.com/meltface-80/MusicD-Remote) (the Roon build) —
same UI, same features, talking to LMS instead of Roon.

- Random-album browsing with cover art
- Whole-library instant search (albums + artists)
- Play now / Play next / Queue to any LMS player
- Live mini-transport (play/pause/seek/volume) + queue view
- Material-skin-level LMS settings surface (server + per-player prefs) — *in progress*

> **Status: phase-1 port.** The core library + playback experience is complete and
> tested end-to-end. Advanced surfaces (Home discovery rows, genre/decade filters,
> Qobuz/Tidal, Pitchfork, wall display, self-update) are stubbed and ported next.
> See [PORTING.md](PORTING.md) for the exact per-route status.

## How it works

The whole UI talks only to this server's `/api/*` routes; the server translates
them to the LMS JSON-RPC / CLI API (`POST /jsonrpc.js`). Because the `/api`
contract is identical to the Roon build, the entire PWA frontend is shared
verbatim.

- **Connection:** set `LMS_HOST` (and optionally `LMS_PORT`, default 9000), or let
  the server auto-discover an LMS on the LAN over UDP 3483, or enter the host in
  the in-app settings. Connection details persist on the data volume.
- **Artwork** is proxied and cached from LMS's `/music/<coverid>/cover.jpg`.
- **Playback** is a single `playlistcontrol` call — no Roon-style filtered
  browse-then-play navigation.

## Run with Docker

```bash
docker build -t musicd-lms-remote .
docker run -d \
  --name musicd-lms-remote \
  --restart unless-stopped \
  --network host \
  -e LMS_HOST=192.168.1.50 \
  -e LMS_PORT=9000 \
  -v musicd-lms-remote-data:/app/data \
  musicd-lms-remote
```

Then open `http://<this-host>:3399`. (Host networking is recommended so UDP
discovery works and LMS artwork URLs resolve; if you use bridge networking,
set `LMS_HOST` explicitly.)

If your LMS has a username/password, set `LMS_USER` / `LMS_PASS`.

## Run locally (Node)

```bash
npm install
LMS_HOST=192.168.1.50 npm start
# open http://localhost:3399
```

## Tests

```bash
npm test          # adapter (17) + search index (8) unit tests
```

## Environment variables

| Var | Default | Purpose |
|-----|---------|---------|
| `PORT` | `3399` | HTTP port for this app |
| `LMS_HOST` | *(discover)* | LMS server host/IP |
| `LMS_PORT` | `9000` | LMS JSON-RPC/web port |
| `LMS_USER` / `LMS_PASS` | – | LMS HTTP auth, if enabled |
| `DEBUG` | – | `1` for verbose logging |

## Layout

```
index.js          server: connection, album index, /api routes
lib/lms.js        LMS JSON-RPC adapter (the Roon replacement)
lib/search.js     whole-library album/artist search index
lib/*.test.js     unit tests
public/           the shared PWA frontend (byte-identical to the Roon build)
SETTINGS.md       Material-skin-level LMS settings catalog (settings-UI blueprint)
PORTING.md        per-route Roon→LMS port status
```

## Licence

MIT.
