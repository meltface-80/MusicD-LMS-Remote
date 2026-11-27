package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * LMS plugins ("apps": Qobuz, Tidal, Spotty, BBC Sounds, Podcasts, TuneIn… every one) as MusicD LMS Remote serves them
 * (lib/apps.js): the list, and any of their menus as rows. Every action is an opaque token the server made from the
 * plugin's own menu; the app never holds an LMS command. Tokens expire (30 minutes): a 410 means "open it again".
 */

private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** A plugin on the list: its tag ("qobuz"), [kind] app or radio, its name and icon, and its root menu's token. */
data class AppEntry(val tag: String, val kind: String, val name: String, val imageKey: String?, val imagePath: String?, val go: String) {
    companion object {
        fun parse(j: JSONObject) = AppEntry(j.optString("tag"), j.optString("kind", "app"), j.optString("name"), j.str("image_key"), j.str("image_path"), j.optString("go"))
    }
}

/** The plugins LMS offers this player: its apps, and its radio. */
data class AppList(val apps: List<AppEntry>, val radio: List<AppEntry>) {
    companion object {
        fun parse(j: JSONObject) = AppList(
            j.optJSONArray("apps").objs().map(AppEntry::parse).filter { it.go.isNotEmpty() },
            j.optJSONArray("radio").objs().map(AppEntry::parse).filter { it.go.isNotEmpty() })
    }
}

/**
 * One row of a plugin's menu. [kind]: node (opens [go]), playable ([play], [add], [insert], [more]; [tapPlays] when a tap
 * plays it, else a tap opens [go]), search (asks for a term for [search]), choice (a radio button or checkbox, [selected],
 * set by [doToken]), link (a web page, [weblink]), text (nothing to do).
 */
data class AppRow(
    val kind: String, val title: String, val subtitle: String?, val imageKey: String?, val imagePath: String?,
    val go: String? = null, val play: String? = null, val add: String? = null, val insert: String? = null, val more: String? = null,
    val doToken: String? = null, val search: String? = null, val prompt: String? = null, val tapPlays: Boolean = false,
    val choice: String? = null, val selected: Boolean = false, val weblink: String? = null, val url: String? = null, val textkey: String? = null
) {
    val hasPicture: Boolean get() = imageKey != null || imagePath != null
    /** What a tap does: "play", "open", "search", "choose", "link", or nothing. */
    val tap: String? get() = when (kind) {
        "node" -> "open"
        "playable" -> if (tapPlays && play != null) "play" else if (go != null) "open" else if (play != null) "play" else null
        "search" -> "search"
        "choice" -> if (doToken != null) "choose" else null
        "link" -> "link"
        else -> null
    }

    companion object {
        fun parse(j: JSONObject) = AppRow(
            kind = j.optString("kind", "text"), title = j.optString("title"), subtitle = j.str("subtitle"),
            imageKey = j.str("image_key"), imagePath = j.str("image_path"),
            go = j.str("go"), play = j.str("play"), add = j.str("add"), insert = j.str("insert"), more = j.str("more"),
            doToken = j.str("do"), search = j.str("search"), prompt = j.str("prompt"), tapPlays = j.optBoolean("tap_plays", false),
            choice = j.str("choice"), selected = j.optBoolean("selected", false), weblink = j.str("weblink"), url = j.str("url"), textkey = j.str("textkey"))
    }
}

/** One of a plugin's menus: its rows from [offset] of [total]; [notice] a sign-in prompt or error; [layout] grid or list. */
data class AppMenu(val title: String, val total: Int, val offset: Int, val layout: String, val notice: String?, val rows: List<AppRow>) {
    val grid: Boolean get() = layout == "grid"

    companion object {
        fun parse(j: JSONObject) = AppMenu(j.optString("title"), j.optInt("total", 0), j.optInt("offset", 0), j.optString("layout", "list"),
            j.str("notice"), j.optJSONArray("rows").objs().map(AppRow::parse))
    }
}

/** What running an action came to: a menu to show (a context menu), or done, with where to go next and what to say. */
data class AppAct(val menu: AppMenu?, val nextWindow: String?, val message: String?) {
    companion object {
        fun parse(j: JSONObject) = AppAct(j.optJSONObject("menu")?.let(AppMenu::parse), j.str("next_window"), j.str("message"))
    }
}
