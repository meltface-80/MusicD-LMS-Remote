package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/*
 * The Shelf's library natively (bug list item 207): /api/shelf/albums as the page's shelf-pick.js reads it — the whole
 * library once, each album with the places of its genres, its letter, year, catalogue number, kinds,
 * format and version, and (item 208) the Format tile it is under and whether it is a favourite — and the orders the shelf is filed in (shelf-pick.js): by artist as the server
 * sends it (item 213: by album no longer), by catalogue number, by year, or shuffled (by label went with the labels, item 492).
 */

/** A genre as the shelf lists it: its name and how many albums carry it. */
data class ShelfName(val name: String, val count: Int)

/** A kind of release as the server names it: its id ("ep"), one ("EP") and many ("EPs"). */
data class ShelfKind(val id: String, val one: String, val many: String)

/**
 * An album on the Shelf (the answer's o, t, a, k, g, b, y, c, ty, fm, v, q, fv): its id, title and artist, its cover's
 * key, the places of its genres in [ShelfLibrary.genres], the letter its artist is filed under, its year, its catalogue number, its kinds, its format ("FLAC 16/44.1"), its title split from its
 * version ("Discovery", "Deluxe Edition"), the Format tile it is under ("24/96 Multichannel") and whether it is a favourite.
 */
data class ShelfCase(
    val offset: Long, val title: String, val artist: String, val imageKey: String?, val genres: List<Int>, val bucket: String,
    val year: Int?, val catno: String?, val kinds: List<String>, val format: String?, val version: Pair<String, String>?,
    val tile: String? = null, val favourite: Boolean = false,
) {
    /** The title as a spine and a card read it, its version apart. */
    val baseTitle: String get() = version?.first ?: title

    /** The version the title ends with, or "". */
    val versionText: String get() = version?.second.orEmpty()
}

/**
 * The shelf as the server sent it: genres commonest first, the kinds it names, the albums in artist order, the
 * signature to ask again with, and the Format tiles in their order (item 208).
 */
data class ShelfLibrary(
    val genres: List<ShelfName>, val kinds: List<ShelfKind>, val albums: List<ShelfCase>, val sig: String?,
    val formats: List<String> = emptyList(),
) {
    companion object {
        /** Where it is asked for: with the signature held, the answer is "the same" while nothing on the shelf changed. */
        fun path(sig: String?): String = "/api/shelf/albums" + if (sig.isNullOrEmpty()) "" else "?sig=" + java.net.URLEncoder.encode(sig, "UTF-8")

        /** The answer read; null when it says the shelf held is still the same. */
        fun parse(j: JSONObject): ShelfLibrary? {
            if (j.optBoolean("same", false)) return null
            return ShelfLibrary(names(j.optJSONArray("genres")),
                objects(j.optJSONArray("kinds")).map { ShelfKind(it.optString("id"), it.optString("one"), it.optString("many")) },
                objects(j.optJSONArray("albums")).map(::case), j.str("sig"),
                j.optJSONArray("formats")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty())
        }

        private fun case(o: JSONObject) = ShelfCase(
            offset = o.optLong("o"), title = o.optString("t"), artist = o.optString("a"), imageKey = o.str("k"),
            genres = ints(o.optJSONArray("g")), bucket = o.str("b") ?: "#", year = o.int("y"), catno = o.str("c"),
            kinds = o.optJSONArray("ty")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
            format = o.str("fm"),
            version = o.optJSONArray("v")?.takeIf { it.length() == 2 }?.let { it.optString(0) to it.optString(1) },
            tile = o.str("q"), favourite = o.optBoolean("fv", false)
        )

        private fun names(a: JSONArray?) = objects(a).map { ShelfName(it.optString("name"), it.optInt("count")) }
        private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }
        // A JSON null asked whether it is one first: Android's org.json answers optString of it with "null" (AlbumExtras).
        private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k, "").ifEmpty { null }
        private fun JSONObject.int(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)
    }
}

