<div align="center"> 

<img width="1536" height="1024" alt="image" src="https://github.com/user-attachments/assets/fc1dd26e-db7f-4e27-8f66-b0ab74db89e3" />

## 🐳 [Install with the Docker configurator →](https://meltface-80.github.io/MusicD-LMS-Remote/#install)

**[meltface-80.github.io/MusicD-LMS-Remote](https://meltface-80.github.io/MusicD-LMS-Remote/)**

Pick same-machine / separate-machine / macOS, toggle the read-only music mount,
and copy a ready-made `docker run` or `docker compose` command — plus
step-by-step FanArt.tv, YouTube and Discogs API key setup.

</div>

# MusicD LMS Remote

## Features

🎵 Album Discovery

* Browse your music library in a fresh and engaging way
* Discover forgotten favourites and hidden gems
* Random album selection with configurable filtering
* Album of the day
* Label of the week
* Play Unheard albums
* Recently unplayed album recommendations
* Continue discovering music automatically with Random Album Radio

Over time with prolonged use the database learns when you last listened to an album and will offer up others instead so you rediscover forgotten albums.

⸻

📚 Rich Library Browsing

Browse your library in multiple ways:

* Albums
* Artists
* Genres
* Record Labels
* Decades
* Tags

Quickly jump between related artists, albums and labels from anywhere in the application.

⸻

🔍 Powerful Search

Search your music library instantly by:

* Album
* Artist
* Record Label

Optionally extend searches to supported streaming services including:

* Qobuz
* TIDAL

Also browse Qobuz and Tidal directly and add favourites to your Roon library. 

⸻

💿 Detailed Album Pages

Each album includes rich metadata including:

* High resolution artwork
* Track listing
* Release year
* Record label
* Album duration
* Multiple artist support
* Pitchfork review and rating (where available)

Play, queue or browse directly from the album page.

⸻

▶ Playback Integration

Control playback directly from the extension.

Features include:

* Play album immediately
* Queue album
* Queue individual tracks
* Multi-select albums
* Queue multiple albums
* Continue playback automatically when the queue finishes
* Move queue between zones - zone switcher

⸻

📺 Full Screen Wall Display

Turn a TV or tablet into a beautiful now-playing display.

Features include:

* Large album artwork
* Artist photography
* Album reviews
* Artist biographies
* Related albums
* Related artists
* Record label information
* YouTube music videos
* Playback progress
* Automatic information rotation
* Multiple display modes

Ideal for dedicated listening rooms.
YouTube videos, if suitable and available will play automatically at the start of a track but may not be in sync to the music.

⸻

🏷 Record Label Explorer

Explore your collection by record label.

Features include:

* Label of the week
* Label artwork
* Discogs integration
* FanArt.tv artwork
* Label merging
* Undo merged labels
* Browse every release from a selected label

⸻

📻 Random Album Radio

Automatically keeps the music flowing.

When the current queue finishes the extension can automatically:

* Select another album
* Avoid recently played albums
* Continue playback indefinitely

Perfect for effortless album listening.

⸻

⭐ Artist Discovery

Learn more about the music you’re listening to.

Includes:

* Artist biographies
* Artist images
* Related artists
* Navigation between artists and albums

⸻

🌐 Online Integrations

Supports information and artwork from:

* Roon
* Qobuz
* TIDAL
* Discogs
* FanArt.tv
* Pitchfork
* YouTube

⸻

📤 Sharing

Create attractive share cards for social media featuring:

* Album artwork
* Artist
* Album title
* Clean modern layout

⸻

🔄 Automatic Updates

Stay up to date with the latest features.

* Built-in update checker
* GitHub release integration
* One-click updates

⸻

🐳 Docker Support

Designed for simple deployment.

Includes:

* Docker image
* Docker Compose support
* Persistent configuration
* Automatic migration of pairing information
* Simple upgrades

⸻

⚡ Modern Interface

Designed specifically for large music libraries.

* Responsive interface
* Fast navigation
* Mobile friendly
* Desktop friendly
* TV friendly
* Dark/light themes
* Clean album-first design
  
---

## Setting up Discogs, FanArt.tv and YouTube API keys

Both are free and significantly improve label logo coverage.

### Discogs personal access token

Discogs is used to find label names for albums that iTunes and MusicBrainz miss, and to fetch label logos.

1. Sign in (or register free) at [discogs.com](https://www.discogs.com)
2. Go to **Settings → Developers** → click **Generate new token**
3. Copy the token
4. In the extension, tap the gear icon → paste into **Discogs token** → tap **Save**

### FanArt.tv API key

FanArt.tv provides high-quality label logos for labels that have a MusicBrainz MBID.

1. Register free at [fanart.tv](https://fanart.tv/get-an-api-key/#personal) for a personel API token
2. login or register
3. follow onscreen prompts (or come back here after registering/login and click on above link)
4. Copy the key shown there
5. In the extension, tap the gear icon → paste into **FanArt.tv key** → tap **Save**

### YouTube API key

Optional: Getting a YouTube Data API v3 key (free)

1. Go to console.cloud.google.com and sign in with any Google account.
2. Create a project: click the project dropdown (top bar) → New project → name it anything (e.g. “MusicD Display”) → Create, and make sure it’s selected.
3. Enable the API: menu → APIs & Services → Library → search “YouTube Data API v3” → open it → Enable.
4. Create the key: APIs & Services → Credentials → + Create credentials → API key. Copy the key shown.
5. (Recommended) Click Edit API key → under “API restrictions” choose Restrict key → tick only YouTube Data API v3 → Save. This makes the key useless for anything else if it ever leaks.
6. Paste the key into MusicD → Settings → YouTube API key → Save.
   
No billing account is needed — the free quota (10,000 units/day) comfortably covers a home display: each new track costs about 100 units, and results are cached, so that’s roughly 90+ fresh tracks per day before it would ever pause until midnight (Pacific time), when the quota resets.

---

> **Note on label accuracy** — an album may appear under a label that differs from the one shown in the album view. This could be correct: many albums could be released under multiple labels simultaneously (for example, Daughtry's *Baptized* was released under 19 Recordings, RCA, and Sony Music). The extension shows whichever label your file tags or the scan sources attribute to the album in the case of being a Qobuz or Tidal version.

---

## Run with Docker

This app talks to Lyrion Music Server purely over the network (HTTP on the LMS
web port, default `9000`). It does **not** need to run on the same machine as
LMS — it can run anywhere that can reach the LMS host and port.

No local clone needed — `docker build` can pull the repo straight from GitHub:

```bash
docker build -t musicd-lms-remote https://github.com/meltface-80/MusicD-LMS-Remote.git#main
```

### Option A — same machine as LMS

Host networking lets the app auto-discover LMS on the local network, so
`LMS_HOST` is optional (set it anyway if discovery doesn't find your server):

```bash
docker run -d \
  --name musicd-lms-remote \
  --restart unless-stopped \
  --network host \
  -v musicd-lms-remote-data:/app/data \
  musicd-lms-remote
```

Then open `http://<this-machine-IP>:3390`.

### Option B — separate machine from LMS

Running the app on a different box (another server, a NAS, a Pi)? Point it at
your LMS with `LMS_HOST` and publish the app's port. UDP auto-discovery only
works on the same LAN segment, so **set `LMS_HOST` explicitly** here:

```bash
docker run -d \
  --name musicd-lms-remote \
  --restart unless-stopped \
  -e LMS_HOST=192.168.1.50 \
  -e LMS_PORT=9000 \
  -p 3390:3390 \
  -v musicd-lms-remote-data:/app/data \
  musicd-lms-remote
```

Replace `192.168.1.50` with your LMS server's IP, then open
`http://<this-machine-IP>:3390`. (You can also set the host/port later from the
in-app **Settings** screen instead of the env vars.)

### Optional — mount your music for richer label scanning

Labels are normally derived from online metadata sources (iTunes, MusicBrainz,
etc.). If the machine running this app can see your music files, mount the
library **read-only** and the label scan will also read the `LABEL` /
`ORGANIZATION` tags straight from your files — the most accurate source. Add
this flag to either option above:

```bash
  -v /path/to/your/music:/music:ro
```

The mount is entirely optional and **read-only** — the app never writes to it.
Omit it when the music isn't present on this machine; labels then come from the
online sources alone and everything else works the same. If your library lives
somewhere other than `/music` inside the container, set `-e MUSIC_DIR=/that/path`
to match.

### docker-compose

A [`docker-compose.yml`](docker-compose.yml) is included — or generate one
tailored to your setup with the
[install configurator](https://meltface-80.github.io/MusicD-LMS-Remote/#install).
Edit `LMS_HOST` (and uncomment the `/music` mount if you want file-tag label
scanning), then:

```bash
docker compose up -d --build
```

(`--build` makes Compose build the image straight from GitHub rather than ever
trying to pull it from a registry — there is no registry image.)

## Environment variables

| Var | Default | Purpose |
|-----|---------|---------|
| `PORT` | `3390` | HTTP port for this app |
| `LMS_HOST` | *(discover)* | LMS server host/IP. Required when the app runs on a different machine/subnet than LMS (UDP discovery only works on the same LAN). |
| `LMS_PORT` | `9000` | LMS JSON-RPC/web port |
| `LMS_USER` / `LMS_PASS` | – | LMS HTTP auth, if enabled |
| `MUSIC_DIR` | `/music` | Where the optional read-only music mount lives inside the container (only used for file-tag label scanning). |
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
