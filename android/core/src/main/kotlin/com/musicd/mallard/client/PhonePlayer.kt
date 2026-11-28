package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLEncoder

/*
 * Away from home the phone is an LMS player of its own (the server's lib/phoneplayers.js; Mandarin's phone zone): the
 * phone says hello, the server runs its player (squeezelite) and makes what LMS plays to it a stream for the phone —
 * Opus 256 kbps or lossless FLAC — and the app shows and plays in that player alone ("This phone").
 */
data class PhoneHello(val playerId: String, val name: String, val away: Boolean) {
    companion object { fun parse(j: JSONObject) = PhoneHello(j.optString("player_id"), j.optString("name"), j.optBoolean("away")) }
}

object PhoneStream {
    /** How the stream is made away (Settings → Away from home → This phone), its value and its words. */
    val QUALITIES = listOf("opus" to "Opus 256 kbps", "flac" to "Lossless (FLAC 24-bit/48 kHz)")
    const val DEFAULT = "opus"
    const val NOTE = "Lyrion plays to this phone's own player losslessly, so its volume levelling and player settings apply; the server " +
        "sends it on as Opus 256 kbps (about a quarter of a CD's data) or lossless FLAC."
    fun quality(v: String?): String = if (v == "flac") "flac" else DEFAULT

    /** The stream's path on the server, for [playerId] made as [q]. */
    fun path(playerId: String, q: String): String = "/api/phone/stream?player_id=" + URLEncoder.encode(playerId, "UTF-8") + "&q=" + quality(q)
}

/** Hello: this phone's player, started if it isn't. [deviceId] is the app's own id for the phone, [name] what LMS calls it. */
fun ServerClient.phoneHello(deviceId: String, name: String): PhoneHello =
    PhoneHello.parse(post("/api/phone/hello", JSONObject().put("device_id", deviceId).put("name", name), 30_000))

/** Home again: its player stopped. */
fun ServerClient.phoneBye(playerId: String) { post("/api/phone/bye", JSONObject().put("player_id", playerId), 15_000) }
