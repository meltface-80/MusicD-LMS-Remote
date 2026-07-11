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

// An album row from an `albums` query. `offset` is the row's position in the
// full, identically-sorted list (the caller supplies the base offset), so the
// app keeps its Roon-era notion of an album "offset" for deep-linking.
function albumRecord(row, offset) {
  // Artwork id: newer LMS returns `coverid`, older `artwork_track_id`.
  const coverId = row.coverid || row.artwork_track_id || null;
  return {
    id:        String(row.id),
    offset,
    title:     row.album || "",
    // The app treats an album's "subtitle" as its artist string.
    subtitle:  row.artist || row.albumartist || "",
    year:      num(row.year),
    coverId:   coverId != null ? String(coverId) : null
  };
}

function trackRecord(row) {
  const coverId = row.coverid || row.artwork_track_id || null;
  return {
    id:       String(row.id),
    title:    row.title || "",
    trackNum: num(row.tracknum),
    disc:     num(row.disc),
    duration: num(row.duration),
    artist:   row.artist || row.trackartist || row.albumartist || "",
    album:    row.album || "",
    coverId:  coverId != null ? String(coverId) : null
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
  const coverId = src.coverid || src.artwork_track_id || null;
  return {
    id:       src.id != null ? String(src.id) : null,
    title:    src.title || "",
    artist:   src.artist || src.trackartist || src.albumartist || "",
    album:    src.album || "",
    duration: num(src.duration != null ? src.duration : status.duration),
    coverId:  coverId != null ? String(coverId) : null
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

function createLms(opts = {}) {
  const cfg = {
    host:      opts.host || "127.0.0.1",
    port:      Number(opts.port) || 9000,
    username:  opts.username || null,
    password:  opts.password || null,
    timeoutMs: Number(opts.timeoutMs) || 8000
  };

  const request = (playerId, command) => rpc(cfg, playerId, command);

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

  // Album-tag string: l(album) a(artist) y(year) j(coverid) S(artist_id).
  const ALBUM_TAGS = "laySj";

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

  // Track/song tags: a(artist) l(album) d(duration) t(tracknum) i(disc)
  // e(album_id) c(coverid) y(year). NB: for SONGS the cover id is tag `c`
  // (`coverid`); tag `j` on a song is the coverart boolean, not the id — only
  // ALBUM queries use `j` for artwork_track_id. Getting this wrong leaves every
  // track/queue thumbnail blank.
  const TRACK_TAGS = "acdeilty";

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

  async function queue(playerId) {
    const r = await request(playerId, ["status", 0, 9999, "tags:" + TRACK_TAGS]);
    const loop = r.playlist_loop || [];
    return loop.map((row) => {
      const t = trackRecord(row);
      t.index = num(row["playlist index"]);
      return t;
    });
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
  async function getPlayerPref(playerId, name) {
    const r = await request(playerId, ["playerpref", name, "?"]);
    return r._p3 !== undefined ? r._p3 : (r[name] !== undefined ? r[name] : null);
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
  function artworkUrl(coverId, size = 300) {
    const s = Math.max(32, Math.min(2000, Math.round(size)));
    const id = coverId || "unknown";
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
    countAlbums, listAlbums, searchAlbums, albumTracks,
    players, playerStatus, queue,
    playAlbum, playTracks, transport, playIndex, seek, setVolume, adjustVolume, setMute,
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
