package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Away from home this phone is the one room (Mandarin's AwayRoom, its bug list item 487; owner report: "it still showed
 * the now-playing screen for my WiiM Pro Plus zone"). A screen is opened in a room and stays on show: one opened at home
 * on the WiiM went on asking for the WiiM from mobile data, so Now playing showed it and its buttons did nothing until
 * the phone was chosen in Play on. Away, what any screen asks of a room or sends to one is asked of, or sent to, this
 * phone's player instead — so every screen follows the phone at once, with nothing chosen by hand. HttpTransport applies
 * it while [ThisPhone.awayRoom] names the phone's player.
 */
object AwayRooms {
    /** The fields that name the room, in the address or the body. */
    val FIELDS = setOf("zone", "zone_or_output_id", "output_id", "zone_id")
    /** Kept for home, never rewritten: rooms moved between, and every room at once. */
    private val HOME_ONLY = setOf("/api/transfer-zone", "/api/mute-all", "/api/pause-all")
    private val PLAYER = Regex("^/api/lms/player/([^/?]+)(/.*)$")

    private fun home(path: String) = path.substringBefore('?') in HOME_ONLY

    /** [path] with a room named in it — `?zone=`, or `/api/lms/player/<id>/…` — made [own]. */
    fun path(path: String, own: String): String {
        if (home(path)) return path
        var p = path
        PLAYER.find(p.substringBefore('?'))?.let { m ->
            if (URLDecoder.decode(m.groupValues[1], "UTF-8") != own) p = "/api/lms/player/" + enc(own) + m.groupValues[2] + p.substring(p.substringBefore('?').length)
        }
        val at = p.indexOf('?')
        if (at < 0) return p
        val query = p.substring(at + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            if (name in FIELDS && value.isNotEmpty() && value != own) name + "=" + enc(own) else pair
        }
        return p.substring(0, at + 1) + query
    }

    /** [body] with a room named in it made [own], in a copy (the caller's own is left as sent). */
    fun body(path: String, body: JSONObject?, own: String): JSONObject? {
        if (body == null || home(path)) return body
        val names = FIELDS.filter { body.has(it) && !body.isNull(it) && body.optString(it).isNotEmpty() && body.optString(it) != own }
        if (names.isEmpty()) return body
        val copy = JSONObject(body.toString())
        for (n in names) copy.put(n, own)
        return copy
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
