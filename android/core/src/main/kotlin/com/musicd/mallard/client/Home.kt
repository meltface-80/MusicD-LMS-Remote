package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * Home's rows as the server answers them (stage N5 of
 * old/plan-android-native.md, bug list item 57), typed for the native
 * Home: Smart Picks, the genre cards, the label of the week and Random
 * Album's choice. Each as the C# server answers it, held by
 * test/golden/android-api.json (AndroidApiTests, LibraryTest) like the rest
 * of Library.kt.
 */

private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
private fun <T> JSONArray?.each(parse: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }.map(parse)

/**
 * One of Smart Picks (/api/smart-picks): an album by [artist], chosen for a [reason]; [offset] where the library
 * has it (then it opens as any album, [libraryTitle] and [librarySubtitle] the library's own words for it), else
 * [albumId] on its [service] to add to your favourites there ([added] once that is done, until it is imported).
 */
data class Pick(
    val artist: String, val album: String, val image: String?, val reason: String?, val offset: Long?,
    val libraryTitle: String?, val librarySubtitle: String?, val imageKey: String?, val added: Boolean, val albumId: String?, val service: String?
) {
    /** The pick as the library's album, where the library has it: the words the page opens it with. */
    val inLibrary: AlbumSummary? get() = offset?.let { AlbumSummary(it, libraryTitle ?: album, librarySubtitle ?: artist, imageKey, null, null, null, null) }

    companion object {
        fun parse(j: JSONObject) = Pick(
            artist = j.optString("artist", ""), album = j.optString("album", ""), image = j.text("image"), reason = j.text("reason"),
            offset = if (!j.has("offset") || j.isNull("offset")) null else j.optLong("offset"),
            libraryTitle = j.text("library_title"), librarySubtitle = j.text("library_subtitle"), imageKey = j.text("image_key"),
            added = j.optBoolean("added", false), albumId = j.text("album_id"), service = j.text("service")
        )
    }
}

/** Today's Smart Picks: the [day] they are for, whether the feature is on, [building] while the first set is made. */
data class SmartPicks(val day: String, val enabled: Boolean, val serviceReady: Boolean, val building: Boolean, val picks: List<Pick>, val error: String? = null) {
    companion object {
        fun parse(j: JSONObject) = SmartPicks(j.optString("day", ""), j.optBoolean("enabled", true), j.optBoolean("service_ready", false), j.optBoolean("building", false),
            j.optJSONArray("picks").each(Pick::parse), j.text("error"))
    }
}

/** A genre as the filter sheet lists it (/api/filters/genres): its name and "n albums". */
data class Genre(val title: String, val subtitle: String, val count: Int) {
    companion object {
        fun list(j: JSONObject): List<Genre> = j.optJSONArray("genres").each { Genre(it.optString("title", ""), it.optString("subtitle", ""), it.optInt("count", 0)) }
    }
}

/** A sub-genre of a group (/api/home/genre-groups): its name, and how many albums, for the weighted pick. */
data class GenreSub(val title: String, val count: Int)

/** Home's genre groups: Pop and Rock/Metal under the [parent] genre, where the library has one ("Pop/Rock"); none from the C# server (it is flat). */
data class GenreGroups(val parent: String?, val pop: List<GenreSub>, val rockmetal: List<GenreSub>) {
    companion object {
        fun parse(j: JSONObject): GenreGroups {
            fun subs(k: String) = j.optJSONArray(k).each { GenreSub(it.optString("title", ""), it.optInt("count", 0)) }
            return GenreGroups(j.text("parent"), subs("pop"), subs("rockmetal"))
        }
    }
}

/**
 * The filter a wall of random albums is drawn with (the page's activeFilter): a [type] ("genre") and its [value];
 * a group's card ([label] "Rock/Metal") names its [parent] and the sub-genre it chose this time as the value.
 */
data class WallFilter(val type: String, val value: String, val parent: String? = null, val label: String? = null) {
    /** The wall's heading: the group's label, else the value. */
    val title: String get() = label ?: value
    /** The query string the page adds to /api/random-albums (filterQSOf), in its order. */
    fun query(): String = "&filter_type=" + enc(type) + "&filter_value=" + enc(value) + (parent?.let { "&filter_parent=" + enc(it) } ?: "")

