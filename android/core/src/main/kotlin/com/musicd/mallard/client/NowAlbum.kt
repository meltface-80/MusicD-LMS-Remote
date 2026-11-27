package com.musicd.mallard.client

import org.json.JSONObject

/**
 * The album of what is playing, found as the page's Now playing finds it when its album's name is tapped (bug list item
 * 111; public/app/mini-transport.js): the album the playing track is from, by its own id (/api/album/now-playing), else
 * the library searched for the album's name, a result with the same name whose credits share a name with the playing
 * artist's ("Prince" is "Prince & The Revolution", not Bonnie "Prince" Billy).
 */
object NowAlbum {
    const val NOT_INDEXED = "Album not yet indexed — try again in a moment"
    private const val PHONE = "phone:"

    /** The album's offset in /api/album/now-playing's answer [j], or null. */
    fun playing(j: JSONObject?): Long? {
        val a = j?.optJSONObject("album") ?: return null
        return if (a.opt("offset") is Number) a.getLong("offset") else null
    }

    /** The offset of the album named [album] by [artist] among /api/search's answer [j] (its "results"), or null. */
    fun match(j: JSONObject?, album: String, artist: String): Long? {
        val rs = j?.optJSONArray("results") ?: return null
        val wanted = names(artist)
        for (i in 0 until rs.length()) {
            val a = rs.optJSONObject(i) ?: continue
            if (norm(a.optString("title")) != norm(album)) continue
            if (wanted.isNotEmpty() && names(a.optString("subtitle")).none { it in wanted }) continue
            if (a.opt("offset") is Number) return a.getLong("offset")
        }
        return null
    }

    /**
     * The phone's own album playing, its key, when the room's album is one ("phone:<key>", the phone playing its own
     * music, item 197): its page is the phone's, not the library's, which has never seen it. Null otherwise.
     */
    fun phoneKey(albumOffset: String?): String? = albumOffset?.takeIf { it.startsWith(PHONE) }?.removePrefix(PHONE)?.ifEmpty { null }

    /** The path the library is searched by for [album]. */
    fun searchPath(album: String): String = "/api/search?q=" + java.net.URLEncoder.encode(album, "UTF-8").replace("+", "%20") + "&limit=20"

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    private val CREDITS = Regex(" / |/| feat\\.? | featuring | ft\\.? |,| & | \\+ | and ", RegexOption.IGNORE_CASE)
    private fun names(s: String) = s.split(CREDITS).map(::norm).filter { it.isNotEmpty() }
}
