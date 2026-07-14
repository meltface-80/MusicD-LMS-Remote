// Unit tests for the LMS adapter against a mock JSON-RPC server.
// Run: node lms/lib/lms.test.js   (exit 0 = all pass)
"use strict";

const http = require("http");
const assert = require("assert");
const { createLms, _internal } = require("./lms");

// A mock LMS: records every slim.request and answers from a canned table
// keyed by the first command word. `lastReq` lets tests assert exact commands.
let lastReq = null;
let reqLog = [];
function mockResultFor(playerId, command) {
  const cmd = command[0];
  if (cmd === "serverstatus") {
    return { version: "8.4.0", "player count": 2, players_loop: [
      { playerid: "aa:bb", name: "Kitchen", modelname: "SqueezeLite", connected: 1, power: 1, isplayer: 1 },
      { playerid: "cc:dd", name: "Study",   modelname: "picoreplayer", connected: 0, power: 0, isplayer: 1 }
    ] };
  }
  if (cmd === "genres") {
    return { count: 3, genres_loop: [
      { id: 1, genre: "Rock" },
      { id: 2, genre: "Jazz" },
      { id: 3, genre: "Pop" }
    ] };
  }
  if (cmd === "albums") {
    const wantCount = command[2];
    const genreArg = command.find(a => String(a).startsWith("genre_id:"));
    // count-only probe
    if (wantCount === 1 && !command.some(a => String(a).startsWith("tags:"))) {
      if (genreArg) {
        const counts = { "1": 50, "2": 200, "3": 10 };
        return { count: counts[genreArg.split(":")[1]] || 0 };
      }
      return { count: 1234 };
    }
    return { count: 1234, albums_loop: [
      { id: 10, album: "Kind of Blue", artist: "Miles Davis", year: "1959", coverid: "abc123", extid: "qobuz:album:12345", artist_id: 7 },
      { id: 11, album: "Blue Train",   artist: "John Coltrane", year: 1957, artwork_track_id: 999, musicbrainz_id: "8fca67ea-b7a9-3d7a-9c1b-2b0b3ad6f851" },
      { id: 12, album: "Streamed",     artist: "Cloud Artist", year: 2024, extid: "qobuz:album:777",
        artwork_url: "https://static.qobuz.com/images/covers/xy/zz/abc_600.jpg" }
    ] };
  }
  if (cmd === "artists") {
    return { count: 2, artists_loop: [
      { id: 77, artist: "P!nk" },
      { id: 78, artist: "Pink Floyd" }
    ] };
  }
  if (cmd === "titles") {
    return { count: 2, titles_loop: [
      { id: 501, title: "So What",     tracknum: "1", disc: "1", duration: "545.6", artist: "Miles Davis", coverid: "abc123" },
      { id: 502, title: "Freddie Freeloader", tracknum: 2, duration: 586, artist: "Miles Davis" }
    ] };
  }
  if (cmd === "players") {
    return { count: 2, players_loop: [
      { playerid: "aa:bb", name: "Kitchen", modelname: "SqueezeLite", connected: 1, power: 1, isplayer: 1 },
      { playerid: "cc:dd", name: "Study",   modelname: "picoreplayer", connected: "0", power: "0", isplayer: "1" }
    ] };
  }
  if (cmd === "status") {
    return {
      mode: "play", time: "42.5", duration: "545.6",
      "mixer volume": "65", "mixer muting": "0",
      playlist_cur_index: "1", playlist_tracks: 12,
      playlist_loop: [
        { "playlist index": 0, id: 500, title: "Played Already", artist: "Miles Davis", album: "Kind of Blue", duration: "300" },
        { "playlist index": 1, id: 501, title: "So What", artist: "Miles Davis", album: "Kind of Blue", duration: "545.6", coverid: "abc123",
          type: "flc", bitrate: "845kbps", samplerate: "44100", samplesize: "16" },
        { "playlist index": 2, id: 502, title: "Freddie Freeloader", artist: "Miles Davis", album: "Kind of Blue", duration: 586 }
      ]
    };
  }
  if (cmd === "pref")       return { _p2: "1" };
  if (cmd === "playerpref") return { _p3: "50" };
  // playlistcontrol / play / pause / stop / playlist / time / mixer / rescan / sync
  return { count: 0 };
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", c => chunks.push(c));
  req.on("end", () => {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    const [playerId, command] = body.params;
    lastReq = { playerId, command };
    reqLog.push(lastReq);
    const result = mockResultFor(playerId, command);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ id: body.id, result }));
  });
});

