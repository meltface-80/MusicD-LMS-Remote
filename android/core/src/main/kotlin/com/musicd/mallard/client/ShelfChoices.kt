package com.musicd.mallard.client

import java.text.Collator
import java.text.NumberFormat
import java.util.Locale

/*
 * What is on the Shelf natively (bug list item 207), as the page's shelf-pick.js chooses it: genres OR among
 * themselves, artists' letters, decades, kinds of release and (item 208) formats likewise, and the
 * favourites alone, each kind of choice narrowing the
 * others; one order at a time, the artist order the default and no chip; the counts on the tiles what each would
 * bring; and the words the choices and the shelf say about it. Genres are held by name, so a library read
 * again keeps them wherever they come in its new lists.
 */

/** A tile of a tab: what it chooses ([id]), its words, how many albums it would bring (null: an order), whether chosen, and what it is called aloud. */
data class ShelfTile(val id: String, val name: String, val count: Int?, val chosen: Boolean, val said: String, val note: String = "", val fan: List<String> = emptyList()) {
    /** Nothing to bring: drawn faint. */
    val zero: Boolean get() = count == 0
}

/** A chip for a choice: its kind and id, its words, and "Artists" before a letter's. */
data class ShelfChip(val cat: String, val id: String, val text: String, val artists: Boolean)

/** The choices made on the Shelf, and what they leave of a library. */
class ShelfChoices(keptOrder: String? = null, var seed: Long = 1, keptGenreSort: String? = null) {
    private val sel: Map<String, LinkedHashSet<String>> = (NARROWING + "order").associateWith { LinkedHashSet() }
    private val chosen = ArrayList<Pair<String, String>>()
    /** The tab open on the choices. */
    var tab: String = "genre"
    /** The genres' order on their tab (item 213, as Rouen's v1.9.70-t5): "count" (#N, the default), "az" or "za"; kept per device. */
    var genreSort: String = keptGenreSort?.takeIf { k -> GENRE_SORTS.any { it.first == k } } ?: "count"; private set

    init {
        // The order kept on this device (the page's rra-shelf-order): not the artist order (the default), nor random (a shuffle is for now).
        if (keptOrder != null && keptOrder != "artist" && keptOrder != "random" && ShelfSort.ORDERS.any { it.first == keptOrder }) choose("order", keptOrder)
    }

    /** The order the shelf is filed in. */
    fun order(): String = sel.getValue("order").firstOrNull() ?: "artist"

    /** Whether [id] of [cat] is chosen. */
    fun isChosen(cat: String, id: String): Boolean = if (cat == "order") order() == id else sel[cat]?.contains(id) == true

    /** How many of [cat] are chosen (its tab's badge). */
    fun count(cat: String): Int = sel[cat]?.size ?: 0

    /** The choices in the order made (the chips; Undo takes back the last that narrowed). */
    fun made(): List<Pair<String, String>> = chosen.toList()

    /** Something narrows the shelf. */
    fun narrowed(): Boolean = NARROWING.any { sel.getValue(it).isNotEmpty() }

    private fun choose(cat: String, id: String) { sel.getValue(cat).add(id); chosen += cat to id }

    /**
     * [id] of [cat] chosen or taken back; an order chosen in place of the one before (the artist order no chip), a
     * random one shuffled afresh from [newSeed]. The order to keep on this device, when one is chosen that is kept.
     */
    fun toggle(cat: String, id: String, newSeed: Long = seed): String? {
        if (cat == "order") {
            if (order() == id) return null
            sel.getValue("order").clear(); chosen.removeAll { it.first == "order" }
            if (id != "artist") choose("order", id)
            if (id == "random") { seed = newSeed; return null }
            return id
        }
        val s = sel[cat] ?: return null
        if (id in s) { s.remove(id); chosen.remove(cat to id) } else choose(cat, id)
        return null
    }

    /** Every choice of [cat] taken back (its Clear all). */
    fun clearCat(cat: String) { sel[cat]?.clear(); chosen.removeAll { it.first == cat } }

    /** Every choice taken back (Reset shelf); the artist order kept on this device again when another was chosen. */
    fun clearAll(): String? {
        val had = sel.getValue("order").isNotEmpty()
        sel.values.forEach { it.clear() }; chosen.clear()
        return if (had) "artist" else null
    }

    /** The last choice that narrowed the shelf taken back (an order only refiles it): the note's Undo. */
    fun undo() { chosen.lastOrNull { it.first != "order" }?.let { toggle(it.first, it.second) } }

