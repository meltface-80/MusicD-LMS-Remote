package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Playing from the Shelf natively (bug list item 207), as the page's shelf-player.js, shelf-back.js and shelf-queue.js
 * do it: the playing record found on the shelf, an album's tracks with each disc under its heading, the chosen tracks
 * sent one by one in the order that leaves them in album order, the zones with what each is doing, the queue's
 * summary, and what the shelf says when it has played something.
 */

/** A zone as the shelf's list offers it: its id, its name and whether it is playing or paused. */
data class ShelfZone(val id: String, val name: String, val state: String) {
    /** What the list says beside its name: Playing, Paused, or nothing. */
    val said: String get() = when (state) { "playing" -> "Playing"; "paused" -> "Paused"; else -> "" }

    companion object {
        /** /api/zones' answer. */
        fun list(j: JSONObject): List<ShelfZone> {
            val a = j.optJSONArray("zones") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("zone_id").isNotEmpty() }
                .map { ShelfZone(it.optString("zone_id"), it.optString("display_name"), it.optString("state", "stopped")) }
        }

        /** The zone to play in: [wanted] by its id or its name, else one playing, else the first. */
        fun pick(zones: List<ShelfZone>, wanted: String?): ShelfZone? =
            zones.firstOrNull { wanted != null && (it.id == wanted || it.name.equals(wanted, ignoreCase = true)) }
                ?: zones.firstOrNull { it.state == "playing" || it.state == "loading" } ?: zones.firstOrNull()
    }
}

/** A row of an album's track list: a disc's heading ([disc]), or a track — its place in the album ([index], what is sent to play it), its number on its disc and its title. */
data class ShelfTrackRow(val disc: String? = null, val index: Int = -1, val number: Int = 0, val title: String = "")

/** An album's tracks as /api/album sends them: each one's title, and its disc (0 where not said). */
data class ShelfTracks(val titles: List<String>, val discs: List<Int>) {
    /** Its rows, a set's under each disc's heading. */
    val rows: List<ShelfTrackRow> get() = ShelfPlaying.rows(titles, discs)

    companion object {
        fun parse(j: JSONObject): ShelfTracks {
            val a = j.optJSONArray("tracks")
            val t = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            return ShelfTracks(t.map { it.optString("title") }, t.map { if (it.isNull("disc")) 0 else it.optInt("disc", 0) })
        }
    }
}

/** The artist's other albums the library has (/api/album/more): whose they are, and each one's id, title and cover. */
data class ShelfMore(val artist: String, val albums: List<Triple<Long, String, String?>>) {
    companion object {
        /** The answer read, the first 24 of them; [fallback] the album's own artist where it names none. */
        fun parse(j: JSONObject, fallback: String): ShelfMore {
            val by = j.optJSONArray("by")
            val rows = if (by == null) emptyList() else (0 until by.length()).mapNotNull { by.optJSONObject(it) }.take(24)
            return ShelfMore(j.optString("artist", "").ifEmpty { fallback },
                rows.map { Triple(it.optLong("offset"), it.optString("title"), if (it.isNull("image_key")) null else it.optString("image_key", "").ifEmpty { null }) })
        }
    }
}

object ShelfPlaying {
    // Made once (item 211): a pattern built on each call, for each album of a library of thousands, held the screen.
    private val NOT_WORD = Regex("[^a-z0-9]")
    private val CREDIT = Regex(" / |/| feat\\.? | featuring | ft\\.? |,| & | \\+ | and ", RegexOption.IGNORE_CASE)

    /** A name as the remote compares names: its letters and numbers, lowercase. */
    fun norm(s: String): String = s.lowercase().replace(NOT_WORD, "")

    /** The names a credit is made of ("A & B feat. C"), each as [norm] makes it. */
    fun creditNames(s: String): List<String> = s.split(CREDIT).map(::norm).filter { it.isNotEmpty() }

    /**
     * Where the playing record is on [list]: by its album's id ([albumId]) where the server says it; else the album
     * named [album] whose credit shares a whole name with the track's [artist] (never "contains": Bonnie "Prince"
     * Billy is not Prince); else the one album of that title. -1 when it isn't on the shelf.
     */
    fun index(list: List<ShelfCase>, albumId: String?, album: String, artist: String): Int {
        albumId?.toLongOrNull()?.let { id -> list.indexOfFirst { it.offset == id }.takeIf { it >= 0 }?.let { return it } }
        val title = norm(album)
        if (title.isEmpty()) return -1
        val wanted = creditNames(artist)
        val i = list.indexOfFirst { norm(it.title) == title && wanted.any { w -> w in creditNames(it.artist) } }
        if (i >= 0) return i
        val only = list.withIndex().filter { norm(it.value.title) == title }
        return if (only.size == 1) only[0].index else -1
    }

