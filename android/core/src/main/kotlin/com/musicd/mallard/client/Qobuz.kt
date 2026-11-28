package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/*
 * Qobuz through the server's LMS plugin (the web app's Browse Qobuz, public/app.js initQobuzBrowse and
 * fetchQobuzAlbumDetail; routes in index.js): its menus walked level by level, its albums as tiles, an album's tracks,
 * Play Now / Add to queue, and "＋ Add to Qobuz" / "✓ In Qobuz" (the account's favourites, never the app's heart). Every
 * album and track is an opaque token the server holds for 30 minutes; nothing here is a lasting handle.
 */

/** A streaming service the server's LMS has: [usable] when its plugin is there and signed in. */
data class Service(val tag: String, val name: String, val usable: Boolean, val notice: String?)

/** One row of a Qobuz menu: a [node] to open further (by [itemId]), or an album (by [token]). */
data class QobuzItem(val node: Boolean, val itemId: String, val token: String, val title: String, val subtitle: String, val imageKey: String?,
                     val canQueue: Boolean, val canFavorite: Boolean) {
    companion object {
        private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
        fun parse(j: JSONObject) = QobuzItem(j.optString("kind") == "node", j.optString("item_id"), j.optString("token"), j.optString("title"),
            j.optString("subtitle"), j.text("image_key"), j.optBoolean("can_queue", true), j.optBoolean("can_favorite", false))
    }
}

/** A level of the menus: its [title], [items] from [start] of [total], and the plugin's own words when it has nothing. */
data class QobuzLevel(val title: String, val total: Int, val start: Int, val items: List<QobuzItem>, val notice: String?) {
    companion object {
        fun parse(j: JSONObject): QobuzLevel {
            val a = j.optJSONArray("items") ?: JSONArray()
            return QobuzLevel(j.optString("title"), j.optInt("total", 0), j.optInt("start", 0),
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(QobuzItem::parse), if (j.isNull("notice")) null else j.optString("notice").ifEmpty { null })
        }
    }
}

data class QobuzTrack(val title: String, val artist: String, val duration: Double?, val token: String, val canQueue: Boolean)

/** An album's page: its tracks, whether it is in the account's favourites, the plugin's words instead of tracks. */
data class QobuzAlbum(val favorite: Boolean, val canFavorite: Boolean, val tracks: List<QobuzTrack>, val notice: String?) {
    companion object {
        fun parse(j: JSONObject): QobuzAlbum {
            val a = j.optJSONArray("tracks") ?: JSONArray()
            return QobuzAlbum(j.optBoolean("favorite"), j.optBoolean("can_favorite", true),
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { QobuzTrack(it.optString("title"), it.optString("artist"),
                    if (it.has("duration") && !it.isNull("duration")) it.optDouble("duration") else null, it.optString("token"), it.optBoolean("can_queue", true)) },
                if (j.isNull("notice")) null else j.optString("notice").ifEmpty { null })
        }
    }
}

object QobuzWords {
    fun favourite(on: Boolean) = if (on) "✓ In Qobuz" else "＋ Add to Qobuz"
    fun favourited(on: Boolean) = if (on) "Added to Qobuz favourites" else "Removed from Qobuz favourites"
    fun played(kind: String, zone: String) = (if (kind == "queue") "Queued → " else "Playing → ") + zone
    const val EXPIRED = "That's from a while ago — open it again"
}

fun ServerClient.services(): List<Service> {
    val a = getJson("/api/services", 30_000).optJSONArray("services") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Service(it.optString("tag"), it.optString("name"), it.optBoolean("usable"), it.optString("notice").ifEmpty { null }) }
}

/** A level of Qobuz's menus: the root (empty [itemId]) or a node's, from [start]. */
fun ServerClient.qobuzBrowse(itemId: String, start: Int = 0): QobuzLevel =
    QobuzLevel.parse(getJson("/api/qobuz/browse?item_id=" + URLEncoder.encode(itemId, "UTF-8") + "&start=$start", 60_000))

fun ServerClient.qobuzAlbum(token: String): QobuzAlbum = QobuzAlbum.parse(getJson("/api/qobuz/album?token=" + URLEncoder.encode(token, "UTF-8"), 60_000))

/** An album's or a track's [token] played in [zoneId]: [kind] play_now or queue. */
fun ServerClient.qobuzPlay(token: String, zoneId: String, kind: String) {
    post("/api/qobuz/play", JSONObject().put("token", token).put("zone_or_output_id", zoneId).put("kind", kind), 60_000)
}

/** The album in the account's favourites, or out of them: whether it now is. */
fun ServerClient.qobuzFavorite(token: String, on: Boolean): Boolean =
    post("/api/qobuz/favorite", JSONObject().put("token", token).put("favorite", on), 60_000).optBoolean("favorite", on)