(async () => {
  await new Promise(r => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;
  const lms = createLms({ host: "127.0.0.1", port, timeoutMs: 3000 });
  let n = 0;
  const ok = (label) => { console.log("  [PASS]", label); n++; };

  // serverStatus
  const ss = await lms.serverStatus();
  assert.strictEqual(ss.version, "8.4.0");
  assert.strictEqual(ss.playerCount, 2);
  assert.strictEqual(ss.players.length, 2);
  assert.strictEqual(ss.players[0].name, "Kitchen");
  ok("serverStatus parses version/players");

  // countAlbums (count-only probe path)
  const cnt = await lms.countAlbums();
  assert.strictEqual(cnt, 1234);
  ok("countAlbums returns count");

  // listAlbums — offsets + normalisation of coverid vs artwork_track_id
  const { count, albums } = await lms.listAlbums({ start: 40, count: 3 });
  assert.strictEqual(count, 1234);
  assert.strictEqual(albums.length, 3);
  assert.strictEqual(albums[0].offset, 40);
  assert.strictEqual(albums[1].offset, 41);
  assert.strictEqual(albums[0].id, "10");
  assert.strictEqual(albums[0].title, "Kind of Blue");
  assert.strictEqual(albums[0].subtitle, "Miles Davis");
  assert.strictEqual(albums[0].year, 1959);
  assert.strictEqual(albums[0].coverId, "abc123");
  assert.strictEqual(albums[1].coverId, "999"); // artwork_track_id fallback, stringified
  assert.strictEqual(albums[0].source, "qobuz"); // extid "qobuz:album:…" → source qobuz
  assert.strictEqual(albums[1].source, null);    // no extid → local album, no badge
  assert.strictEqual(albums[0].extid, "qobuz:album:12345"); // raw extid kept (review lookup reads the id)
  assert.strictEqual(albums[0].artistId, "7");   // tag S artist_id, stringified
  assert.strictEqual(albums[1].mbid, "8fca67ea-b7a9-3d7a-9c1b-2b0b3ad6f851"); // tag M MusicBrainz id
  // Online-library album: artwork_url only (no coverid) → encoded url- key,
  // and artworkUrl() must route it through LMS's imageproxy.
  const streamKey = albums[2].coverId;
  assert.ok(streamKey && streamKey.startsWith("url-"), "artwork_url encodes to a url- cover key: " + streamKey);
  const decoded = Buffer.from(streamKey.slice(4), "base64url").toString("utf8");
  assert.strictEqual(decoded, "https://static.qobuz.com/images/covers/xy/zz/abc_600.jpg");
  const proxied = lms.artworkUrl(streamKey, 300);
  assert.ok(proxied.includes("/imageproxy/") && proxied.endsWith("/image_300x300_o.jpg"), proxied);
  assert.ok(proxied.includes(encodeURIComponent(decoded)), "remote url is uri-escaped inside the path");
  const albTagsK = lastReq.command.find(a => /^tags:/.test(String(a))) || "";
  assert.ok(String(albTagsK).includes("K"), "albums tags must request artwork_url (K)");
  // command shape
  assert.strictEqual(lastReq.command[0], "albums");
  assert.strictEqual(lastReq.command[1], 40);
  // Tags must carry the base fields plus extid (e) — extid is what surfaces an
  // album's online-library provenance (qobuz:album:…) for the source badge.
  const albTags = lastReq.command.find(a => /^tags:/.test(String(a))) || "";
  assert.ok(albTags.includes("laySj") && albTags.includes("e"), "albums tags must include base tags + extid (e)");
  assert.ok(lastReq.command.includes("sort:album"));
  ok("listAlbums offsets + coverid fallback + command shape");

  // searchAlbums adds search: filter
  await lms.searchAlbums("blue", 5);
  assert.ok(lastReq.command.includes("search:blue"));
  ok("searchAlbums passes search term");

  // genres — full list + per-genre counts (via genre_id count-query plumbing),
  // sorted biggest-first
  reqLog.length = 0;
  const gs = await lms.genres();
  assert.strictEqual(gs.length, 3);
  assert.deepStrictEqual(gs.map(g => g.title), ["Jazz", "Rock", "Pop"]); // 200, 50, 10
  assert.strictEqual(gs[0].count, 200);
  assert.strictEqual(gs[0].id, "2");
  const genreCountReqs = reqLog.filter(r =>
    r.command[0] === "albums" && r.command.some(a => String(a).startsWith("genre_id:")));
  assert.strictEqual(genreCountReqs.length, 3);
  ok("genres returns list sorted by count desc, using genre_id count queries");

  // albumTracks
  const tracks = await lms.albumTracks(10);
  assert.strictEqual(tracks.length, 2);
  assert.strictEqual(tracks[0].id, "501");
  assert.strictEqual(tracks[0].trackNum, 1);
  assert.strictEqual(tracks[0].duration, 545.6);
  assert.strictEqual(lastReq.command[0], "titles");
  assert.ok(lastReq.command.includes("album_id:10"));
  // Songs carry coverid under tag `c`, not `j` — the tag string must include c.
  assert.ok(lastReq.command.some(a => /^tags:/.test(String(a)) && String(a).includes("c")), "titles tags must request coverid tag c");
  assert.strictEqual(tracks[0].coverId, "abc123");
  ok("albumTracks parses + filters by album_id + requests coverid tag c");

  // players — boolean coercion of string/number connected/power
  const pl = await lms.players();
  assert.strictEqual(pl[0].connected, true);
  assert.strictEqual(pl[1].connected, false);
  assert.strictEqual(pl[1].power, false);
  assert.strictEqual(pl[1].isPlayer, true);
  ok("players coerces connected/power/isPlayer");

  // playerStatus — normalised now-playing
  const st = await lms.playerStatus("aa:bb");
  assert.strictEqual(st.mode, "play");
  assert.strictEqual(st.playing, true);
  assert.strictEqual(st.time, 42.5);
  assert.strictEqual(st.volume, 65);
  assert.strictEqual(st.muted, false);
  assert.strictEqual(st.index, 1);
  assert.strictEqual(st.total, 12);
  assert.strictEqual(st.track.title, "So What");
  assert.strictEqual(st.track.coverId, "abc123");
  assert.strictEqual(lastReq.playerId, "aa:bb");
  ok("playerStatus normalises mode/time/volume/track (current = playlist_cur_index)");

  // queue — full playlist with index, PLUS where the player currently is
  // (curIndex) so callers can drop already-played entries, and the quality
  // fields (type/bitrate/samplerate/samplesize) for the summary line.
  const q = await lms.queue("aa:bb");
  assert.strictEqual(q.curIndex, 1);
  assert.strictEqual(q.tracks.length, 3);
  assert.strictEqual(q.tracks[0].index, 0);
  assert.strictEqual(q.tracks[2].index, 2);
  assert.strictEqual(q.tracks[2].title, "Freddie Freeloader");
  assert.strictEqual(q.tracks[1].type, "flc");
  assert.strictEqual(q.tracks[1].bitrate, "845kbps");
  assert.strictEqual(q.tracks[1].samplerate, 44100);
  assert.strictEqual(q.tracks[1].samplesize, 16);
  ok("queue returns indexed playlist + curIndex + quality fields");

  // searchArtists — contributor lookup by name
  const arts = await lms.searchArtists("pink");
  assert.strictEqual(arts.length, 2);
  assert.strictEqual(arts[0].id, "77");
  assert.strictEqual(arts[0].name, "P!nk");
  assert.ok(lastReq.command.includes("search:pink"));
  ok("searchArtists returns contributor ids");

  // playAlbum modes → correct playlistcontrol cmd
  await lms.playAlbum("aa:bb", 10, "now");
  assert.deepStrictEqual(lastReq.command, ["playlistcontrol", "cmd:load", "album_id:10"]);
  await lms.playAlbum("aa:bb", 10, "next");
  assert.ok(lastReq.command.includes("cmd:insert"));
  await lms.playAlbum("aa:bb", 10, "queue");
  assert.ok(lastReq.command.includes("cmd:add"));
  ok("playAlbum maps now/next/queue to load/insert/add");

  // playTracks joins ids
  await lms.playTracks("aa:bb", [501, 502], "now");
  assert.deepStrictEqual(lastReq.command, ["playlistcontrol", "cmd:load", "track_id:501,502"]);
  ok("playTracks joins track ids");

  // transport actions
  await lms.transport("aa:bb", "play");  assert.deepStrictEqual(lastReq.command, ["play"]);
  await lms.transport("aa:bb", "pause"); assert.deepStrictEqual(lastReq.command, ["pause", "1"]);
  await lms.transport("aa:bb", "next");  assert.deepStrictEqual(lastReq.command, ["playlist", "index", "+1"]);
  await lms.transport("aa:bb", "prev");  assert.deepStrictEqual(lastReq.command, ["playlist", "index", "-1"]);
  await lms.transport("aa:bb", "stop");  assert.deepStrictEqual(lastReq.command, ["stop"]);
  ok("transport maps actions to commands");
  try { await lms.transport("aa:bb", "bogus"); assert.fail("should throw"); }
  catch (e) { assert.ok(/Unknown transport/.test(e.message)); }
  ok("transport rejects unknown action");

  // seek / volume / mute clamping + rounding
  await lms.seek("aa:bb", 42.9);   assert.deepStrictEqual(lastReq.command, ["time", "43"]);
  await lms.setVolume("aa:bb", 150); assert.deepStrictEqual(lastReq.command, ["mixer", "volume", "100"]);
  await lms.setVolume("aa:bb", -5);  assert.deepStrictEqual(lastReq.command, ["mixer", "volume", "0"]);
  await lms.setMute("aa:bb", true);  assert.deepStrictEqual(lastReq.command, ["mixer", "muting", "1"]);
  ok("seek/volume/mute clamp + round");

  // adjustVolume uses LMS's native signed relative form (+N / -N)
  await lms.adjustVolume("aa:bb", 5);   assert.deepStrictEqual(lastReq.command, ["mixer", "volume", "+5"]);
  await lms.adjustVolume("aa:bb", -5);  assert.deepStrictEqual(lastReq.command, ["mixer", "volume", "-5"]);
  ok("adjustVolume sends signed relative delta");

  // queue requests the coverid tag (c) too
  await lms.queue("aa:bb");
  assert.ok(lastReq.command.some(a => /^tags:/.test(String(a)) && String(a).includes("c")), "queue tags must include coverid tag c");
  ok("queue requests coverid tag c");

  // prefs
  assert.strictEqual(await lms.getPref("mediadirs"), "1");
  assert.strictEqual(await lms.getPlayerPref("aa:bb", "volume"), "50");
  await lms.setPref("foo", "bar"); assert.deepStrictEqual(lastReq.command, ["pref", "foo", "bar"]);
  ok("get/set pref + playerpref");

  // artworkUrl form
  const url = lms.artworkUrl("abc123", 400);
  assert.strictEqual(url, `http://127.0.0.1:${port}/music/abc123/cover_400x400_o.jpg`);
  ok("artworkUrl builds resized cover URL");

  // ping
  assert.strictEqual(await lms.ping(), true);
  ok("ping true when server answers");

  // normaliser unit: statusState handles a stopped player with empty playlist
  const stopped = _internal.statusState({ mode: "stop", playlist_loop: [] });
  assert.strictEqual(stopped.playing, false);
  assert.strictEqual(stopped.track, null);
  ok("statusState handles stopped/empty");

  server.close();
  console.log(`\n${n}/${n} LMS adapter tests passed.`);
})().catch(e => { console.error("[FAIL]", e); server.close(); process.exit(1); });
