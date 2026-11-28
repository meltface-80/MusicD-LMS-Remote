package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * Add to playlist (the web app's __addToPlaylistSheet, public/app.js): what is sent to /api/playlists/add and what is
 * said of its answer, word for word as the page says it, so the two apps tell the same story.
 */
object PlaylistAdds {
    /** Every track of the [offsets] albums (the albums' multi-select). */
    fun albums(offsets: List<Long>): JSONObject = JSONObject().put("offsets", JSONArray(offsets))

    /** The [places] of one album's tracks (the tracks' multi-select): their places as /api/album numbers them. */
    fun tracks(offset: Long, places: List<Int>): JSONObject = JSONObject().put("offset", offset).put("tracks", JSONArray(places))

    /** The sheet's line over its rows. */
    fun describeAlbums(n: Int) = "Every track from $n selected album" + (if (n == 1) "" else "s") + "."
    fun describeTracks(n: Int) = "$n selected track" + (if (n == 1) "" else "s") + "."

    /**
     * The answer said: "Added 12 tracks to new playlist “Road”" — or "existing “Road”" when the name was already taken
     * (LMS appends to it rather than making a second one), or "playlist" when one was picked — and how many were skipped.
     */
    fun said(j: JSONObject, name: String?): String {
        val added = j.optInt("added", 0)
        val where = if (name != null) (if (j.optBoolean("created")) "new playlist “$name”" else "existing “$name”") else "playlist"
        val skipped = j.optInt("skipped", 0).let { if (it > 0) " ($it skipped)" else "" }
        return "Added $added track" + (if (added == 1) "" else "s") + " to " + where + skipped
    }
}

/** [body] (PlaylistAdds.albums / tracks) added to the playlist [playlistId], or to a new one called [name]. Its answer. */
fun ServerClient.addToPlaylist(body: JSONObject, playlistId: String?, name: String?): JSONObject {
    val b = JSONObject(body.toString())
    if (playlistId != null) b.put("playlist_id", playlistId) else b.put("name", name)
    return post("/api/playlists/add", b, 180_000)
}