    /** Whether [a] passes every choice but [skip]'s (a tile's count is what its own would bring). */
    fun passes(lib: ShelfLibrary, a: ShelfCase, skip: String? = null): Boolean {
        val g = sel.getValue("genre"); val b = sel.getValue("artist"); val y = sel.getValue("year"); val t = sel.getValue("type")
        if (skip != "genre" && g.isNotEmpty() && a.genres.none { lib.genres.getOrNull(it)?.name in g }) return false
        if (skip != "artist" && b.isNotEmpty() && a.bucket !in b) return false
        if (skip != "year" && y.isNotEmpty() && decadeOf(a) !in y) return false
        if (skip != "type" && t.isNotEmpty() && typesOf(a).none { it in t }) return false
        // item 208: the Format tile it is under; the favourites alone
        if (skip != "format" && sel.getValue("format").let { it.isNotEmpty() && a.tile !in it }) return false
        if (skip != "favourite" && sel.getValue("favourite").isNotEmpty() && !a.favourite) return false
        return true
    }

    /** What the shelf holds: the library through the choices, in the order chosen. */
    fun list(lib: ShelfLibrary): List<ShelfCase> = ShelfSort.filed(lib, lib.albums.filter { passes(lib, it) }, order(), seed)

    /** The choices of a library read again that it no longer has let go: a genre, a decade or a kind gone has no tile to un-tick. */
    fun carry(lib: ShelfLibrary) {
        val keep = mapOf("genre" to lib.genres.map { it.name }.toSet(),
            "year" to lib.albums.map(::decadeOf).toSet(), "type" to lib.albums.flatMap(::typesOf).toSet(),
            "format" to lib.formats.toSet(), "favourite" to if (lib.albums.any { it.favourite }) setOf(FAVOURITE) else emptySet())
        for ((cat, have) in keep) { sel.getValue(cat).retainAll(have); chosen.removeAll { it.first == cat && it.second !in have } }
    }

    /** The tabs' cards along the shelf: where a new letter or decade begins in [list], "" elsewhere; none in a random or catalogue order. */
    fun tabsFor(lib: ShelfLibrary, list: List<ShelfCase>): List<String> {
        val by = order()
        if (by == "random" || by == "catno") return list.map { "" }
        val key: (ShelfCase) -> String = when (by) {
            "year" -> { a -> a.year?.let { decadeOf(a) + "s" }.orEmpty() }
            else -> { a -> a.bucket }
        }
        return list.mapIndexed { i, a -> if (i == 0 || key(list[i - 1]) != key(a)) key(a) else "" }
    }

    /** A choice's words: a genre's name, a kind's, an order's, "1990s" or "Undated", a letter. */
    fun labelOf(lib: ShelfLibrary, cat: String, id: String): String = when (cat) {
        "type" -> kindName(lib, id, true)
        "order" -> ShelfSort.name(id)
        "year" -> if (id == "none") "Undated" else id + "s"
        "favourite" -> "Favourites"
        else -> id
    }

    /** The tiles of [tab] for [lib]; empty, with [message] said, where it has nothing to choose. */
    fun tiles(lib: ShelfLibrary, tab: String = this.tab): List<ShelfTile> {
        val counts = counts(lib, tab)
        fun tile(id: String, name: String, said: String, fan: List<String> = emptyList()) = ShelfTile(id, name, counts[id] ?: 0, isChosen(tab, id), said, fan = fan)
        return when (tab) {
            "genre" -> genreOrder(lib).map { tile(it.name, it.name, it.name) }
            "artist" -> LETTERS.map { l -> tile(l, l, "Artists under " + if (l == "#") "#: names starting with 0, or with no letter A to Z" else l) }
            "year" -> decades(lib).let { ds -> if (ds.isEmpty() || ds[0] == "none") emptyList() else ds.map { d ->
                tile(d, if (d == "none") "Undated" else d + "s", if (d == "none") "Albums with no year" else "The ${d}s") } }
            "type" -> kindsHere(lib).map { tile(it, kindName(lib, it, true), kindName(lib, it, true)) }
            "format" -> lib.formats.map { tile(it, it, it) }
            "favourite" -> if (lib.albums.none { it.favourite }) emptyList() else listOf(tile(FAVOURITE, "Favourites", "Favourites", favouritesFan(lib)))
            else -> ShelfSort.ORDERS.map { (id, name, note) -> ShelfTile(id, name, null, order() == id, name, note) }
        }
    }

