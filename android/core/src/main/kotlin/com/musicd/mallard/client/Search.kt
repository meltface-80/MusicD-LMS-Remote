package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * The search, typed (the Search group of old/plan-android-native.md,
 * bug list item 61): the library's answer (/api/search; Library.kt's
 * SearchResults) and what the services and Pitchfork answer beside it
 * (/api/search/external, each part asked on its own), as the page's search.js
 * and search-external.js read them.
 */

/** One of a service's albums the search found: its id there, the version after the title, and whether it is one of your favourites there. */
data class ServiceAlbum(val service: String, val id: String, val title: String, val version: String, val artist: String, val image: String?, val favourited: Boolean,
                        /** This server's cover for it (its image key), where it sends one instead of an address. */
                        val imageKey: String? = null)

/** One of a service's artists the search found, for the strip of chips. */
data class ServiceArtist(val service: String, val id: String, val name: String, val image: String?)

/** A Pitchfork review the words find: the album, its artist and cover, the score where it has one, Best New Music. */
data class PitchforkHit(val url: String, val album: String, val artist: String, val cover: String?, val score: Double?, val bestNew: Boolean, val date: String) {
    /** The score as the page writes it: one decimal ("8.4"). */
    val scoreText: String? get() = score?.let { String.format(java.util.Locale.US, "%.1f", it) }

    /** What Find on Qobuz searches for: the artist, then the album (pitchfork.js makeFindBtn). */
    val query: String get() = "$artist $album".trim()

    companion object {
        /** A Pitchfork listing (/api/pitchfork/reviews?type=latest|best, item 70): its reviews, newest first. */
        fun list(j: JSONObject): List<PitchforkHit> = ExternalSearch.reviews(j.optJSONArray("items"))
        /** The review as handed about ([json]); null when broken. */
        fun parse(json: String?): PitchforkHit? = runCatching { ExternalSearch.reviews(org.json.JSONArray().put(JSONObject(json ?: return null))).firstOrNull() }.getOrNull()?.takeIf { it.url.isNotEmpty() }
        /** The library's album a review is of (/api/pitchfork/review), by its offset: null when the library hasn't it. */
        fun match(j: JSONObject): Long? = j.optJSONObject("match")?.takeIf { it.has("offset") }?.optLong("offset")
    }

    /** The review as the page hands it to the magazine (__openPitchforkReview's item). */
    fun json(): String = JSONObject().put("url", url).put("album", album).put("artist", artist).put("cover", cover ?: JSONObject.NULL)
        .put("score", score ?: JSONObject.NULL).put("isBestNewMusic", bestNew).put("date", date).toString()
}

/**
 * What one part of the search beyond the library answered (/api/search/external?parts=): a service's albums and artists
 * (null where that service isn't signed in, or failed), Pitchfork's reviews.
 */
data class ExternalSearch(val query: String, val qobuz: List<ServiceAlbum>?, val qobuzArtists: List<ServiceArtist>, val tidal: List<ServiceAlbum>?,
                          val tidalArtists: List<ServiceArtist>, val pitchfork: List<PitchforkHit>) {
    companion object {
        /** The parts, in the order the page shows them: the services above the reviews. */
        val PARTS = listOf("qobuz", "tidal", "pitchfork")

        fun parse(j: JSONObject) = ExternalSearch(j.optString("query", ""), albums("qobuz", j.optJSONArray("qobuz")), artists("qobuz", j.optJSONArray("qobuz_artists")),
            albums("tidal", j.optJSONArray("tidal")), artists("tidal", j.optJSONArray("tidal_artists")), reviews(j.optJSONArray("pitchfork")))

        private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }

        private fun albums(service: String, a: JSONArray?): List<ServiceAlbum>? = a?.let { arr ->
            // MusicD LMS sends its search's albums as the browser's rows: an opaque token for the id, the artist line as subtitle, a cover's image key.
            objs(arr).map { ServiceAlbum(service, it.text("id") ?: it.optString("token"), it.optString("title"), it.optString("version"), it.text("artist") ?: it.optString("subtitle"),
                it.text("image"), it.optBoolean("favourited", false), it.text("image_key")) }
        }
        private fun artists(service: String, a: JSONArray?): List<ServiceArtist> =
            objs(a).map { ServiceArtist(service, it.optString("id"), it.optString("name"), it.text("image")) }
        internal fun reviews(a: JSONArray?): List<PitchforkHit> = objs(a).map {
            PitchforkHit(it.optString("url"), it.optString("album"), it.optString("artist"), it.text("cover"), if (it.isNull("score") || !it.has("score")) null else it.optDouble("score").takeIf { s -> !s.isNaN() },
                it.optBoolean("isBestNewMusic", false), it.optString("date"))
        }
    }

    /** How many things [part] shows: the page's count of a section's tiles, chips and rows (none: no section). */
    fun count(part: String): Int = when (part) {
        "qobuz" -> (qobuz?.size ?: 0) + (if (qobuz != null) qobuzArtists.size else 0)
        "tidal" -> (tidal?.size ?: 0) + (if (tidal != null) tidalArtists.size else 0)
        else -> pitchfork.size
    }
}
