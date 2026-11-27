package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/** A track to come in a room's queue; the first is the one playing. */
data class QueueItem(val id: Long, val title: String, val subtitle: String, val album: String, val imageKey: String?, val lengthSeconds: Int?)

/** A track the room has played, as the queue's "played earlier" lists it. */
data class Played(
    val track: String, val artist: String, val album: String, val imageKey: String?,
    val durationSeconds: Int, val elapsedSeconds: Int, val played: Boolean
) {
    /** Skipped part way: its length reads "0:12 / 3:35", so the skip is legible. */
    val skipped: Boolean get() = !played && elapsedSeconds > 0 && durationSeconds > 0

    /** Its length as the row shows it. */
    val lengthText: String get() = when {
        skipped -> NowPlayingView.clock(elapsedSeconds.toDouble()) + " / " + NowPlayingView.clock(durationSeconds.toDouble())
        durationSeconds > 0 -> NowPlayingView.clock(durationSeconds.toDouble())
        else -> ""
    }
}

/**
 * A room's queue as the Queue tab shows it (the page's public/app/queue.js):
 * what is to come, the one playing first, and what has played, newest first
 * as the server sends it. Its words are the page's, so the two say the same.
 */
data class QueueView(val items: List<QueueItem>, val history: List<Played>) {
    /** Nothing either side of "Now playing": the empty queue. */
    val empty: Boolean get() = items.isEmpty() && history.isEmpty()

    /** The bar over it: "2 tracks · 0:06 remaining", or that nothing more is queued. */
    val summary: String get() =
        if (items.isEmpty()) "Nothing more queued"
        else "${items.size} track${if (items.size == 1) "" else "s"} · ${NowPlayingView.clock(items.sumOf { it.lengthSeconds ?: 0 }.toDouble())} remaining"

    /** The played tracks in the order they played, the latest against "Now playing". */
    val playedInOrder: List<Played> get() = history.reversed()

    /** "1 played earlier", the fold-out's words. */
    val historyText: String get() = "${history.size} played earlier"

    /** As it will be once the [k]th track plays from here: those above it played, it "Now playing". */
    fun playedFrom(k: Int): QueueView {
        if (k <= 0 || k >= items.size) return this
        val played = items.take(k).reversed().map { Played(it.title, it.subtitle, it.album, it.imageKey, it.lengthSeconds ?: 0, it.lengthSeconds ?: 0, true) }
        return QueueView(items.drop(k), played + history)
    }

    companion object {
        /** /api/queue's answer. */
        fun parse(j: JSONObject): QueueView {
            val items = rows(j.optJSONArray("items")).map {
                QueueItem(it.optLong("queue_item_id"), it.optString("title", ""), it.optString("subtitle", ""), it.optString("album", ""),
                    it.str("image_key"), if (it.isNull("length")) null else it.optInt("length"))
            }
            val history = rows(j.optJSONArray("history")).map {
                Played(it.optString("track", ""), it.optString("artist", ""), it.optString("album", ""), it.str("image_key"),
                    it.optInt("duration", 0), it.optInt("elapsed", 0), it.optBoolean("played", true))
            }
            return QueueView(items, history)
        }

        private fun rows(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun JSONObject.str(k: String) = optString(k, "").takeIf { it.isNotEmpty() && !isNull(k) }

        /** The questions the Queue asks before it acts, in the page's words. */
        fun askPlayFrom(title: String) = "Play from \"${title.ifEmpty { "this track" }}\"?"
        fun askPlayNext(track: String) = "Play \"${track.ifEmpty { "this track" }}\" next?"
        fun askRemove(titles: List<String>) = "Remove ${what(titles)} from the queue?"
        const val ASK_CLEAR = "Clear the queue? Playback stops."
        fun askHistory(kind: String, tracks: List<String>) =
            "${if (kind == "queue") "Add" else "Play"} ${what(tracks)} ${if (kind == "queue") "to the end of the queue" else "next"}?"

        /** "Song 1" in quotes for one, "3 tracks" for more. */
        fun what(titles: List<String>) = if (titles.size == 1) "\"${titles[0].ifEmpty { "this track" }}\"" else "${titles.size} tracks"

        /** The most played tracks put back at once, as the page has it. */
        const val HISTORY_MAX = 20
    }
}
