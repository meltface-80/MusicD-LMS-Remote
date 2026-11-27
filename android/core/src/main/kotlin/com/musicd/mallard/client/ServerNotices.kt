package com.musicd.mallard.client

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/*
 * The server's notice at the head of Home, worked out by the app from /api/status (bug list item 112, O2 in
 * old/plan-android-native.md), as the page's server-notice.js works it out: the data not kept, no music folder,
 * a first scan under way, no audio found, starting up, a music folder gone (with Forget, or Remove the missing ones, and
 * its question), no Sonos rooms. None while the app is answering for the server (offline), nor for a folder or the rooms
 * while away from home. Before, the page worked it out and told the app what to draw.
 */
object ServerNotices {
    /** The notice the server's [status] calls for, or none; numbers written for [locale]. */
    fun from(status: JSONObject, locale: Locale = Locale.getDefault()): ServerNotice? {
        if (status.optBoolean("offline")) return null
        val scan = status.optJSONObject("scan") ?: JSONObject()
        val last = scan.optJSONObject("last") ?: JSONObject()
        val albums = status.optInt("index_count")
        val sonos = status.optJSONObject("sonos")
        val rooms = sonos?.optInt("rooms") ?: 0
        val dirs = status.optJSONArray("music_dirs")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val musicDir = status.optString("music_dir").ifEmpty { "/music" }
        val dir = dirs.joinToString(", ").ifEmpty { musicDir }
        val away = status.optBoolean("away")
        fun n(v: Int) = NumberFormat.getIntegerInstance(locale).format(v)
        fun say(text: String, error: Boolean) = ServerNotice(listOf(text), error, emptyList())
        if (albums == 0) {
            val status0 = last.optString("status")
            return when {
                (status.has("music_dir_exists") && !status.optBoolean("music_dir_exists", true)) || status0 == "no-music" ->
                    say("No music folder at " + dir + ". Add your library in Settings → Music folders.", true)
                scan.optBoolean("running") -> say("Scanning your music folder… " + n(scan.optInt("files_seen")) +
                    " files so far. Albums appear when the first pass finishes.", false)
                status0.isNotEmpty() -> {
                    val unread = if (last.optInt("errors") > 0) " (" + last.optInt("errors") + " files couldn't be read)" else ""
                    // Said for how the server is installed (item 389), as the page says it.
                    when (status.optString("install")) {
                        "mandarin-os" -> say("No music yet. Copy it into Storage in smb://mandarin/data, or plug in a USB drive: it appears under Storage/USB, and its music here." + unread, last.optInt("errors") > 0)
                        else -> say("No audio files were found in " + dir + ". Check that it holds your music and that Mandarin can read it, or add your library in Settings → Music folders" + unread + ".", true)
                    }
                }
                else -> say("Starting up — reading your music folder…", false)
            }
        }
        val gone = last.optJSONArray("offline_dirs")
        if (gone != null && gone.length() > 0 && !away) return missing((0 until gone.length()).mapNotNull { gone.optJSONObject(it) }, ::n, status.optString("install"))
        if (rooms == 0 && sonos?.optBoolean("searching") != true && !away) {
            val error = sonos?.optString("error")?.takeIf { it.isNotEmpty() && !sonos.isNull("error") }
            val first = "No Sonos rooms found yet. Speakers on the same network as Mandarin appear by themselves; if yours don't, " +
                "set SONOS_HOSTS to a speaker's IP."
            return say(first + (error?.let { " ($it)" } ?: ""), true)
        }
        return null
    }

    /** How long until the server is asked again ([notice] the one shown; [scanning] a scan under way), in ms, as the page waits. */
    fun nextLook(notice: ServerNotice?, scanning: Boolean): Long = when {
        notice == null -> 60_000
        notice.dirs.isNotEmpty() -> 30_000
        scanning -> 3_000
        else -> 8_000
    }

    /** What is said once a folder has been forgotten ([removed] tracks, the folder called [name]). */
    fun forgotten(removed: Int, name: String, locale: Locale = Locale.getDefault()): String =
        "Removed " + NumberFormat.getIntegerInstance(locale).format(removed) + " tracks from “" + name + "”"

    /** The music folders gone, a paragraph each, with the button (and its question) that forgets one where there is one. */
    /** How a music folder is put back, for how the server is installed (item 389), as the page says it. */
    private fun putBack(install: String) = when (install) {
        "mandarin-os" -> "Plug the drive in again"
        else -> "Mount the drive or share again"
    }

    private fun missing(dirs: List<JSONObject>, n: (Int) -> String, install: String): ServerNotice {
        val lines = ArrayList<String>(); val buttons = ArrayList<String>(); val asks = ArrayList<String>(); val folders = ArrayList<String>(); val names = ArrayList<String>()
        for (d in dirs) {
            val name = "“" + d.optString("name") + "”"
            val tracks = n(d.optInt("tracks"))
            when (d.optString("reason")) {
                "unreadable" -> { lines += "Part of the music folder $name couldn't be read (a share that dropped, a disk that stalled?) — its $tracks tracks are kept as they were, and it's read again next scan."; continue }
                "vanished" -> {
                    lines += "Most of $name was gone at once — $tracks tracks. They're kept, in case it's a drive or share only half there. Deleted them yourself? Then remove them from the library:"
                    buttons += "Remove the missing ones"
                    asks += "Remove the tracks in $name whose files are gone? Only those leave the library; anything still there stays."
                }
                else -> {
                    lines += "The music folder $name" + (if (d.optBoolean("missing")) " isn't there" else " is empty") + " — its $tracks" +
                        " tracks are kept, not removed. " + putBack(install) + " and they're back as they were. Gone for good? Forget it:"
                    buttons += "Forget $name"
                    asks += "Forget $name? Its $tracks tracks and their albums leave the library (they come back with a rescan if the folder does)."
                }
            }
            folders += d.optString("dir"); names += d.optString("name")
        }
        return ServerNotice(lines, true, buttons, asks, folders, names)
    }
}
