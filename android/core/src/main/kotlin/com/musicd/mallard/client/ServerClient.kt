package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Mallard's calls to a MusicD LMS Remote server (index.js), typed where a screen needs them typed.
 *
 * The server has no sign-in: anyone who reaches it on the network may use it, so there is no token here. A "zone" is
 * an LMS player and its id is the player's (a MAC-like string); every transport and play call names it explicitly,
 * because the server keeps no notion of a phone's current room.
 *
 * Track identity is the album's offset and the track's place in /api/album's list — the server never hands out LMS
 * track ids. An offset is only good until the library is re-indexed: a 404 or 409 means "ask the list again".
 */
class ServerClient(
    val address: ServerAddress,
    private val timeoutMs: Int = 8000,
    private val transport: Transport = HttpTransport(address.baseUrl)
) {

    class ServerException(val status: Int, message: String) : IOException(message) {
        /** The server is up but can't reach its Lyrion Music Server (503 "Not connected"). */
        val waitingForLms get() = status == 503
        /** An offset or queue place that has moved on (the library was re-indexed, the queue changed). */
        val stale get() = status == 404 || status == 409
    }

    // ---- the server itself -------------------------------------------------------------------------------------

    fun health(): Health = Health.parse(getJson("/api/status"))

    /** The players, as the zone picker lists them, with what each is doing. */
    fun zones(): Zones = Zones.parse(getJson("/api/zones"))

    /** The rooms, as the Play on list has them. */
    fun rooms(): List<Room> = Room.list(getJson("/api/zones"))

    /** What [zoneId] is playing now. The server answers at once; the caller paces the next ask (2 s playing, 6 s not). */
    fun zoneState(zoneId: String): ZoneState = ZoneState.parse(getJson("/api/zone-state?zone=" + enc(zoneId)))

    // ---- transport ---------------------------------------------------------------------------------------------

    /** [command]: play, pause, playpause, stop, previous, next. */
    fun control(zoneId: String, command: String) {
        post("/api/control", JSONObject().put("zone_or_output_id", zoneId).put("command", command))
    }

    fun seek(zoneId: String, seconds: Double) {
        post("/api/seek", JSONObject().put("zone_or_output_id", zoneId).put("seconds", seconds.coerceAtLeast(0.0)))
    }

    /**
     * Shuffle and repeat, as concrete values worked out from the last poll — never a blind toggle, because another
     * phone or LMS's own page may have changed them since. [shuffle]: 0 off, 1 tracks, 2 albums. [loop]: disabled,
     * loop (the queue), loop_one (the track); null leaves one as it is.
     */
    fun zoneSettings(zoneId: String, shuffle: Boolean? = null, loop: String? = null, shuffleMode: Int? = null) {
        val b = JSONObject()
        if (shuffleMode != null) b.put("shuffle", shuffleMode) else if (shuffle != null) b.put("shuffle", if (shuffle) 1 else 0)
        if (loop != null) b.put("repeat", when (loop) { "loop" -> 2; "loop_one" -> 1; else -> 0 })
        if (b.length() == 0) return
        post("/api/lms/player/" + enc(zoneId) + "/mode", b)
    }

    /** The room's volume to [value] (0–100). */
    fun zoneVolume(zoneId: String, value: Double) {
        post("/api/volume", JSONObject().put("zone_or_output_id", zoneId).put("value", value))
    }

    /** A nudge up (1) or down (-1), applied by LMS itself so quick presses never race. */
    fun zoneVolumeStep(zoneId: String, direction: Int, step: Int = 2) {
        post("/api/volume", JSONObject().put("zone_or_output_id", zoneId).put("relative", if (direction > 0) step else -step))
    }

    fun mute(zoneId: String, on: Boolean) {
        post("/api/volume", JSONObject().put("zone_or_output_id", zoneId).put("mute", on))
    }

    fun volumeRelative(outputId: String, delta: Int) = zoneVolumeStep(outputId, delta, kotlin.math.abs(delta).coerceAtLeast(1))
    fun volumeAbsolute(outputId: String, value: Int) = zoneVolume(outputId, value.toDouble())

    /** The player switched on or off (LMS power). */
    fun power(zoneId: String, on: Boolean) { post("/api/lms/player/" + enc(zoneId) + "/power", JSONObject().put("on", on)) }

    /** Every room paused. */
    fun pauseAll() { post("/api/pause-all", JSONObject()) }

    /** Every room muted ([mute]) or unmuted. */
    fun muteAll(mute: Boolean) { post("/api/mute-all", JSONObject().put("how", if (mute) "mute" else "unmute")) }

    /** What's playing in [from] moved to [to]. */
    fun transferZone(from: String, to: String) { post("/api/transfer-zone", JSONObject().put("from_zone", from).put("to_zone", to)) }

    /** Whether [zoneId] is playing an album (the move-what's-playing question). */
    fun playingAlbum(zoneId: String): Boolean =
        getJson("/api/album/now-playing?zone=" + enc(zoneId)).optJSONObject("album")?.optString("title", "").orEmpty().isNotEmpty()

    /** Random album radio for a room: on, off. */
    fun radio(zoneId: String, on: Boolean) { post("/api/radio", JSONObject().put("zone", zoneId).put("enabled", on)) }

    // ---- queue -------------------------------------------------------------------------------------------------

    /** The room's queue: the track playing and what is still to come. */
    fun queue(zoneId: String): QueueView = QueueView.parse(getJson("/api/queue?zone=" + enc(zoneId)))

    /** The room plays on from the queue's track [queueItemId] (LMS's own playlist index). */
    fun playFromHere(zoneId: String, queueItemId: Long) {
        post("/api/play-from-here", JSONObject().put("zone_or_output_id", zoneId).put("queue_item_id", queueItemId))
    }

    /**
     * The picked tracks removed from the queue. LMS shifts every later index down on each removal, so they go
     * highest first: that way no index named here has moved by the time it is reached.
     */
    fun queueRemove(zoneId: String, queueItemIds: List<Long>) {
        for (id in queueItemIds.distinct().sortedDescending()) {
            post("/api/queue/remove", JSONObject().put("zone_or_output_id", zoneId).put("queue_item_id", id))
        }
    }

    // ---- playing albums and tracks -----------------------------------------------------------------------------

    /** An album to the room ([kind]: play_now, play_next, queue). A merged album plays every disc in order. */
    fun playAlbum(offset: Long, zoneId: String, kind: String): JSONObject =
        post("/api/play", JSONObject().put("offset", offset).put("zone_or_output_id", zoneId).put("kind", kind))

    /** One of an album's tracks (its place, from 0) to the room. play_now plays just that track. */
    fun playTrack(offset: Long, index: Int, zoneId: String, kind: String): JSONObject =
        post("/api/play-track", JSONObject().put("offset", offset).put("track", index).put("zone_or_output_id", zoneId).put("kind", kind))

    /** Several of an album's tracks, in the order given; places that have moved on are skipped, not fatal. */
    fun playTracks(offset: Long, indices: List<Int>, zoneId: String, kind: String): JSONObject =
        post("/api/play-tracks", JSONObject().put("offset", offset).put("tracks", JSONArray(indices)).put("zone_or_output_id", zoneId).put("kind", kind))

    /** Albums selected on a wall: the first honours [kind], the rest queue after it. The answer: queued, failed, total. */
    fun playMulti(albums: List<AlbumSummary>, zoneId: String, kind: String): JSONObject = playOffsets(albums.map { it.offset }, zoneId, kind)

    fun playOffsets(offsets: List<Long>, zoneId: String, kind: String): JSONObject =
        post("/api/play-multi", JSONObject().put("offsets", JSONArray(offsets)).put("zone_or_output_id", zoneId).put("kind", kind), 60_000)

    /** A random album not played in six months, played now in [zoneId]: its title and artist. */
    fun playUnheard(zoneId: String): JSONObject = post("/api/play-unheard", JSONObject().put("zone", zoneId), 20_000)

    /**
     * A random album in the room called [zoneName] (or the server's own choice of room), played now. The server's
     * shortcut names the room by its NAME, as a home-automation request would. Returns the album title.
     */
    fun playRandom(zoneName: String?): String {
        val path = "/api/shortcut/play-random" + (zoneName?.let { "?zone=" + enc(it) } ?: "")
        val j = getJson(path, 20_000)
        return j.optJSONObject("album")?.optString("title", "") ?: ""
    }

    // ---- favourites --------------------------------------------------------------------------------------------

    /**
     * The heart on an album set to [on]. The server keys favourites on title and artist (so they outlive a rescan),
     * and mirrors them into LMS's own Favorites best-effort. Its answer: favourite (the new state), lms_reason.
     */
    fun favourite(album: AlbumSummary, on: Boolean): JSONObject =
        post("/api/favourites/toggle", JSONObject().put("title", album.title).put("subtitle", album.subtitle)
            .put("source", album.source ?: JSONObject.NULL).put("image_key", album.imageKey ?: JSONObject.NULL)
            .put("qobuz_id", album.serviceId ?: JSONObject.NULL).put("offset", album.offset).put("favourite", on))

    /** Every favourite's key, to mark hearts on a wall without fetching the whole collection. */
    fun favouriteKeys(): Set<String> {
        val a = getJson("/api/favourites/keys").optJSONArray("keys") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    /** Albums favourited together (add only) or unfavourited together. */
    fun favouriteMany(albums: List<AlbumSummary>, on: Boolean): JSONObject {
        val items = JSONArray().also { a -> albums.forEach { al ->
            a.put(JSONObject().put("title", al.title).put("subtitle", al.subtitle).put("source", al.source ?: JSONObject.NULL)
                .put("image_key", al.imageKey ?: JSONObject.NULL).put("offset", al.offset))
        } }
        return post(if (on) "/api/favourites/add-multi" else "/api/favourites/remove-multi", JSONObject().put("items", items), 60_000)
    }

    // ---- merges ------------------------------------------------------------------------------------------------

    /** Albums merged into one (a multi-disc set LMS lists as several), the first picked first. Re-indexes: every offset moves. */
    fun mergeAlbums(albums: List<AlbumSummary>): JSONObject =
        post("/api/albums/merge", JSONObject().put("items", JSONArray().also { a -> albums.forEach { a.put(JSONObject().put("offset", it.offset).put("title", it.title).put("subtitle", it.subtitle)) } }), 60_000)

    // ---- Smart Picks -------------------------------------------------------------------------------------------

    /** Never this artist again in Smart Picks (the page's "Not for me"). */
    fun blockSmartPick(artist: String) { post("/api/smart-picks/block", JSONObject().put("artist", artist)) }

    /** A pick played straight from the streaming service ([kind] play_now or queue); found again by search each time. */
    fun playSmartPick(artist: String, album: String, zoneId: String, kind: String): JSONObject =
        post("/api/smart-picks/play", JSONObject().put("artist", artist).put("album", album).put("zone_or_output_id", zoneId).put("kind", kind), 30_000)

    /** A pick kept: favourited in the streaming account, so a later scan brings it in. */
    fun addSmartPick(artist: String, album: String): JSONObject =
        post("/api/smart-picks/add", JSONObject().put("artist", artist).put("album", album), 30_000)

    fun rebuildPicks(): JSONObject = post("/api/smart-picks/rebuild", JSONObject())

    // ---- settings ----------------------------------------------------------------------------------------------

    /** The server's version and whether a newer one is out. */
    fun updateStatus(): JSONObject = getJson("/api/update/status")

    /** One of the server's settings as it has it ([name]: "smart-picks", "display", "home-rows", "labels", "features"). */
    fun setting(name: String): JSONObject = getJson("/api/settings/$name")
    fun saveSetting(name: String, body: JSONObject): JSONObject = post("/api/settings/$name", body, 20_000)

    /** The server's link to LMS: host, port, connected, the LMS server's name and players. */
    fun lmsConnection(): JSONObject = getJson("/api/lms/connection")
    fun saveLmsConnection(host: String, port: Int): JSONObject =
        post("/api/lms/connection", JSONObject().put("host", host).put("port", port), 30_000)

    /** A player's own settings (name, power, modes, DSTM, sync group), as Settings → Players shows them. */
    fun playerSettings(zoneId: String): JSONObject = getJson("/api/lms/player/" + enc(zoneId) + "/settings")
    fun playerPref(zoneId: String, name: String, value: String): JSONObject =
        post("/api/lms/player/" + enc(zoneId) + "/pref/" + enc(name), JSONObject().put("value", value))
    fun playerDstm(zoneId: String, provider: String?): JSONObject =
        post("/api/lms/player/" + enc(zoneId) + "/dstm", JSONObject().put("provider", provider ?: JSONObject.NULL))
    fun playerSync(zoneId: String, with: String?): JSONObject =
        post("/api/lms/player/" + enc(zoneId) + "/sync", JSONObject().put("with", with ?: JSONObject.NULL))
    fun renamePlayer(zoneId: String, name: String): JSONObject =
        post("/api/lms/player/" + enc(zoneId) + "/name", JSONObject().put("name", name))

    /** LMS rescans its library; the app's index follows when the scan ends. */
    fun rescan(): JSONObject = post("/api/lms/rescan", JSONObject(), 20_000)

    /** The app's own index rebuilt from LMS now. */
    fun reindex(): JSONObject = post("/api/reindex", JSONObject(), 120_000)

    // ---- images and plumbing -----------------------------------------------------------------------------------

    /** A cover by its key, [size] pixels square (the server clamps to 64–1200). */
    fun imageUrl(imageKey: String, size: Int): String =
        address.baseUrl + "/api/image/" + enc(imageKey).replace("+", "%20") + "?size=" + size.coerceIn(64, 1200)

    /** A picture the server hands over as a path of its own (a Smart Pick's cover, an artist photo), or a whole address. */
    fun absoluteUrl(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl else address.baseUrl + (if (pathOrUrl.startsWith("/")) "" else "/") + pathOrUrl

    /** The bytes at [url], one of this server's (a cover from [imageUrl]). */
    fun bytes(url: String): ByteArray = transport.bytes(url.removePrefix(address.baseUrl), timeoutMs)

    fun getJson(path: String, timeout: Int = timeoutMs): JSONObject = transport.json("GET", path, null, timeout)

    fun post(path: String, body: JSONObject, timeout: Int = timeoutMs): JSONObject = transport.json("POST", path, body, timeout)

    fun delete(path: String): JSONObject = transport.json("DELETE", path, null, timeoutMs)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
