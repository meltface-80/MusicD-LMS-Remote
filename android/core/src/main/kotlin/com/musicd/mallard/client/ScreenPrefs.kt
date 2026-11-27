package com.musicd.mallard.client

import org.json.JSONObject

/**
 * This phone's screen settings, kept by the app as one JSON object now there is no page to keep them (bug list item 111):
 * the UI Settings ([WallPrefs]'s names) and the Library's view ("view", its query string); and
 * the "page" part of a backup made from them under the page's own names (public/app/settings-backup.js, PAGE_KEYS), so a
 * backup made in the app restores in a browser and one made in a browser restores here. The page's keys the app has no
 * use for are carried through as they came.
 */
object ScreenPrefs {
    /** Each setting's name in the JSON, and the page's key for it. */
    val KEYS = linkedMapOf(
        "text" to "rra-ui-text", "title" to "rra-ui-title", "menu" to "rra-ui-menu", "layout" to "rra-ui-cols", "tile" to "rra-ui-tile",
        "greeting" to "rra-ui-greeting", "random" to "rra-ui-random"
    )
    const val VIEW = "view"
    const val ZONE = "rra-zone"
    const val VIEW_KEY = "rra-library-view"
    /** The page's keys the app doesn't use, kept in the JSON under their own names. */
    val PAGE_ONLY = listOf("rra-album-view", "rra-filter", "rra-np-reduced")
    /** The page's library view's own version (walls.js LIB_VIEW_VERSION). */
    private const val VIEW_VERSION = 2

    /** [json] with [name] set to [value], or taken out when [value] is null. */
    fun with(json: String?, name: String, value: String?): String {
        val j = read(json)
        if (value == null) j.remove(name) else j.put(name, value)
        return j.toString()
    }

    /** The backup's "page" part: what [json] holds and the room ([zone]), under the page's names. */
    fun backup(json: String?, zone: String?): JSONObject {
        val j = read(json)
        val out = JSONObject()
        for ((name, key) in KEYS) j.optString(name).takeIf { it.isNotEmpty() && !j.isNull(name) }?.let { out.put(key, it) }
        j.optString(VIEW).takeIf { it.isNotEmpty() }?.let { q ->
            out.put(VIEW_KEY, LibraryView.parse(q).json().put("v", VIEW_VERSION).put("prefix", "").toString())
        }
        if (!zone.isNullOrEmpty()) out.put(ZONE, zone)
        for (key in PAGE_ONLY) j.optString(key).takeIf { it.isNotEmpty() && !j.isNull(key) }?.let { out.put(key, it) }
        return out
    }

    /**
     * A backup's "page" part ([page]) put back over [json], as the page's applyPage puts it back: each setting it holds set,
     * each it doesn't taken out (anything else kept here, not in a backup, kept). The new JSON, and the room it names (null: none).
     */
    fun restore(page: JSONObject, json: String?): Pair<String, String?> {
        val j = read(json)
        for ((name, key) in KEYS) if (page.has(key)) j.put(name, page.optString(key)) else j.remove(name)
        val view = page.optString(VIEW_KEY).takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (view != null) j.put(VIEW, LibraryView.ofJson(view).query()) else j.remove(VIEW)
        for (key in PAGE_ONLY) if (page.has(key)) j.put(key, page.optString(key)) else j.remove(key)
        return j.toString() to page.optString(ZONE).takeIf { it.isNotEmpty() && !page.isNull(ZONE) }
    }

    private fun read(json: String?) = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: JSONObject()
}
