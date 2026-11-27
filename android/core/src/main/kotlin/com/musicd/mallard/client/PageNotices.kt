package com.musicd.mallard.client

import org.json.JSONObject

/*
 * The page's two notices at the head of its screens, as it tells the app of them (public/app/native-notices.js; the
 * Rooms group of old/plan-android-native.md, R3; bug list item 69): the server's notice (server-notice.js: an
 * empty library, no rooms, a music folder gone with Forget, the data not kept) and the update offer (updater.js: the
 * server's and the app's, Update or Later, the notes, its progress and a failure). The page goes on deciding what they
 * say and doing what their buttons do; the native Home draws them, which it hid behind itself until now.
 */

/**
 * The server's notice: its paragraphs, red-edged when it is a fault, and its buttons (Forget a folder), by their words;
 * [asks], each button's question, asked by the app before the page is told it was answered Yes (bug list item 96).
 */
data class ServerNotice(val lines: List<String>, val error: Boolean, val buttons: List<String>, val asks: List<String> = emptyList(),
                        /** The folder each button forgets, and its name (the app's own notices, item 112); the page's told none. */
                        val dirs: List<String> = emptyList(), val names: List<String> = emptyList()) {
    /** The question the button at [i] asks first; none when it asks none. */
    fun ask(i: Int): String? = asks.getOrNull(i)?.takeIf { it.isNotBlank() }
}

/** The update offer: what it says, its notes when open, Update's word (null while it is busy), whether Later is offered, and a failure. */
data class UpdateOffer(val text: String, val notes: String, val now: String?, val later: Boolean, val error: Boolean)

/** Both, either absent. */
data class PageNotices(val notice: ServerNotice? = null, val update: UpdateOffer? = null) {
    companion object {
        /** The page's JSON ({"notice": {lines, error, buttons} | null, "update": {text, notes, now, later, error} | null}); broken, none. */
        fun parse(json: String?): PageNotices {
            val j = runCatching { JSONObject(json ?: return PageNotices()) }.getOrNull() ?: return PageNotices()
            fun strings(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            // The questions by the buttons' places: kept as they are, a button without one an empty one.
            fun asks(o: JSONObject) = o.optJSONArray("asks")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val n = j.optJSONObject("notice")?.let { ServerNotice(strings(it, "lines"), it.optBoolean("error"), strings(it, "buttons"), asks(it)) }?.takeIf { it.lines.isNotEmpty() }
            val u = j.optJSONObject("update")?.let {
                UpdateOffer(it.optString("text"), it.optString("notes"), if (it.isNull("now")) null else it.optString("now").ifEmpty { null }, it.optBoolean("later"), it.optBoolean("error"))
            }?.takeIf { it.text.isNotBlank() }
            return PageNotices(n, u)
        }
    }
}