    /** The genres in the order chosen: as the server sends them (the most albums first), or by name either way, case and accents aside (the page's localeCompare, base). */
    private fun genreOrder(lib: ShelfLibrary): List<ShelfName> = when (genreSort) {
        "az" -> lib.genres.sortedWith { x, y -> BY_NAME.compare(x.name, y.name) }
        "za" -> lib.genres.sortedWith { x, y -> BY_NAME.compare(y.name, x.name) }
        else -> lib.genres
    }

    /** The genres put in order [id] (#N, A → Z or Z → A): the order to keep on this device, or null when it is no change. */
    fun sortGenres(id: String): String? {
        if (id == genreSort || GENRE_SORTS.none { it.first == id }) return null
        genreSort = id
        return id
    }

    /** What a tab with no tiles says instead. */
    fun message(tab: String = this.tab): String? = when (tab) {
        "genre" -> "None of your albums carries a genre tag, so there are no genres to choose from. Artists, Years and Order work."
        "year" -> "None of your albums carries a year, so there are no decades to choose from. Genres, Artists and Order work."
        "format" -> "Nothing is known yet of your albums’ files, so there are no formats to choose from."
        "favourite" -> "No favourites yet: tap the heart on an album’s page and it is here."
        else -> null
    }

    /** How many albums each tile of [tab] would bring: the library through every other choice. */
    fun counts(lib: ShelfLibrary, tab: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        fun add(k: String) { out[k] = (out[k] ?: 0) + 1 }
        for (a in lib.albums) {
            if (!passes(lib, a, tab)) continue
            when (tab) {
                "genre" -> a.genres.mapNotNull { lib.genres.getOrNull(it)?.name }.forEach(::add)
                "artist" -> add(a.bucket)
                "year" -> add(decadeOf(a))
                "type" -> typesOf(a).forEach(::add)
                "format" -> a.tile?.let(::add)
                "favourite" -> if (a.favourite) add(FAVOURITE)
            }
        }
        return out
    }

    /** The decades the library has, newest first, then "none" for the undated. */
    fun decades(lib: ShelfLibrary): List<String> {
        val ds = lib.albums.map(::decadeOf).toSet()
        return ds.filter { it != "none" }.sortedByDescending { it.toInt() } + if ("none" in ds) listOf("none") else emptyList()
    }

    /** The kinds the library has: the server's in its order, then any it doesn't name, then "none" (Unknown). */
    fun kindsHere(lib: ShelfLibrary): List<String> {
        val have = lib.albums.flatMap(::typesOf).toSet()
        val named = lib.kinds.map { it.id }.filter { it in have }
        return named + have.filter { it != "none" && it !in named }.sorted() + if ("none" in have) listOf("none") else emptyList()
    }

    /** A kind's name, for many (a tile) or one (a line); "none" is Unknown. */
    fun kindName(lib: ShelfLibrary, id: String, many: Boolean): String =
        if (id == "none") "Unknown" else lib.kinds.firstOrNull { it.id == id }?.let { if (many) it.many else it.one } ?: id

    /** The album view's line: its catalogue number and kinds, then its year. */
    fun lineOf(lib: ShelfLibrary, a: ShelfCase): String =
        (listOf(a.catno.orEmpty()) + a.kinds.map { kindName(lib, it, false) } + listOf(a.year?.toString().orEmpty())).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The summary under the title: before, the number in bold, after. */
    fun sum(lib: ShelfLibrary?, n: Int, waiting: String): Triple<String, String, String> = when {
        lib == null -> Triple(waiting, "", "")
        narrowed() -> Triple("", fmt(n), " of ${fmt(lib.albums.size)} albums on the shelf")
        else -> Triple("The whole library · ", fmt(lib.albums.size), " albums")
    }

    /** The phone's way back to the shelf: Show, the number in bold, albums; or Show the shelf before the library is read. */
    fun done(n: Int, ready: Boolean): Triple<String, String, String> = if (!ready) Triple("Show the shelf", "", "") else Triple("Show ", fmt(n), if (n == 1) " album" else " albums")

