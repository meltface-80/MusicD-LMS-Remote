package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * The web app's side-menu collections, for Mallard's twins of them (owner rule: the two apps have the same features):
 * Merged albums (/api/albums/merges), Playlists (/api/playlists…), Dynamic Playlists (/api/live-playlists…) and Import a
 * playlist (/api/share/import, /api/share/save), plus the switches that decide which rows the menu shows
 * (/api/settings/features), read as the web page reads them.
 */

private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.texts(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { if (isNull(it)) null else optString(it).ifEmpty { null } }

/**
 * The server's opt-in switches (lib/features.js). A switch the server didn't name is [on] — the page hides a row only
 * when its switch is false, so a server that doesn't know the switch (or doesn't answer) never loses a row.
 */
data class Features(val off: Set<String>) {
    fun on(id: String): Boolean = id !in off

    companion object {
        fun parse(j: JSONObject?): Features {
            val f = j?.optJSONObject("features") ?: return Features(emptySet())
            return Features(f.keys().asSequence().filter { f.opt(it) == false }.toSet())
        }
    }
}

/** A merge: albums LMS lists apart shown as one ([present] of [partCount] still in the library; [offset] null when none). */
data class AlbumMerge(val id: String, val title: String, val artist: String, val parts: List<String>, val partCount: Int, val present: Int,
                      val offset: Long?, val imageKey: String?) {
    companion object {
        fun parse(j: JSONObject) = AlbumMerge(j.optString("id"), j.optString("title"), j.optString("artist"),
            j.optJSONArray("parts").objs().map { it.optString("title") }, j.optInt("part_count", 0), j.optInt("present", 0),
            if (j.isNull("offset") || !j.has("offset")) null else j.optLong("offset"), j.text("image_key"))
        fun list(j: JSONObject) = j.optJSONArray("merges").objs().map(::parse)
    }
}

/** One of LMS's stored playlists: its id, title, url and provenance, up to four covers, and how many tracks (null: unknown). */
data class Playlist(val id: String, val title: String, val url: String?, val extid: String?, val remote: Boolean, val art: List<String>, val tracks: Int?) {
    /**
     * Whether it is the owner's own playlist FILE, the only kind Delete is offered for (public/app.js isOwnPlaylist):
     * not a plugin's ([remote]), not imported from an online library ([extid]), and a url that is a playlist file. It
     * FAILS CLOSED: LMS deletes the .m3u with no way back, and on an imported playlist it can take a same-titled local
     * file with it.
     */
    val own: Boolean get() {
        if (remote || extid != null) return false
        val u = url ?: return false
        if (u.startsWith("file:", ignoreCase = true)) return true
        if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(u)) return false
        return Regex("\\.(m3u8?|pls|xspf)$", RegexOption.IGNORE_CASE).containsMatchIn(u)
    }

    companion object {
        fun parse(j: JSONObject) = Playlist(j.optString("id"), j.optString("title"), j.text("url"), j.text("extid"),
            j.optBoolean("remote") || j.optInt("remote", 0) == 1, j.optJSONArray("art").texts().take(4),
            if (j.isNull("tracks") || !j.has("tracks")) null else j.optInt("tracks"))
        fun list(j: JSONObject) = j.optJSONArray("playlists").objs().map(::parse).filter { it.id.isNotEmpty() }
    }
}

/** A playlist's track: its place, title, artist, album, length in seconds, cover. */
data class PlaylistTrack(val index: Int, val title: String, val artist: String, val album: String, val duration: Double?, val imageKey: String?) {
    /** The line under the title: artist · album, either alone when the other is missing. */
    val line: String get() = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun parse(j: JSONObject, at: Int) = PlaylistTrack(j.optInt("index", at), j.optString("title"), j.optString("subtitle"), j.optString("album"),
            if (j.isNull("duration") || !j.has("duration")) null else j.optDouble("duration"), j.text("image_key"))
        fun list(j: JSONObject) = j.optJSONArray("tracks").objs().mapIndexed { i, o -> parse(o, i) }
    }
}

/** A Dynamic Playlist: a saved library view. [total] is what it delivers (its limit applied), [matched] what the rule found. */
data class LivePlaylist(val id: String, val name: String, val total: Int, val matched: Int, val limit: Int, val order: String, val art: List<String>) {
    /** "100 of 1,179 albums" when capped, else "12 albums". */
    val count: String get() {
        val n = java.text.NumberFormat.getIntegerInstance()
        val word = if (total == 1) "album" else "albums"
        return if (matched > total) "${n.format(total)} of ${n.format(matched)} $word" else "${n.format(total)} $word"
    }

    companion object {
        fun parse(j: JSONObject) = LivePlaylist(j.optString("id"), j.optString("name"), j.optInt("total", 0), j.optInt("matched", 0),
            j.optInt("limit", 0), j.optString("order"), j.optJSONArray("art").texts().take(4))
        fun list(j: JSONObject) = j.optJSONArray("playlists").objs().map(::parse).filter { it.id.isNotEmpty() }
    }
}

/** A shared playlist read: [resolved] kept as the server sent them (Save sends them back), [missing] as words to show. */
data class ShareImport(val name: String, val total: Int, val truncated: Boolean, val resolved: JSONArray, val missing: List<String>) {
    val found: Int get() = resolved.length()

