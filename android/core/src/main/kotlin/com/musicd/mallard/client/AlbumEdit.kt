package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLEncoder

/*
 * The album editor (the web app's initAlbumEdit, public/app.js; routes in index.js): the owner's title, artist, year and
 * artwork for an album, kept in the app's own database — the music files and LMS are never touched. Artwork comes from a
 * found candidate (Music & Artist Information, Cover Art Archive, iTunes) or a pasted address, which the server downloads.
 */

/** A cover found for the album: [url] (shown through the server's /api/albumart/thumb, as MAI's sit on the LMS host) and where from. */
data class ArtCandidate(val url: String, val source: String)

object AlbumEdits {
    /** The year as typed: a number, or null for none (which clears an edited year). */
    fun year(typed: String): Int? = typed.trim().toIntOrNull()?.takeIf { it in 0..3000 }

    /** What Save sends: the fields as they are (an empty one clears that edit), the artwork only when one was chosen. */
    fun body(offset: Long, title: String, artist: String, year: String, artUrl: String?): JSONObject = JSONObject()
        .put("offset", offset).put("title", title).put("artist", artist).put("year", year(year) ?: JSONObject.NULL)
        .apply { if (!artUrl.isNullOrBlank()) put("art_url", artUrl.trim()) }

    const val NOTE = "Edits are stored in the app's database — your music files and LMS are never modified."
    const val SEARCHING = "Searching cover sources…"
    const val NONE = "No artwork found — try adjusting title/artist, or paste a URL below."
    const val PICK = "Tap a cover to select it, then Save."
    const val SAVED = "Album saved to the app's database"
    const val REMOVED = "Edits removed — back to LMS values"
}

fun ServerClient.artCandidates(offset: Long, title: String, artist: String): List<ArtCandidate> {
    val e = { s: String -> URLEncoder.encode(s, "UTF-8") }
    val j = getJson("/api/albumart/candidates?offset=$offset&title=${e(title)}&artist=${e(artist)}", 60_000)
    val a = j.optJSONArray("candidates") ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c -> c.optString("url").takeIf { it.isNotEmpty() }?.let { ArtCandidate(it, c.optString("source")) } }
}

/** A candidate's preview, through the server (MAI's covers are on the LMS host, which the phone may not reach). */
fun ServerClient.artThumbPath(url: String): String = "/api/albumart/thumb?url=" + URLEncoder.encode(url, "UTF-8")

/** The album as an edit left it: its summary (title, artist line, year, cover) and whether it still carries edits. */
data class EditedAlbum(val album: AlbumSummary, val edited: Boolean) {
    companion object { fun parse(j: JSONObject) = j.getJSONObject("album").let { EditedAlbum(AlbumSummary.parse(it), it.optBoolean("edited", false)) } }
}

/** Saved: the album as it now is. */
fun ServerClient.saveAlbumEdit(body: JSONObject): EditedAlbum = EditedAlbum.parse(post("/api/album/edit", body, 120_000))

/** Every edit removed: the album as LMS has it. */
fun ServerClient.removeAlbumEdits(offset: Long): EditedAlbum = EditedAlbum.parse(delete("/api/album/edit?offset=$offset"))