    companion object {
        /** A filter as the app's route carries it (the query string back). */
        fun parse(query: String?): WallFilter? {
            if (query.isNullOrEmpty()) return null
            val m = HashMap<String, String>()
            for (part in query.trimStart('&').split('&')) if (part.isNotEmpty()) m[dec(part.substringBefore('='))] = dec(part.substringAfter('=', ""))
            val type = m["filter_type"] ?: return null; val value = m["filter_value"] ?: return null
            if (type.isEmpty() || value.isEmpty()) return null
            return WallFilter(type, value, m["filter_parent"]?.ifEmpty { null }, m["filter_label"]?.ifEmpty { null })
        }
        /** With the label too, for the route: the page keeps the label in memory, the app in the route. */
        fun routeQuery(f: WallFilter): String = f.query() + (f.label?.let { "&filter_label=" + enc(it) } ?: "")
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** One of Home's Browse by genre cards: a genre of its own, or a group ([group] its sub-genres under [parent]) that picks one of them each tap. */
data class GenreCard(val label: String, val genre: String?, val group: List<GenreSub>?, val parent: String?) {
    /** The filter a tap applies: the genre, or one of the group's sub-genres, the commoner the likelier (the page's pickWeightedSub). */
    fun filter(random: kotlin.random.Random = kotlin.random.Random.Default): WallFilter {
        if (group.isNullOrEmpty()) return WallFilter("genre", genre ?: label)
        var total = 0.0
        for (s in group) total += maxOf(1, s.count)
        var r = random.nextDouble() * total
        for (s in group) { r -= maxOf(1, s.count); if (r <= 0) return WallFilter("genre", s.title, parent, label) }
        return WallFilter("genre", group.last().title, parent, label)
    }

    companion object {
        private val POP_ROCK = Regex("pop\\s*/\\s*rock", RegexOption.IGNORE_CASE)
        const val MAX = 12

        /**
         * The cards as the page builds them (device-power.js, loadHomeGenres): Rock/Metal and Pop first where the library
         * has the parent, then the commonest genres (the parent itself left out), twelve at most and an even count.
         */
        fun build(genres: List<Genre>, groups: GenreGroups): List<GenreCard> {
            val cards = ArrayList<GenreCard>()
            val parent = groups.parent
            if (parent != null && (groups.rockmetal.isNotEmpty() || groups.pop.isNotEmpty())) {
                if (groups.rockmetal.isNotEmpty()) cards += GenreCard("Rock/Metal", null, groups.rockmetal, parent)
                if (groups.pop.isNotEmpty()) cards += GenreCard("Pop", null, groups.pop, parent)
            }
            for (g in genres.take(16)) {
                if (parent != null && POP_ROCK.containsMatchIn(g.title)) continue
                cards += GenreCard(g.title, g.title, null, null)
            }
            var n = minOf(cards.size, MAX)
            if (n % 2 == 1) n -= 1
            return cards.take(n)
        }
    }
}


/** Random Album's choice (/api/pick-unheard): one not heard in a year, or any until Mandarin has a year's listening. */
data class PickedAlbum(val album: AlbumSummary) {
    companion object {
        fun parse(j: JSONObject) = PickedAlbum(AlbumSummary.parse(j.getJSONObject("album")))
    }
}

/** A downloaded album as the server has it now (/api/download/albums): the download's [id] and the server's album, or none when it has gone from the library. */
data class DownloadedAlbum(val id: Int, val album: AlbumSummary?) {
    companion object {
        fun list(j: JSONObject): List<DownloadedAlbum> = j.optJSONArray("albums").each {
            DownloadedAlbum(it.optInt("id"), if (it.optBoolean("exists", false)) it.optJSONObject("album")?.let { a -> runCatching { AlbumSummary.parse(a) }.getOrNull() } else null)
        }
    }
}

/**
 * Music on device's view (the page's localView, home-layout.js; item 57): the sort and its direction (the seed fixing a
 * shuffle), and the Focus on the phone's own facets (the artist, the decade, the files' quality), each value included
 * or, with a "!" in front, excluded; kept on the phone as JSON. Worked out here, on the phone: the server never sees these albums.
 */
data class LocalView(val sort: String = "album", val dir: String = "asc", val seed: Int = 1, val facets: Map<String, List<String>> = emptyMap()) {
    val sortOption: LibraryView.Sort get() = SORTS.firstOrNull { it.id == sort } ?: SORTS[0]
    val dirLabel: String get() = sortOption.let { if (!it.hasDir) "" else if (dir == "desc") it.desc else it.asc }
    val focusCount: Int get() = facets.values.sumOf { it.size }

    fun state(facet: String, value: String): String {
        val sel = facets[facet] ?: return "off"
        return if (value in sel) "on" else if ("!$value" in sel) "not" else "off"
    }

    fun cycle(facet: String, value: String): LocalView {
        val sel = (facets[facet] ?: emptyList()).toMutableList()
        when (state(facet, value)) { "on" -> sel[sel.indexOf(value)] = "!$value"; "not" -> sel.remove("!$value"); else -> sel.add(value) }
        return copy(facets = facets + (facet to sel))
    }

    fun choose(id: String): LocalView {
        val o = SORTS.firstOrNull { it.id == id } ?: return this
        if (id == sort) return if (o.hasDir) copy(dir = if (dir == "desc") "asc" else "desc") else copy(seed = LibraryView.nextSeed(seed))
        return copy(sort = id, dir = o.dir, seed = if (o.hasDir) seed else LibraryView.nextSeed(seed))
    }

    fun cleared(): LocalView = copy(facets = emptyMap())

    /** Whether [a] passes the Focus: the page's localPasses. */
    fun passes(a: AlbumSummary): Boolean {
        for ((id, _) in FACETS) {
            val sel = facets[id] ?: continue
            if (sel.isEmpty()) continue
            val v = facetOf(id, a)
            if ("!$v" in sel) return false
            val on = sel.filter { !it.startsWith("!") }
            if (on.isNotEmpty() && v !in on) return false
        }
        return true
    }

    /** [list] in this order: the page's localSorted (its seeded shuffle for Random, so the wall stays put until ⟳). */
    fun sorted(list: List<AlbumSummary>): List<AlbumSummary> {
        if (sort == "random") {
            val out = list.toMutableList()
            var s = seed.toLong() and 0xFFFFFFFFL
            fun rnd(): Double { s = ((s * 1103515245L) + 12345L) and 0xFFFFFFFFL; return s / 4294967296.0 }
            for (i in out.size - 1 downTo 1) { val j = (rnd() * (i + 1)).toInt(); val t = out[i]; out[i] = out[j]; out[j] = t }
            return out
        }
        val cmp = Comparator<String> { x, y -> String.CASE_INSENSITIVE_ORDER.compare(x, y) }
        val sorted = when (sort) {
            "artist" -> list.sortedWith(compareBy<AlbumSummary, String>(cmp) { it.subtitle }.thenBy(cmp) { it.title })
            "year" -> list.sortedWith(compareBy<AlbumSummary> { it.year ?: 0 }.thenBy(cmp) { it.title })
            else -> list.sortedWith(compareBy<AlbumSummary, String>(cmp) { it.title })
        }
        return if (dir == "desc") sorted.reversed() else sorted
    }

    fun toJson(): String = JSONObject().put("sort", sort).put("dir", dir).put("seed", seed)
        .also { j -> for ((id, _) in FACETS) j.put(id, JSONArray(facets[id] ?: emptyList<String>())) }.toString()

    companion object {
        val SORTS = listOf(
            LibraryView.Sort("album", "Album name", "asc", "A → Z", "Z → A"),
            LibraryView.Sort("artist", "Artist", "asc", "A → Z", "Z → A"),
            LibraryView.Sort("year", "Release date", "desc", "Oldest first", "Newest first", "from the files' tags"),
            LibraryView.Sort("random", "Random", "asc", "", "")
        )
        /** The facets, in the sheet's order: each id and its label. */
        val FACETS = listOf("artist" to "Artist", "decade" to "Decade", "quality" to "Quality")

        /** An album's value in a facet, as the page words it ("Unknown artist", "1990s", "Undated", "Hi-Res", "Lossless", "Lossy"). */
        fun facetOf(id: String, a: AlbumSummary): String = when (id) {
            "artist" -> a.subtitle.ifEmpty { "Unknown artist" }
            "decade" -> a.year?.let { "${it / 10 * 10}s" } ?: "Undated"
            else -> if (a.hires) "Hi-Res" else if (a.quality?.contains("/") == true) "Lossless" else "Lossy"
        }

        /** The view as kept (JSON); nothing or nonsense: the defaults. */
        fun parse(json: String?): LocalView {
            val j = runCatching { JSONObject(json ?: return LocalView()) }.getOrNull() ?: return LocalView()
            val facets = HashMap<String, List<String>>()
            for ((id, _) in FACETS) j.optJSONArray(id)?.let { a -> (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { facets[id] = it } }
            return LocalView(
                sort = j.optString("sort", "album").takeIf { s -> SORTS.any { it.id == s } } ?: "album",
                dir = j.optString("dir", "asc").takeIf { it == "asc" || it == "desc" } ?: "asc",
                seed = j.optInt("seed", 1).takeIf { it > 0 } ?: 1, facets = facets
            )
        }
    }
}