/** The orders the shelf is filed in, and the names they are compared by (shelf-pick.js). */
object ShelfSort {
    /** The orders, each with its name and its line on the Order tab, the artist order first (the default). */
    val ORDERS = listOf(
        Triple("artist", "A → Z by artist", "As a shop files them: by the artist's name, then the title."),
        Triple("catno", "By catalogue №", "The numbers in order, as a shop's stock list. Albums with no number come last, by artist."),
        Triple("year", "By year", "Oldest first. Albums with no year come last."),
        Triple("random", "Random order", "The shelf in a random order, as on the Random albums screen. Only the albums your other choices leave, if any."),
    )

    /** An order's name ("A → Z by artist"). */
    fun name(id: String): String = ORDERS.firstOrNull { it.first == id }?.second ?: id

    private val primary: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    /**
     * Two names in a shop's order, as the page's Intl.Collator("en", numeric, base) has them: numbers as numbers ("WARP 61"
     * before "WARP 123"), case and accents alike.
     */
    fun natural(a: String, b: String): Int = Natural(a).compareTo(Natural(b))

    /**
     * A name split once for [natural]'s order (item 211: split again at each comparison, a sort of thousands took
     * seconds on a phone): its runs, numbers as numbers and words as the collator's keys.
     */
    class Natural(s: String) : Comparable<Natural> {
        private val parts: List<Any> = runs(s).map { if (it[0] in '0'..'9') BigInteger(it) else primary.getCollationKey(it) }

        override fun compareTo(other: Natural): Int {
            for (i in 0 until minOf(parts.size, other.parts.size)) {
                val p = parts[i]; val q = other.parts[i]
                val c = when {
                    p is BigInteger && q is BigInteger -> p.compareTo(q)
                    p is BigInteger -> -1   // a number before a word, as the collator puts digits before letters
                    q is BigInteger -> 1
                    else -> (p as CollationKey).compareTo(q as CollationKey)
                }
                if (c != 0) return c
            }
            return parts.size.compareTo(other.parts.size)
        }
    }

    private fun runs(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val d = s[i] in '0'..'9'
            var j = i
            while (j < s.length && (s[j] in '0'..'9') == d) j++
            out += s.substring(i, j)
            i = j
        }
        return out
    }

    /** [list] filed in the order [by] ([seed] the shuffle's), from the artist order the server sends; a stable sort keeps it within a year. */
    fun filed(lib: ShelfLibrary, list: List<ShelfCase>, by: String, seed: Long): List<ShelfCase> = when (by) {
        "random" -> shuffled(list, seed)
        // each album's names split once, then sorted (item 211)
        "catno" -> list.map { Keyed(it, Natural(it.catno.orEmpty()), Natural(it.title)) }.sortedWith { x, y ->
            if ((x.a.catno == null) != (y.a.catno == null)) (if (x.a.catno == null) 1 else -1)
            else x.first.compareTo(y.first).takeIf { it != 0 } ?: x.title.compareTo(y.title)
        }.map { it.a }
        "year" -> list.sortedWith { x, y -> if ((x.year == null) != (y.year == null)) (if (x.year == null) 1 else -1) else (x.year ?: 0).compareTo(y.year ?: 0) }
        else -> list
    }

    /** An album with its names split for a sort: its catalogue number, its title. */
    private class Keyed(val a: ShelfCase, val first: Natural, val title: Natural)

    /** The page's shuffle (shelf.js rng, a xorshift from [seed]): the same seed, the same order. */
    fun shuffled(list: List<ShelfCase>, seed: Long): List<ShelfCase> {
        val out = list.toMutableList()
        val r = rng(seed)
        for (i in out.size - 1 downTo 1) { val j = (r() * (i + 1)).toInt(); val t = out[i]; out[i] = out[j]; out[j] = t }
        return out
    }

    /** shelf.js rng: xorshift32 from [seed], each draw in [0, 1). */
    fun rng(seed: Long): () -> Double {
        var s = (seed and 0xffffffffL).toInt().let { if (it == 0) 1 else it }
        return {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            (s.toLong() and 0xffffffffL) / 4294967296.0
        }
    }

    /** shelf.js hash: FNV-1a over [s]'s UTF-16 units, as an unsigned 32-bit number. */
    fun hash(s: String): Long {
        var h = 2166136261L
        for (ch in s) { h = h xor ch.code.toLong(); h = (h * 16777619L) and 0xffffffffL }
        return h
    }
}
