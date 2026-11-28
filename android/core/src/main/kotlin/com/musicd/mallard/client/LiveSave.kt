package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Saving the Library's view as a Dynamic Playlist, and editing one's rules (the web app's saveLivePlaylistPrompt,
 * suggestLivePlaylistName and the Focus sheet's Order and Playlist size sections, public/app.js; the server's
 * lib/liveplaylists.js). The words are the page's.
 */

/** A Dynamic Playlist being edited: its rules shown in the Library and the Focus sheet, its own size and order beside them. */
data class LiveEdit(val id: String, val name: String, val limit: Int, val order: String, val view: LibraryView)

object LiveSaves {
    val LIMITS = listOf(25, 50, 100, 200, 400)
    const val LIMIT_DEFAULT = 100
    val ORDERS = listOf("album" to "Album order", "random" to "Random")
    const val ORDER_DEFAULT = "album"
    const val ORDER_NOTE = "Album order queues them in the sort you chose. Random shuffles which albums, and what order they play in. " +
        "The shuffle is fixed per playlist, so it stays put while you scroll rather than reshuffling under you."
    const val SIZE_NOTE = "How many albums this playlist actually plays. A rule can match your whole library, but every album is a " +
        "separate command to the server — 400 albums is thousands of tracks."

    /** A name from the rules on (only what is INCLUDED: "not Pop" reads badly in a title), else "My Dynamic Playlist". */
    fun suggest(v: LibraryView): String {
        val bits = ArrayList<String>()
        if (v.played == "never") bits += "Never played" else if (v.played != "any") bits += "Unplayed " + v.played + "m"
        fun inc(id: String) = (v.facets[id] ?: emptyList()).filter { !it.startsWith("!") }
        if (inc("decade").isNotEmpty()) bits += inc("decade").sorted().joinToString(" + ") { it + "s" }
        if (inc("genre").isNotEmpty()) bits += inc("genre").joinToString(" + ")
        if (inc("label").isNotEmpty()) bits += inc("label").joinToString(" + ")
        if (inc("source").isNotEmpty() && bits.isEmpty()) bits += inc("source").joinToString(" + ")
        return if (bits.isEmpty()) "My Dynamic Playlist" else bits.joinToString(" ")
    }

    /** What Save sends: a new playlist takes the server's size and order; an edited one keeps (or changes) its own. */
    fun body(name: String, view: LibraryView, edit: LiveEdit?): JSONObject = JSONObject().put("name", name).put("view", view.json()).apply {
        if (edit != null) { put("id", edit.id); put("limit", edit.limit); put("order", edit.order) }
    }

    /** The answer said: what it plays against what matched, as the page says it. */
    fun said(name: String, j: JSONObject): String {
        val pl = j.optJSONObject("playlist") ?: JSONObject()
        val n = pl.optInt("total", 0)
        val matched = if (pl.has("matched")) pl.optInt("matched", n) else n
        val f = java.text.NumberFormat.getIntegerInstance()
        return if (matched > n) "Saved “$name” — it plays $n of the ${f.format(matched)} albums that match. Change that with Edit."
        else "Saved “$name” — $n album" + (if (n == 1) "" else "s") + " right now"
    }
}

/** Saved (a new one, or [LiveSaves.body]'s id written over in place). The server's answer. */
fun ServerClient.saveLivePlaylist(body: JSONObject): JSONObject = post("/api/live-playlists", body, 30_000)

/** The playlist [id]'s rules, size and order, to edit (null when it has gone). */
fun ServerClient.liveEditOf(id: String): LiveEdit? {
    val a = getJson("/api/live-playlists", 30_000).optJSONArray("playlists") ?: return null
    for (i in 0 until a.length()) {
        val p = a.optJSONObject(i) ?: continue
        if (p.optString("id") != id) continue
        return LiveEdit(id, p.optString("name"), p.optInt("limit", LiveSaves.LIMIT_DEFAULT), p.optString("order", LiveSaves.ORDER_DEFAULT),
            p.optJSONObject("view")?.let(LibraryView::ofJson) ?: LibraryView())
    }
    return null
}
