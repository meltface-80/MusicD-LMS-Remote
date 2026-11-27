package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The Library wall's view (bug list item 52; the page's libView, public/app/walls.js): the sort and its
 * direction (and the seed that fixes a shuffle), the Focus (each facet's values, included or, with a "!" in
 * front, excluded; and the listening: any, never, 6 or 12 months) and the name filter. Written as the query
 * string the page's wall asks the server with ([query]), and read back from one ([parse]): that string is
 * what the page hands the app and what the app hands back, so the two never hold it differently.
 */
data class LibraryView(
    val sort: String = "album", val dir: String = "asc", val seed: Int = 1, val played: String = "any",
    val facets: Map<String, List<String>> = emptyMap(), val prefix: String = ""
) {
    /** One way the wall can be ordered: as the page's LIB_SORT_OPTIONS, [dir] the direction it opens with; [asc] and [desc] what the two arrows mean for it. */
    data class Sort(val id: String, val label: String, val dir: String, val asc: String, val desc: String, val note: String? = null) {
        /** Random has no direction (a re-tap reshuffles). */
        val hasDir: Boolean get() = id != "random"
    }

    /** The listening facet's choices. */
    data class Played(val id: String, val label: String)

    val sortOption: Sort get() = SORTS.firstOrNull { it.id == sort } ?: SORTS[0]
    /** What the arrow means now, in words ("A → Z", "Newest first"); "" for Random. */
    val dirLabel: String get() = sortOption.let { if (!it.hasDir) "" else if (dir == "desc") it.desc else it.asc }
    /** How many filters are on: every selected value, excluded ones too, and the listening when not Any. */
    val focusCount: Int get() = facets.values.sumOf { it.size } + (if (played != "any") 1 else 0)
    /** Whether anything narrows the wall beyond its order (the heading says "matching"). */
    val focused: Boolean get() = focusCount > 0 || prefix.isNotEmpty()

    /** A value's state in [facet]: "on" (included), "not" (excluded) or "off". */
    fun state(facet: String, value: String): String {
        val sel = facets[facet] ?: return "off"
        return if (value in sel) "on" else if ("!$value" in sel) "not" else "off"
    }

    /** The value tapped: off → on → not → off, the page's cycle. */
    fun cycle(facet: String, value: String): LibraryView {
        val sel = (facets[facet] ?: emptyList()).toMutableList()
        when (state(facet, value)) {
            "on" -> sel[sel.indexOf(value)] = "!$value"
            "not" -> sel.remove("!$value")
            else -> sel.add(value)
        }
        return copy(facets = facets + (facet to sel))
    }

    /** The sort [id] chosen: its own opening direction; Random freshly shuffled. A second tap on the one chosen reverses it (or reshuffles Random). */
    fun choose(id: String): LibraryView {
        val o = SORTS.firstOrNull { it.id == id } ?: return this
        if (id == sort) return if (o.hasDir) copy(dir = if (dir == "desc") "asc" else "desc") else copy(seed = nextSeed(seed))
        return copy(sort = id, dir = o.dir, seed = if (o.hasDir) seed else nextSeed(seed))
    }

    /** Every filter off, the order kept. */
    fun cleared(): LibraryView = copy(played = "any", facets = emptyMap())

    /** The query string the wall asks with: the page's libViewQuery, in its order. */
    fun query(): String {
        val p = ArrayList<String>()
        p += "sort=" + enc(sort); p += "dir=" + enc(dir)
        if (sort == "random") p += "seed=$seed"
        for (id in FACET_IDS) for (v in facets[id] ?: emptyList()) p += id + "=" + enc(v)
        if (played != "any") p += "played=" + enc(played)
        if (prefix.isNotEmpty()) p += "prefix=" + enc(prefix)
        return p.joinToString("&")
    }

    /**
     * The order alone, as Home's Library row asks with it (the page's libSortKey): the sort, its direction and a random
     * one's seed, none of the Focus nor the name filter, which narrow the wall and never the row (item 63).
     */
    fun sortQuery(): String = "sort=" + enc(sort) + "&dir=" + enc(dir) + (if (sort == "random") "&seed=$seed" else "")

    /** The view as a backup keeps it (the page's currentLibViewSnapshot): the sort, its direction and seed, the listening, every facet. */
    fun json(): JSONObject = JSONObject().put("sort", sort).put("dir", dir).put("seed", seed).put("played", played).also { o ->
        for (id in FACET_IDS) o.put(id, JSONArray().apply { for (x in facets[id] ?: emptyList()) put(x) })
    }

    companion object {
        /** The facets, in the sheet's order: MusicD LMS Remote's own (libFacetDefs in index.js). */
        val FACET_IDS = listOf("genre", "source", "decade", "label", "format", "rate", "bits", "letter", "added")
        val SORTS = listOf(
            Sort("album", "Album name", "asc", "A → Z", "Z → A"),
            Sort("artist", "Artist", "asc", "A → Z", "Z → A"),
            Sort("genre", "Genre", "asc", "A → Z", "Z → A"),
            Sort("year", "Release year", "desc", "Oldest first", "Newest first", "undated albums last"),
            Sort("added", "Date added", "desc", "Oldest first", "Newest first", "when LMS first added the album's tracks"),
            Sort("plays", "Most played", "desc", "Least played first", "Most played first", "from about 13 months of plays this server has seen"),
            Sort("lastplayed", "Last played", "desc", "Longest ago first", "Most recent first", "from about 13 months of plays this server has seen"),
            Sort("random", "Random", "asc", "", "")
        )
        val PLAYED = listOf(Played("any", "Any"), Played("never", "Never played"), Played("6", "Not in 6 months"), Played("12", "Not in 12 months"))

        /** A seed the server hasn't just served (1..100000: 0 would mean the default). */
        fun nextSeed(current: Int): Int {
            var next = current
            while (next == current) next = (1..100000).random()
            return next
        }

        /** The view a query string names (the page's, or the app's own); what it doesn't say is the default. */
        /**
         * [query] as it is kept for the next visit and the next start: without the name filter, which is a momentary
         * narrowing (the page's libView keeps no prefix either); restored, it looked like a library that had lost most of
         * its albums, with no field to clear it (item 338).
         */
        fun kept(query: String?): String = if (query.isNullOrEmpty()) "" else parse(query).copy(prefix = "").query()

        fun parse(query: String?): LibraryView {
            var v = LibraryView()
            val facets = HashMap<String, MutableList<String>>()
            for (part in (query ?: "").split('&')) {
                if (part.isEmpty()) continue
                val k = dec(part.substringBefore('='))
                val value = dec(part.substringAfter('=', ""))
                when (k) {
                    "sort" -> if (SORTS.any { it.id == value }) v = v.copy(sort = value)
                    "dir" -> if (value == "asc" || value == "desc") v = v.copy(dir = value)
                    "seed" -> value.toIntOrNull()?.let { v = v.copy(seed = it) }
                    "played" -> if (PLAYED.any { it.id == value }) v = v.copy(played = value)
                    "prefix" -> v = v.copy(prefix = value)
                    in FACET_IDS -> if (value.isNotEmpty()) facets.getOrPut(k) { ArrayList() }.add(value)
                }
            }
            return v.copy(facets = facets)
        }

        /**
         * A view kept as JSON ([json], the page's applyViewToLibView): every facet it doesn't name off, its values as words
         * (a decade the server keeps as a number, as the page writes it), the sort, its direction, seed and listening kept.
         */
        fun ofJson(j: JSONObject): LibraryView {
            var v = LibraryView()
            j.optString("sort").takeIf { s -> SORTS.any { it.id == s } }?.let { v = v.copy(sort = it) }
            j.optString("dir").takeIf { it == "asc" || it == "desc" }?.let { v = v.copy(dir = it) }
            if (j.has("seed")) v = v.copy(seed = j.optInt("seed", v.seed))
            j.optString("played").takeIf { p -> PLAYED.any { it.id == p } }?.let { v = v.copy(played = it) }
            val facets = LinkedHashMap<String, List<String>>()
            for (id in FACET_IDS) {
                val a = j.optJSONArray(id) ?: continue
                val sel = (0 until a.length()).map { word(a.get(it)) }
                if (sel.isNotEmpty()) facets[id] = sel
            }
            return v.copy(facets = facets)
        }

        private fun word(o: Any): String = if (o is Number && o.toDouble() == Math.floor(o.toDouble())) o.toLong().toString() else o.toString()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** What the Focus sheet offers (/api/library/facets): each facet's commonest values with their counts, and how much of the library each covers. */
data class Facets(val total: Int, val facets: List<Facet>, val coverage: Map<String, Int>, val hasPlays: Boolean, val sourcesDerived: Boolean) {
    data class Value(val value: String, val label: String, val count: Int)
    data class Facet(val id: String, val label: String, val totalValues: Int, val values: List<Value>)

    companion object {
        fun parse(j: JSONObject): Facets {
            val list = ArrayList<Facet>()
            val fa = j.optJSONArray("facets")
            if (fa != null) for (i in 0 until fa.length()) {
                val f = fa.optJSONObject(i) ?: continue
                val values = ArrayList<Value>()
                val va = f.optJSONArray("values")
                if (va != null) for (k in 0 until va.length()) {
                    val x = va.optJSONObject(k) ?: continue
                    values += Value(x.optString("value", ""), x.optString("label", x.optString("value", "")), x.optInt("count", 0))
                }
                list += Facet(f.optString("id", ""), f.optString("label", ""), f.optInt("total_values", values.size), values)
            }
            val cov = HashMap<String, Int>()
            j.optJSONObject("coverage")?.let { c -> for (k in c.keys()) cov[k] = c.optInt(k, 0) }
            return Facets(j.optInt("total", 0), list, cov, j.optBoolean("hasPlays", false), j.optBoolean("sources_derived", false))
        }
    }
}

/** An artist's biography (/api/artist-bio): the text, where it is from, and a portrait when there is one. */
data class ArtistBio(val text: String, val source: String?, val image: String?) {
    companion object {
        fun parse(j: JSONObject): ArtistBio? {
            val b = j.optJSONObject("bio") ?: return null
            val text = b.optString("text", "")
            if (text.isEmpty()) return null
            return ArtistBio(text, b.optString("source", "").ifEmpty { null }, if (b.isNull("image")) null else b.optString("image", "").ifEmpty { null })
        }
    }
}

/** Not played: the albums not played for [months], or, with no history that long yet, when there will be ([readyAt], ms). */
data class Unplayed(val albums: List<AlbumSummary>, val noHistory: Boolean, val readyAt: Long?) {
    companion object {
        fun parse(j: JSONObject) = Unplayed(AlbumRow.parse(j).albums, j.optBoolean("no_history", false), if (j.isNull("ready_at")) null else j.optLong("ready_at"))
    }
}