    /** An album's rows: a set's tracks under each disc's heading, numbered from 1 on each ([discs] each track's disc, 0 where not said). */
    fun rows(titles: List<String>, discs: List<Int>): List<ShelfTrackRow> {
        val most = discs.maxOrNull() ?: 0
        val out = ArrayList<ShelfTrackRow>()
        var disc = 0; var n = 0
        titles.forEachIndexed { i, t ->
            if (most > 1) {
                val d = discs.getOrElse(i) { 0 }.takeIf { it >= 1 } ?: maxOf(disc, 1)
                if (d != disc) { out += ShelfTrackRow(disc = "Disc $d"); disc = d; n = 0 }
            }
            n++
            out += ShelfTrackRow(index = i, number = n, title = t)
        }
        return out
    }

    /** The chosen tracks in the order they are sent: Play next last to first, so they land in album order; else first to last. */
    fun sendOrder(picks: Set<Int>, kind: String): List<Int> = picks.sorted().let { if (kind == "play_next") it.reversed() else it }

    /** The kind each chosen track is sent as: Play now plays the first and queues the rest behind it. */
    fun kindFor(kind: String, sentBefore: Int): String = if (kind == "play_now" && sentBefore > 0) "queue" else kind

    /** What it says when [sent] of [of] chosen tracks have gone ([why]: what stopped the rest). */
    fun tracksSaid(kind: String, sent: Int, of: Int, zone: String, why: String): String {
        val where = if (zone.isNotEmpty()) " in $zone" else ""
        val what = if (sent == 1) "1 track" else "$sent tracks"
        var msg = when (kind) {
            "play_now" -> "Playing $what$where"
            "play_next" -> "$what play${if (sent == 1) "s" else ""} next$where"
            else -> "$what added to the queue$where"
        }
        if (sent < of) msg += " — ${of - sent} didn’t go. $why"
        return msg
    }

    /** A length as the queue writes it: 4:05, 1:02:03. */
    fun clock(secs: Int): String {
        val s = maxOf(0, secs); val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    /** The queue's summary: how many tracks and how long; "100+ tracks" where the server read only its first hundred. */
    fun queueSum(items: List<QueueItem>): String {
        if (items.isEmpty()) return ""
        if (items.size >= 100) return "100+ tracks"
        val total = items.sumOf { maxOf(0, it.lengthSeconds ?: 0) }
        return "${items.size} track${if (items.size == 1) "" else "s"}" + if (total > 0) " · " + clock(total) else ""
    }

    /** The spine's colour for an album whose cover isn't read yet: one of eight, the same each time for it. */
    fun fallbackColour(a: ShelfCase): Long = SPINE_FALLBACK[(ShelfSort.hash(a.title + "|" + a.artist) % SPINE_FALLBACK.size).toInt()]

    private val SPINE_FALLBACK = listOf(0xFF2F3A4AL, 0xFF4A2F35L, 0xFF2F4A3CL, 0xFF4A432FL, 0xFF3A2F4AL, 0xFF25343AL, 0xFF463026L, 0xFF33363BL)

    /** A spine's colours from a cover's average (r, g, b): a touch deeper, as a printed spine reads, and its writing dark or light. */
    fun spineColours(r: Int, g: Int, b: Int): Pair<Long, Long> {
        val k = 0.82
        val rr = Math.round(r * k).toInt(); val gg = Math.round(g * k).toInt(); val bb = Math.round(b * k).toInt()
        val lum = 0.2126 * r * k + 0.7152 * g * k + 0.0722 * b * k
        val bg = 0xFF000000L or (rr.toLong() shl 16) or (gg.toLong() shl 8) or bb.toLong()
        return bg to if (lum > 150) 0xFF17191CL else 0xFFF2EFE6L
    }

    /** The back of a case's barcode: its bars' widths as fractions of the whole, black first, from the album's own seed. */
    fun barcode(a: ShelfCase): List<Float> {
        val r = ShelfSort.rng(ShelfSort.hash(a.title + a.artist))
        val out = ArrayList<Float>(); var x = 0f
        while (x < 100f) { val w = (0.8 + r() * 2.6).toFloat(); out += w / 100f; x += w }
        return out
    }
}