    /** The line under the tabs: what the tab does, or how many of it are chosen. */
    fun note(lib: ShelfLibrary?, tab: String = this.tab): String {
        val n = count(tab)
        val noun = mapOf("genre" to ("genre" to "genres"), "artist" to ("letter" to "letters"), "year" to ("decade" to "decades"),
            "type" to ("type" to "types"))[tab]
        return when {
            tab == "order" -> if (order() == "random") "On: the shelf is shuffled" else "How the shelf is filed. One order at a time."
            n > 0 && noun != null -> "$n ${if (n == 1) noun.first else noun.second} chosen"
            tab == "artist" -> "Tap letters. # holds names that start with 0, or have no letter A–Z."
            tab == "year" -> "Tap decades. Choose as many as you like."
            tab == "favourite" -> if (n > 0) "Only your favourites" else "Tap to show only the albums you have hearted."
            tab == "format" -> if (n > 0) "$n ${if (n == 1) "format" else "formats"} chosen" else "Tap formats. Choose as many as you like."
            tab == "type" -> if (lib != null && lib.albums.none { it.kinds.isNotEmpty() }) "The kinds come from the identification scan’s matches: none has said yet."
                else "Tap kinds of release. Choose as many as you like."
            else -> "Tap to choose. Choose as many as you like."
        }
    }

    /** Clear all is offered: two or more of the tab's chosen (not on Order). */
    fun clearShown(tab: String = this.tab): Boolean = tab != "order" && count(tab) >= 2

    /** Reset shelf is offered: two or more choices, of two or more kinds. */
    fun resetShown(): Boolean = chosen.size >= 2 && chosen.map { it.first }.toSet().size >= 2

    /** The chips, the first six and how many more when over seven. */
    fun chips(lib: ShelfLibrary): Pair<List<ShelfChip>, Int> {
        val shown = if (chosen.size > 7) chosen.take(6) else chosen
        return shown.map { (c, i) -> ShelfChip(c, i, labelOf(lib, c, i), c == "artist") } to (if (chosen.size > 7) chosen.size - 6 else 0)
    }

    /**
     * What the shelf says with nothing on it: its title and why (Undo and Reset with it when it is the choices that
     * leave nothing), or null while it holds albums.
     */
    fun empty(lib: ShelfLibrary, n: Int): Triple<String, String, Boolean>? {
        if (n > 0) return null
        if (lib.albums.isEmpty()) return Triple("The shelf is empty", "Mandarin hasn’t found any albums in your music folders yet.", false)
        val words = chosen.filter { it.first != "order" }.map { (c, i) ->
            when (c) { "artist" -> "artists under " + labelOf(lib, c, i); "year" -> if (i == "none") "no year" else "the " + labelOf(lib, c, i); else -> labelOf(lib, c, i) }
        }
        val joined = if (words.size > 1) words.dropLast(1).joinToString(", ") + " and " + words.last() else words.firstOrNull().orEmpty()
        return Triple("Nothing on this shelf", "No albums match $joined.", true)
    }

    /** Up to three of the favourites' covers for their tile, spread through them from a place the name gives (item 208). */
    fun favouritesFan(lib: ShelfLibrary): List<String> = spread("Favourites", lib.albums.filter { it.favourite }.mapNotNull { it.imageKey })

    private fun spread(name: String, keys: List<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val step = maxOf(1, keys.size / 3); val start = (ShelfSort.hash(name) % keys.size).toInt()
        return (0 until minOf(3, keys.size)).map { keys[(start + it * step) % keys.size] }
    }

    companion object {
        /** The genres' orders (item 213): each with its button's words and what it is called aloud. */
        val GENRE_SORTS = listOf(Triple("count", "#N", "Most albums first"), Triple("az", "A → Z", "A to Z"), Triple("za", "Z → A", "Z to A"))
        private val BY_NAME: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        /** The kinds of choice that narrow the shelf (the order only files it). */
        val NARROWING = listOf("genre", "artist", "year", "type", "format", "favourite")
        /** The tabs, in the page's order, with their names: four, then three (item 208; Labels gone in item 492). */
        val TABS = listOf("genre" to "Genres", "artist" to "Artists", "year" to "Years", "type" to "Types",
            "format" to "Formats", "favourite" to "Favourites", "order" to "Order")
        /** The Favourites tile's id. */
        const val FAVOURITE = "yes"
        /** The Artists tab's letters: A to Z, then #, then 1 to 9. */
        val LETTERS = ('A'..'Z').map { it.toString() } + "#" + (1..9).map { it.toString() }

        /** An album's decade as its Years tile names it ("1960"), or "none" for one no source dated. */
        fun decadeOf(a: ShelfCase): String = a.year?.takeIf { it > 0 }?.let { (it / 10 * 10).toString() } ?: "none"

        /** The kinds of release an album is; ["none"] for one no source says. */
        fun typesOf(a: ShelfCase): List<String> = a.kinds.ifEmpty { listOf("none") }

        /** A count as the page writes it (toLocaleString): 1,234. */
        fun fmt(n: Int): String = NumberFormat.getIntegerInstance(Locale.UK).format(n)
    }
}