    companion object {
        fun parse(j: JSONObject) = ShareImport(j.optString("name", "Shared playlist"), j.optInt("total", 0), j.optBoolean("truncated"),
            j.optJSONArray("resolved") ?: JSONArray(),
            j.optJSONArray("missing").objs().map { m -> listOf(m.optString("title"), m.optString("artist")).filter { it.isNotEmpty() }.joinToString(" — ") })
    }
}

fun ServerClient.features(): Features = Features.parse(getJson("/api/settings/features"))

fun ServerClient.albumMerges(): List<AlbumMerge> = AlbumMerge.list(getJson("/api/albums/merges", 30_000))
/** A merge undone: its albums are listed apart again (the library re-indexes, so every offset moves). */
fun ServerClient.unmerge(id: String) { delete("/api/albums/merge/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")) }

fun ServerClient.playlists(): List<Playlist> = Playlist.list(getJson("/api/playlists", 30_000))
fun ServerClient.playlistTracks(id: String): List<PlaylistTrack> =
    PlaylistTrack.list(getJson("/api/playlist/tracks?playlist_id=" + java.net.URLEncoder.encode(id, "UTF-8"), 30_000))
/** A whole playlist in [zoneId]: [kind] play_now, play_next or queue. */
fun ServerClient.playPlaylist(id: String, zoneId: String, kind: String) {
    post("/api/playlist/play", JSONObject().put("playlist_id", id).put("zone_or_output_id", zoneId).put("kind", kind), 30_000)
}
/** The playlist played from its track [index] (the server checks the place against what LMS loads). */
fun ServerClient.playPlaylistFrom(id: String, zoneId: String, index: Int) {
    post("/api/playlist/play-track", JSONObject().put("playlist_id", id).put("zone_or_output_id", zoneId).put("index", index), 30_000)
}
/** The playlist's file deleted on the server — LMS keeps no copy. Only ever for a [Playlist.own] one, after asking. */
fun ServerClient.deletePlaylist(id: String) { post("/api/playlists/delete", JSONObject().put("playlist_id", id), 30_000) }

fun ServerClient.livePlaylists(): List<LivePlaylist> = LivePlaylist.list(getJson("/api/live-playlists", 30_000))
/** A Dynamic Playlist's albums from [offset], as the server pages them. */
fun ServerClient.livePlaylistPage(id: String, offset: Int, count: Int = 60): AlbumPage =
    AlbumPage.parse(getJson("/api/live-playlist?id=" + java.net.URLEncoder.encode(id, "UTF-8") + "&offset=$offset&count=$count", 30_000))
/** Every album of it (up to [max]) played in [zoneId]: the first per [kind], the rest after it. Whether it was cut short. */
fun ServerClient.playLivePlaylist(id: String, zoneId: String, kind: String, max: Int = 200): Boolean {
    val j = getJson("/api/live-playlist/albums?id=" + java.net.URLEncoder.encode(id, "UTF-8") + "&max=$max", 30_000)
    val a = j.optJSONArray("offsets") ?: JSONArray()
    val offsets = (0 until a.length()).map { a.optLong(it) }
    if (offsets.isNotEmpty()) playOffsets(offsets, zoneId, kind)
    return j.optBoolean("truncated")
}
fun ServerClient.deleteLivePlaylist(id: String) { post("/api/live-playlists/delete", JSONObject().put("id", id)) }

/** A shared playlist (its MDRP1 text, or a .musicd file's) read against this library: what matched, what didn't. */
fun ServerClient.readShare(blob: String): ShareImport = ShareImport.parse(post("/api/share/import", JSONObject().put("blob", blob), 60_000))
/** What matched, saved as a stored playlist called [name]: the answer's added, created and skipped. */
fun ServerClient.saveShare(name: String, items: JSONArray): JSONObject =
    post("/api/share/save", JSONObject().put("name", name).put("items", items), 120_000)

/** The routes of the side menu's collections (Shell.kt's [ShellRoute] holds the rest). */
object CollectionRoute {
    const val ID = "id"
    const val NAME = "name"
    const val OWN = "own"
    const val ZONE = ShellRoute.ZONE
    const val MERGES = "merges?$ZONE={$ZONE}"
    const val PLAYLISTS = "playlists?$ZONE={$ZONE}"
    const val PLAYLIST = "playlist?$ID={$ID}&$NAME={$NAME}&$OWN={$OWN}&$ZONE={$ZONE}"
    const val LIVE_PLAYLISTS = "liveplaylists?$ZONE={$ZONE}"
    const val LIVE_PLAYLIST = "liveplaylist?$ID={$ID}&$NAME={$NAME}&$ZONE={$ZONE}"
    const val IMPORT = "import?$ZONE={$ZONE}"
    fun merges(zone: String) = "merges?$ZONE=" + enc(zone)
    fun playlists(zone: String) = "playlists?$ZONE=" + enc(zone)
    fun playlist(p: Playlist, zone: String) = "playlist?$ID=" + enc(p.id) + "&$NAME=" + enc(p.title) + "&$OWN=" + p.own + "&$ZONE=" + enc(zone)
    fun livePlaylists(zone: String) = "liveplaylists?$ZONE=" + enc(zone)
    fun livePlaylist(p: LivePlaylist, zone: String) = "liveplaylist?$ID=" + enc(p.id) + "&$NAME=" + enc(p.name) + "&$ZONE=" + enc(zone)
    fun import(zone: String) = "import?$ZONE=" + enc(zone)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
