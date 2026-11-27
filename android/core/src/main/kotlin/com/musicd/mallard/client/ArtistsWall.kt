package com.musicd.mallard.client

import java.net.URLEncoder

/**
 * The Artists wall (bug list item 130): every act an album of the library's is credited to, as a grid of round
 * pictures with their names, read a page at a time from /api/artists. Its order (A to Z, most albums first, or at
 * random), found by part of a name, and each act's picture: their own once the server has found one (item 129),
 * else their first album's cover.
 */
object ArtistsWall {
    /** As many as a page of the wall reads. */
    const val PAGE = 120

    /** The orders, as /api/artists names them, in the order the Sort button goes round them. */
    val SORTS = listOf("az", "albums", "random")

    /** What the Sort button says of an order. */
    fun sortWord(sort: String): String = when (sort) { "albums" -> "Most albums"; "random" -> "Random"; else -> "A–Z" }

    /** The order after [sort], the Sort button tapped. */
    fun nextSort(sort: String): String = SORTS[(SORTS.indexOf(sort).coerceAtLeast(0) + 1) % SORTS.size]

    /** The address of a page: from [offset], in [sort] order ([seed] for a random one), found by [find] when it says anything. */
    fun path(sort: String, offset: Int, find: String = "", seed: Int = 1): String =
        "/api/artists?sort=" + (if (sort in SORTS) sort else "az") + "&offset=" + offset.coerceAtLeast(0) + "&limit=$PAGE" +
            (if (sort == "random") "&seed=$seed" else "") + (if (find.isBlank()) "" else "&q=" + URLEncoder.encode(find.trim(), "UTF-8").replace("+", "%20"))

    /** The picture a tile shows: their own, else their first album's cover. */
    fun art(a: Artist): String? = a.photoKey ?: a.imageKey

    /** Under a name: how many albums. */
    fun subtitle(a: Artist): String = (if (a.albumCount == 1) "1 album" else "${a.albumCount} albums") + (a.note?.let { " · $it" } ?: "")

    /** The heading: "Artists", and how many once the first page has said ("Artists · 312"). */
    fun heading(total: Int?): String = if (total == null) "Artists" else "Artists · $total"

    /** Said when a find matches no one. */
    fun none(find: String): String = "No artist matches “${find.trim()}”"

    /** Whether more can be read after [have] of [total]. */
    fun more(have: Int, total: Int): Boolean = have < total
}

/**
 * One act of a name others share (item 330: Airbag from Norway, Airbag from Argentina), as an artist's page is opened
 * with it: the name and the act's id together in the one string every "open this artist" passes, so an act opened from
 * the Artists wall or from its own album's page is that act alone; a plain name is every act of it, as ever.
 */
object ArtistAct {
    private const val SEP = '\u001F'

    /** [name], and act [id] where acts share it. */
    fun of(name: String, id: Long?): String = if (id == null) name else "$name$SEP$id"

    /** The name, and the act it names (null: every act of the name). */
    fun parse(s: String): Pair<String, Long?> {
        val at = s.lastIndexOf(SEP)
        if (at < 0) return s to null
        return s.substring(0, at) to s.substring(at + 1).toLongOrNull()
    }
}
