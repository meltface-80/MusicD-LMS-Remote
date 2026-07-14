// ---------------------------------------------------------------------------
// Lyrion Music Server (LMS / formerly Logitech Media Server) adapter.
//
// This is the LMS equivalent of the Roon integration the original MusicD Remote
// used. It speaks the LMS JSON-RPC / CLI API (POST /jsonrpc.js) and exposes the
// small set of primitives the app needs, in the SAME data shapes the rest of
// the server expects, so the HTTP /api contract — and therefore the whole PWA
// frontend — is unchanged.
//
// LMS is in several ways simpler than Roon:
//   - No pairing/authorisation dance. It is an HTTP host:port. We either take a
//     configured host or auto-discover one over UDP 3483.
//   - Albums come back from a database query with a stable numeric id AND a
//     natural offset, so there is no Roon-style "filtered browse then Play Now
//     action item" navigation — a play is a single `playlistcontrol` call.
//   - Artwork is a plain HTTP URL (/music/<coverid>/cover.jpg), not a binary
//     image API, so image serving becomes a cached proxy/redirect.
//
// The API surface intentionally mirrors what index.js consumes: album lists
// (paged, for the in-memory search index), album tracks, players ("zones"),
// now-playing status, queue, and the transport/volume controls, plus server-
// and player-pref get/set so a Material-skin-level settings UI can be built on
// top.
//
// No external dependencies — Node's built-in http/dgram only.
// ---------------------------------------------------------------------------
"use strict";

const http = require("http");
const dgram = require("dgram");
const { makeLogger } = require("./log");

// ---------------------------------------------------------------------------
// Low-level JSON-RPC transport
// ---------------------------------------------------------------------------

// A single slim.request call. `playerId` is "" for server-global commands
// (albums, players, pref, …) or a player MAC/id for player commands.
// `command` is the CLI command array, e.g. ["albums", 0, 50, "tags:lay"].
function rpc(cfg, playerId, command) {
  const payload = JSON.stringify({
    id: 1,
    method: "slim.request",
    params: [playerId || "", command]
  });

  const headers = {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(payload)
  };
  // LMS HTTP auth (only when the server has a username/password set).
  if (cfg.username) {
    const basic = Buffer.from(`${cfg.username}:${cfg.password || ""}`).toString("base64");
    headers["Authorization"] = "Basic " + basic;
  }

  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: cfg.host, port: cfg.port, path: "/jsonrpc.js", method: "POST", headers },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          if (res.statusCode === 401) return reject(new Error("LMS authentication failed (401)"));
          if (res.statusCode !== 200) return reject(new Error("LMS HTTP " + res.statusCode));
          const text = Buffer.concat(chunks).toString("utf8");
          let json;
          try { json = JSON.parse(text); }
          catch (e) { return reject(new Error("LMS returned non-JSON: " + text.slice(0, 120))); }
          // slim.request always wraps the answer in `result`.
          resolve(json && json.result ? json.result : {});
        });
      }
    );
    req.on("error", reject);
    // Guard every call — a wedged LMS must not hang a request forever.
    req.setTimeout(cfg.timeoutMs, () => req.destroy(new Error("LMS request timed out")));
    req.write(payload);
    req.end();
  });
}

// ---------------------------------------------------------------------------
// Normalisers — turn LMS loops into the flat shapes the app expects.
// ---------------------------------------------------------------------------

