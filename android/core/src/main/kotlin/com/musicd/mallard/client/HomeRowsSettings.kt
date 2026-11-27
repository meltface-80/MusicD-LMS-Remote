package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/** One of Home's rows as Settings → Home Screen lists it: its [name], [why] it can't be switched (none when it can), and its switch [on]. */
data class ListedRow(val id: String, val name: String, val why: String?, val on: Boolean) {
    /** Whether its switch can be moved: not while a setting behind it is off, nor Downloaded albums while albums are on the phone. */
    val switchable: Boolean get() = why == null
}

/**
 * Settings → Home Screen (the page's settings-home.js, renderHomeRowsList and saveHomeRows; bug list item 81): Home's rows in
 * the owner's order, each on or off, dragged to a new place. The rows' names are the page's HOME_ROWS (home-layout.js).
 */
object HomeRowsSettings {
    /** Each row's name, the app's own two first, as the page's HOME_ROWS has them in the app. */
    val TITLES: Map<String, String> = linkedMapOf(
        "phone" to "Music on device", "downloads" to "Downloaded albums", "unplayed" to "Not played in 6 months", "later" to "Listen later",
        "favourites" to "Favourites", "history" to "Recently played", "picks" to "Smart Picks",
        "random" to "Random albums", "library" to "Library", "genres" to "Browse by genre")

    /** What Downloaded albums says while albums are on the phone: it stays on until they are removed. */
    const val HELD = "On while albums are downloaded to this phone"

    /**
     * The layout the server keeps, made whole for this phone (the page's loadHomeLayout): every row in the table's order, on,
     * where it keeps none; a row this phone has that it doesn't know (an older server) at the top, on.
     */
    fun complete(saved: List<HomeRow>, ids: Collection<String> = TITLES.keys): List<HomeRow> {
        val out = saved.toMutableList()
        if (out.isEmpty()) out += ids.map { HomeRow(it, true, null) }
        for (id in ids.reversed()) if (out.none { it.id == id }) out.add(0, HomeRow(id, true, null))
        return out
    }

    /** The rows as the list shows them: only those this phone has, Downloaded albums held on while [downloaded] albums are on the phone. */
    fun listed(rows: List<HomeRow>, downloaded: Int): List<ListedRow> = rows.mapNotNull { r ->
        val name = TITLES[r.id] ?: return@mapNotNull null
        val held = r.id == "downloads" && downloaded > 0
        val why = r.unavailable ?: if (held) HELD else null
        ListedRow(r.id, name, why, held || (r.unavailable == null && r.on))
    }

    /** The row [id] switched [on]; the rest as they were. */
    fun switched(rows: List<HomeRow>, id: String, on: Boolean): List<HomeRow> = rows.map { if (it.id == id) it.copy(on = on) else it }

    /**
     * The row [id] moved one place past its neighbour in the list ([by] -1 up, 1 down), as the page's drag swaps the row it
     * passes; a row the list doesn't show stays where it is. Unchanged at either end.
     */
    fun swapped(rows: List<HomeRow>, id: String, by: Int): List<HomeRow> {
        val at = rows.indexOfFirst { it.id == id }
        if (at < 0) return rows
        var other = at + by
        while (other in rows.indices && rows[other].id !in TITLES) other += by
        if (other !in rows.indices) return rows
        val out = rows.toMutableList()
        out[at] = rows[other]; out[other] = rows[at]
        return out
    }

    /** What is saved (POST /api/settings/home-rows): each row's id and whether it is on, in order. */
    fun body(rows: List<HomeRow>): JSONObject =
        JSONObject().put("rows", JSONArray().apply { for (r in rows) put(JSONObject().put("id", r.id).put("on", r.on)) })
}
