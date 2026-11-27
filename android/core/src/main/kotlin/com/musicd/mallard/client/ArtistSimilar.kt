package com.musicd.mallard.client

import org.json.JSONObject

/**
 * An act's similar acts for its page (/api/artist/similar, bug list item 137) and an album's (item 494): Deezer's
 * ([source]), each with whether the library has them and a picture. Every one opens its page, as a band's members do.
 */
data class ArtistSimilar(val source: String?, val artists: List<Act>) {
    data class Act(val name: String, val inLibrary: Boolean, val photoKey: String?)

    companion object {
        fun parse(j: JSONObject): ArtistSimilar {
            val a = j.optJSONArray("artists")
            val acts = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Act(it.optString("name", ""), it.optBoolean("in_library", false), if (it.isNull("photo_key")) null else it.optString("photo_key", "").ifEmpty { null })
            }.filter { it.name.isNotEmpty() }
            return ArtistSimilar(if (j.isNull("source")) null else j.optString("source", "").ifEmpty { null }, acts)
        }
    }
}

/** A similar album: the library's own ([album]) when it has it, else its title, artist and cover ([imageKey]). */
data class SimilarAlbum(val title: String, val artist: String, val imageKey: String?, val album: AlbumSummary?)

/** An act's similar albums (/api/artist/similar-albums, item 140): the library's by the acts Deezer relates. */
data class SimilarAlbums(val enabled: Boolean, val error: String?, val albums: List<SimilarAlbum>) {
    companion object {
        fun parse(j: JSONObject): SimilarAlbums {
            val a = j.optJSONArray("albums")
            val albums = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                SimilarAlbum(it.optString("title", ""), it.optString("artist", ""), if (it.isNull("image_key")) null else it.optString("image_key", "").ifEmpty { null },
                    it.optJSONObject("album")?.let(AlbumSummary::parse))
            }
            return SimilarAlbums(j.optBoolean("enabled", false), if (j.isNull("error")) null else j.optString("error", "").ifEmpty { null }, albums)
        }
    }
}