// LMS numeric fields arrive as strings ("2021") or numbers depending on tag.
function num(v) {
  if (v == null || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// Cover key for a row: a real cover id when LMS has one; otherwise the row's
// artwork_url (online-library albums/tracks often carry ONLY that), encoded
// as a self-identifying "url-<base64url>" key so it survives the image_key
// round-trip through the frontend and back into /api/image. Hex/numeric
// cover ids can never start with "url-", so the forms can't collide.
function coverKey(row) {
  const id = row.coverid || row.artwork_track_id || null;
  if (id != null) return String(id);
  const u = row.artwork_url;
  if (u) return "url-" + Buffer.from(String(u), "utf8").toString("base64url");
  return null;
}

// Best-effort online-source detection. LMS marks online-library albums with an
// `extid` like "qobuz:album:12345" (Tidal uses "tidal:"/"wimp:"). We read it
// defensively — it may be absent, and how a given LMS/plugin surfaces it can
// vary by version. When no signal is present we return null and the album is
// treated as a local one (no badge, no layout change).
//
// NOTE: `albums` loops don't always carry `extid` under a dedicated tag letter;
// we read `row.extid`/`row.url` from whatever the current query returns. If a
// user's LMS doesn't surface extid here, this detection may need a one-line
// live-server tweak (see ALBUM_TAGS) — see report/comments for details.
function albumSource(row) {
  const ext = String((row && (row.extid || row.url)) || "").toLowerCase();
  if (ext.startsWith("qobuz:") || ext.includes("qobuz")) return "qobuz";
  if (ext.startsWith("tidal:") || ext.startsWith("wimp:") || ext.includes("tidal")) return "tidal";
  return null;
}

// An album row from an `albums` query. `offset` is the row's position in the
// full, identically-sorted list (the caller supplies the base offset), so the
// app keeps its Roon-era notion of an album "offset" for deep-linking.
function albumRecord(row, offset) {
  // Artwork: a local cover id, or the encoded remote artwork_url (online
  // albums) — see coverKey().
  const coverId = coverKey(row);
  return {
    id:        String(row.id),
    offset,
    title:     row.album || "",
    // The app treats an album's "subtitle" as its artist string.
    subtitle:  row.artist || row.albumartist || "",
    year:      num(row.year),
    coverId,
    // Online-library provenance ("qobuz"/"tidal"/null); best-effort, see above.
    source:    albumSource(row),
    // Raw online-library id ("qobuz:album:123…" / "tidal:album:…") — the
    // review lookup extracts the service album id from it.
    extid:     row.extid || null,
    // Album MusicBrainz id from local file tags, when LMS carries one (tag M
    // is requested; servers/rows without it simply omit the field).
    mbid:      row.musicbrainz_id || null,
    artistId:  row.artist_id != null ? String(row.artist_id) : null
  };
}

function trackRecord(row) {
  return {
    id:       String(row.id),
    title:    row.title || "",
    trackNum: num(row.tracknum),
    disc:     num(row.disc),
    duration: num(row.duration),
    artist:   row.artist || row.trackartist || row.albumartist || "",
    album:    row.album || "",
    coverId:  coverKey(row),
    // Quality fields (tags o/r/T/I) — bitrate is LMS's display string
    // ("845kbps VBR"); samplerate is Hz; samplesize is bits; type is the
    // content type ("flc", "mp3", …). All best-effort.
    type:       row.type || null,
    bitrate:    row.bitrate || null,
    samplerate: num(row.samplerate),
    samplesize: num(row.samplesize),
    mbid:       row.musicbrainz_id || null
  };
}

function playerRecord(row) {
  return {
    id:        row.playerid,
    name:      row.name || row.playerid,
    model:     row.modelname || row.model || "",
    connected: row.connected === 1 || row.connected === "1",
    power:     row.power === 1 || row.power === "1",
    isPlayer:  row.isplayer === undefined ? true : (row.isplayer === 1 || row.isplayer === "1")
  };
}

// The current track out of a `status` result. LMS puts it in playlist_loop at
// playlist_cur_index; for radio/streams it may live under remoteMeta.
function nowPlayingTrack(status) {
  const idx  = num(status.playlist_cur_index);
  const loop = status.playlist_loop || [];
  let cur = null;
  if (idx != null) cur = loop.find((t) => num(t["playlist index"]) === idx) || loop[0] || null;
  else cur = loop[0] || null;
  const meta = status.remoteMeta || null;
  const src  = cur || meta;
  if (!src) return null;
  return {
    id:       src.id != null ? String(src.id) : null,
    title:    src.title || "",
    artist:   src.artist || src.trackartist || src.albumartist || "",
    album:    src.album || "",
    duration: num(src.duration != null ? src.duration : status.duration),
    coverId:  coverKey(src)
  };
}

// Full `status` → normalised now-playing state (mode/time/volume/queue index).
function statusState(status) {
  const mode = status.mode || "stop";
  return {
    mode,                                            // "play" | "pause" | "stop"
    playing:  mode === "play",
    time:     num(status.time) || 0,                 // elapsed seconds
    duration: num(status.duration),                  // current track length
    volume:   num(status["mixer volume"]),
    muted:    status["mixer muting"] === 1 || status["mixer muting"] === "1",
    index:    num(status.playlist_cur_index),
    total:    num(status.playlist_tracks) || 0,
    track:    nowPlayingTrack(status)
  };
}

// ---------------------------------------------------------------------------
// Adapter factory
// ---------------------------------------------------------------------------

// One-line summary of an LMS result for the command trace — a count, a
// *_loop length, or the first few keys. Never dumps the whole payload.
function summarizeResult(r) {
  if (!r || typeof r !== "object") return String(r);
  if (r.count != null) return "count=" + r.count;
  const loopKey = Object.keys(r).find(k => k.endsWith("_loop"));
  if (loopKey) return loopKey + "=" + (Array.isArray(r[loopKey]) ? r[loopKey].length : "?");
  const keys = Object.keys(r);
  return keys.length ? "{" + keys.slice(0, 6).join(",") + (keys.length > 6 ? ",…" : "") + "}" : "empty";
}

function createLms(opts = {}) {
  const cfg = {
    host:      opts.host || "127.0.0.1",
    port:      Number(opts.port) || 9000,
    username:  opts.username || null,
    password:  opts.password || null,
    timeoutMs: Number(opts.timeoutMs) || 8000
  };

  const log = opts.log || makeLogger("lms");

  // Every JSON-RPC command flows through here. At trace level each command is
  // logged with its target player, a compact result summary and elapsed ms; a
  // failing command is logged at debug (the callers mostly swallow the throw,
  // so this is the only place the failure is otherwise visible).
  const request = async (playerId, command) => {
    const started = Date.now();
    const label = Array.isArray(command) ? command.slice(0, 5).map(String).join(" ") : String(command);
    const who = playerId ? playerId + " " : "";
    try {
      const result = await rpc(cfg, playerId, command);
      if (log.enabled("trace")) log.trace(who + label, "→", (Date.now() - started) + "ms", summarizeResult(result));
      return result;
    } catch (e) {
      log.debug("command failed:", who + label, "—", e.message, "(" + (Date.now() - started) + "ms)");
      throw e;
    }
  };

  // ---- server / library reads ----

  async function serverStatus() {
    const r = await request("", ["serverstatus", 0, 99]);
    return {
      version:     r.version || null,
      uuid:        r.uuid || null,
      playerCount: num(r["player count"]) || 0,
      lastScan:    num(r.lastscan),
      scanning:    r.rescan === 1 || r.rescan === "1",
      players:     (r.players_loop || []).map(playerRecord)
    };
  }

  // Album-tag string: l(album) a(artist) y(year) j(coverid) S(artist_id)
  // e(extid) — extid carries the online-library provenance for streaming
  // albums, e.g. "qobuz:album:..." / "tidal:album:...", which albumSource()
  // reads to badge the tile. LMS only includes extid when the tag is asked
  // for (E is added too, harmlessly, to cover tag-letter differences across
  // LMS versions; unrecognised tags are ignored). M asks for the album
  // MusicBrainz id (present when local files are MusicBrainz-tagged).
  // K asks for artwork_url: online-library (Qobuz/Tidal) albums store a
  // remote https URL as their art and return it ONLY under this tag (j and K
  // are mutually exclusive per row) — without K their tiles have no cover.
  const ALBUM_TAGS = "laySjeEMK";

  function albumFilterArgs(f = {}) {
    const args = [];
    if (f.search)        args.push("search:" + f.search);
    if (f.genreId  != null) args.push("genre_id:" + f.genreId);
    if (f.artistId != null) args.push("artist_id:" + f.artistId);
    if (f.year     != null) args.push("year:" + f.year);
    if (f.compilation)   args.push("compilation:1");
    return args;
  }

  // Total album count (optionally filtered) without fetching rows.
  async function countAlbums(f = {}) {
    const r = await request("", ["albums", 0, 1, ...albumFilterArgs(f)]);
    return num(r.count) || 0;
  }

  // A page of albums, sorted identically to how the full index is built so a
  // row's absolute position is a stable offset.
  async function listAlbums({ start = 0, count = 200, sort = "album", ...filters } = {}) {
    const r = await request("", [
      "albums", start, count, "tags:" + ALBUM_TAGS, "sort:" + sort, ...albumFilterArgs(filters)
    ]);
    const loop = r.albums_loop || [];
    return {
      count:  num(r.count) || 0,
      albums: loop.map((row, i) => albumRecord(row, start + i))
    };
  }

  async function searchAlbums(term, count = 30) {
    const { albums } = await listAlbums({ start: 0, count, search: term });
    return albums;
  }

  // Contributor lookup by name — the `artists` CLI query. Returns
  // [{ id, name }] so callers can run artist_id-filtered album queries (which
  // include track-level contributions LMS knows about, i.e. real
  // "appears on" data, unlike a string match over album subtitles).
  async function searchArtists(term, count = 10) {
    const r = await request("", ["artists", 0, count, "search:" + term]);
    return (r.artists_loop || []).map((row) => ({
      id:   String(row.id),
      name: row.artist || ""
    }));
  }

  // All album genres, with the album count in each. The `genres` CLI query
  // itself only returns {id, genre} — no per-genre album count — so we reuse
  // the already-filtered `countAlbums({ genreId })` path (same albumFilterArgs
  // plumbing `listAlbums` uses) instead of inventing a new, unverified LMS
  // query shape. Counts are fetched in parallel since a library can have
  // dozens of genres and this is a Home-row load, not a hot path.
  async function genres() {
    const r = await request("", ["genres", 0, 999999, "tags:s"]);
    const loop = r.genres_loop || [];
    const list = loop.map((row) => ({ id: String(row.id), title: row.genre || "" }));
    const counts = await Promise.all(list.map((g) => countAlbums({ genreId: g.id })));
    return list
      .map((g, i) => ({ id: g.id, title: g.title, count: counts[i] }))
      .sort((a, b) => b.count - a.count);
  }

  // Track/song tags: a(artist) l(album) d(duration) t(tracknum) i(disc)
  // e(album_id) c(coverid) y(year). NB: for SONGS the cover id is tag `c`
  // (`coverid`); tag `j` on a song is the coverart boolean, not the id — only
  // ALBUM queries use `j` for artwork_track_id. Getting this wrong leaves every
  // track/queue thumbnail blank. o(type) r(bitrate) T(samplerate)
  // I(samplesize) feed the queue's quality summary; M is the track
  // MusicBrainz id when files are tagged. K adds artwork_url for remote/
  // online tracks whose art isn't a local coverid.
  const TRACK_TAGS = "acdeiltyorTIMK";

  async function albumTracks(albumId) {
    const r = await request("", [
      "titles", 0, 1000, "album_id:" + albumId, "tags:" + TRACK_TAGS, "sort:tracknum"
    ]);
    return (r.titles_loop || []).map(trackRecord);
  }

  // ---- players ("zones") ----

  async function players() {
    const r = await request("", ["players", 0, 99]);
    return (r.players_loop || []).map(playerRecord);
  }

  async function playerStatus(playerId) {
    // "-" + 1 asks for the current track only, with the tags we normalise.
    const r = await request(playerId, ["status", "-", 1, "tags:aldjyKc"]);
    return statusState(r);
  }

  // The player's playlist plus WHERE it currently is. curIndex is
  // playlist_cur_index (null when nothing is loaded) — callers use it to show
  // only the current + upcoming tracks rather than the already-played ones.
  async function queue(playerId) {
    const r = await request(playerId, ["status", 0, 9999, "tags:" + TRACK_TAGS]);
    const loop = r.playlist_loop || [];
    const tracks = loop.map((row) => {
      const t = trackRecord(row);
      t.index = num(row["playlist index"]);
      return t;
    });
    return { tracks, curIndex: num(r.playlist_cur_index) };
  }

  // ---- playback / transport ----

  // mode: "now" (replace + play), "next" (insert after current), "queue" (append)
  const PLC = { now: "load", next: "insert", queue: "add" };

  async function playAlbum(playerId, albumId, mode = "now") {
    return request(playerId, ["playlistcontrol", "cmd:" + (PLC[mode] || "load"), "album_id:" + albumId]);
  }

  async function playTracks(playerId, trackIds, mode = "now") {
    const ids = (Array.isArray(trackIds) ? trackIds : [trackIds]).join(",");
    return request(playerId, ["playlistcontrol", "cmd:" + (PLC[mode] || "load"), "track_id:" + ids]);
  }

  // action: play | pause | stop | next | prev | toggle
  async function transport(playerId, action) {
    switch (action) {
      case "play":   return request(playerId, ["play"]);
      case "pause":  return request(playerId, ["pause", "1"]);
      case "resume": return request(playerId, ["pause", "0"]);
      case "toggle": return request(playerId, ["pause"]);       // no arg = toggle
      case "stop":   return request(playerId, ["stop"]);
      case "next":   return request(playerId, ["playlist", "index", "+1"]);
      case "prev":   return request(playerId, ["playlist", "index", "-1"]);
      default: throw new Error("Unknown transport action: " + action);
    }
  }

  async function playIndex(playerId, index) {
    return request(playerId, ["playlist", "index", String(index)]);
  }

  // queue_item_id is the playlist index; deleting shifts later indices down, so
  // callers should re-fetch the queue afterwards.
  async function removeFromQueue(playerId, index) {
    return request(playerId, ["playlist", "delete", String(index)]);
  }

  async function seek(playerId, seconds) {
    return request(playerId, ["time", String(Math.max(0, Math.round(seconds)))]);
  }

  async function setVolume(playerId, vol) {
    return request(playerId, ["mixer", "volume", String(Math.max(0, Math.min(100, Math.round(vol))))]);
  }

  // Relative change, done atomically by LMS itself (`mixer volume +N` / `-N`),
  // so rapid presses accumulate correctly without a read-modify-write race.
  async function adjustVolume(playerId, delta) {
    const d = Math.round(delta);
    return request(playerId, ["mixer", "volume", (d >= 0 ? "+" : "") + d]);
  }

  async function setMute(playerId, muted) {
    return request(playerId, ["mixer", "muting", muted ? "1" : "0"]);
  }

  // ---- per-player settings primitives (native player-settings pane) ----

  // Queue MODES (not stored prefs): shuffle 0 off / 1 songs / 2 albums,
  // repeat 0 off / 1 one / 2 all. The "?" query form returns the current
  // value under _shuffle/_repeat.
  async function getPlayerModes(playerId) {
    const [sh, rp] = await Promise.all([
      request(playerId, ["playlist", "shuffle", "?"]),
      request(playerId, ["playlist", "repeat", "?"])
    ]);
    return { shuffle: num(sh._shuffle), repeat: num(rp._repeat) };
  }
  async function setShuffle(playerId, mode) {
    return request(playerId, ["playlist", "shuffle", String(mode)]);
  }
  async function setRepeat(playerId, mode) {
    return request(playerId, ["playlist", "repeat", String(mode)]);
  }

  // Player rename. READ via the `playername` pref (documented), falling back
  // to the `name ?` query; WRITE via the `name` CLI command (not a playerpref
  // set — the command also fires LMS's rename notification).
  async function getPlayerName(playerId) {
    const v = await getPlayerPref(playerId, "playername").catch(() => null);
    if (v) return v;
    const r = await request(playerId, ["name", "?"]);
    return r._value !== undefined ? r._value : null;
  }
  async function setPlayerName(playerId, name) {
    return request(playerId, ["name", String(name)]);
  }

  async function getPower(playerId) {
    const r = await request(playerId, ["power", "?"]);
    return r._power === 1 || r._power === "1";
  }
  async function setPower(playerId, on) {
    return request(playerId, ["power", on ? "1" : "0"]);
  }

  // Don't Stop The Music (bundled LMS plugin): the stock
  // `dontstopthemusicsetting` player query lists every registered provider
  // with its localized name + which is selected. Each item carries the
  // provider key at actions.do.cmd[2] ("0" = Disabled). Empty options =
  // the plugin is disabled on the server.
  async function dstmOptions(playerId) {
    const r = await request(playerId, ["dontstopthemusicsetting"]);
    const loop = r.item_loop || [];
    const options = [];
    let current = "0";
    for (const it of loop) {
      const cmd = it && it.actions && it.actions.do && it.actions.do.cmd;
      if (!Array.isArray(cmd) || cmd.length < 3) continue;
      const key = String(cmd[2]);
      options.push({ key, text: it.text || key });
      if (it.radio === 1 || it.radio === "1") current = key;
    }
    return { options, current };
  }
  async function setDstm(playerId, provider) {
    return request(playerId, ["playerpref", "plugin.dontstopthemusic:provider", String(provider || 0)]);
  }

  // Sync groups across the server: [{ members: [ids], names: [names] }].
  async function syncGroups() {
    const r = await request("", ["syncgroups", "?"]);
    const loop = r.syncgroups_loop || [];
    return loop.map((g) => ({
      members: String(g.sync_members || "").split(",").filter(Boolean),
      names:   String(g.sync_member_names || "").split(",").filter(Boolean)
    }));
  }

  // Move playback from one player to another by syncing then unsyncing, or by
  // transferring the playlist. LMS's closest primitive is playlist transfer via
  // save/load; the simplest robust move is to copy the queue and play index.
  // Left as a thin helper so callers can decide the policy.
  async function syncPlayers(masterId, slaveId) {
    return request(slaveId, ["sync", masterId]);
  }
  async function unsync(playerId) {
    return request(playerId, ["sync", "-"]);
  }

  // ---- preferences (server + per-player) for Material-skin-level settings ----

  async function getPref(name) {
    const r = await request("", ["pref", name, "?"]);
    return r._p2 !== undefined ? r._p2 : (r[name] !== undefined ? r[name] : null);
  }
  async function setPref(name, value) {
    return request("", ["pref", name, String(value)]);
  }
  // The "?" answer arrives under the positional key of the replaced token —
  // _p2 for the 3-token ["playerpref", name, "?"] form. _p3 kept as a
  // fallback for the namespaced 4-token form some LMS versions echo.
  async function getPlayerPref(playerId, name) {
    const r = await request(playerId, ["playerpref", name, "?"]);
    if (r._p2 !== undefined) return r._p2;
    if (r._p3 !== undefined) return r._p3;
    return r[name] !== undefined ? r[name] : null;
  }
  async function setPlayerPref(playerId, name, value) {
    return request(playerId, ["playerpref", name, String(value)]);
  }

  async function rescan(mode) {
    return request("", mode ? ["rescan", mode] : ["rescan"]);
  }

  // ---- artwork ----

  // Full HTTP URL for an album/track cover at a given square size. Callers that
  // proxy this add auth if needed; the URL form is LMS's resized-cover route.
  // "url-<base64url>" keys (remote artwork_url from online-library rows — see
  // coverKey) are served through LMS's imageproxy instead, which fetches,
  // caches and resizes the remote image:
  //   /imageproxy/<uri-escaped-url>/image_<W>x<H>_<mode>.jpg
  // (Slim::Web::ImageProxy::proxiedImage). Mode `o` scales to the width.
  function artworkUrl(coverId, size = 300) {
    const s = Math.max(32, Math.min(2000, Math.round(size)));
    const id = String(coverId || "unknown");
    if (id.startsWith("url-")) {
      let remote = "";
      try { remote = Buffer.from(id.slice(4), "base64url").toString("utf8"); } catch (e) { /* fall through */ }
      if (remote) {
        return `http://${cfg.host}:${cfg.port}/imageproxy/${encodeURIComponent(remote)}/image_${s}x${s}_o.jpg`;
      }
    }
    return `http://${cfg.host}:${cfg.port}/music/${id}/cover_${s}x${s}_o.jpg`;
  }

  // Reachability probe — resolves true if the server answers serverstatus.
  async function ping() {
    try { await serverStatus(); return true; }
    catch (e) { return false; }
  }

  return {
    cfg,
    request,
    serverStatus, ping,
    countAlbums, listAlbums, searchAlbums, searchArtists, albumTracks, genres,
    players, playerStatus, queue,
    getPlayerModes, setShuffle, setRepeat, getPlayerName, setPlayerName,
    getPower, setPower, syncGroups, dstmOptions, setDstm,
    playAlbum, playTracks, transport, playIndex, removeFromQueue, seek, setVolume, adjustVolume, setMute,
    syncPlayers, unsync,
    getPref, setPref, getPlayerPref, setPlayerPref, rescan,
    artworkUrl
  };
}

// ---------------------------------------------------------------------------
// UDP auto-discovery (best-effort). LMS answers a discovery datagram on UDP
// 3483 with TLV fields; we read the JSON (web/CLI port) and the responder's IP.
// A configured host always wins; discovery is the fallback for zero-config.
// ---------------------------------------------------------------------------
function discover({ timeoutMs = 2500 } = {}) {
  return new Promise((resolve) => {
    const socket = dgram.createSocket("udp4");
    // 'e' = extended discovery request; ask for the JSON (web) port + name.
    // Each tag is 4 bytes followed by a 1-byte length (0 = "please send it").
    const tags = ["NAME", "JSON", "VERS", "IPAD"];
    const parts = [Buffer.from("e")];
    for (const t of tags) parts.push(Buffer.from(t), Buffer.from([0]));
    const query = Buffer.concat(parts);

    let done = false;
    const finish = (val) => {
      if (done) return;
      done = true;
      try { socket.close(); } catch (e) { /* already closed */ }
      resolve(val);
    };

    socket.on("error", () => finish(null));
    socket.on("message", (msg, rinfo) => {
      // Reply starts with 'E'; then repeating [4-byte tag][1-byte len][value].
      if (!msg || msg[0] !== 0x45 /* 'E' */) return;
      const fields = {};
      let i = 1;
      while (i + 5 <= msg.length) {
        const tag = msg.toString("ascii", i, i + 4);
        const len = msg[i + 4];
        const val = msg.toString("utf8", i + 5, i + 5 + len);
        fields[tag] = val;
        i += 5 + len;
      }
      finish({
        host: rinfo.address,
        port: Number(fields.JSON) || 9000,
        name: fields.NAME || null,
        version: fields.VERS || null
      });
    });

    socket.bind(() => {
      try {
        socket.setBroadcast(true);
        socket.send(query, 0, query.length, 3483, "255.255.255.255");
      } catch (e) { finish(null); }
    });

    setTimeout(() => finish(null), timeoutMs);
  });
}

module.exports = { createLms, discover, _internal: { albumRecord, trackRecord, playerRecord, statusState, nowPlayingTrack } };
