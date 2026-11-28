package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLEncoder

/*
 * Away from home the phone is an LMS player of its own (the server's lib/phoneplayers.js; Mandarin's phone zone): the
 * phone says hello, the server runs its player and the app shows and plays in that player alone ("This phone"). Two
 * ways it reaches the phone: squeezelite's sound made into a stream (Opus 256 kbps or FLAC 24-bit/48 kHz), or —
 * "Untouched", for a USB DAC — the server's own relay player (lib/relayplayer.js), which hands the phone each track as
 * the file's own bytes, to play as a playlist and report on (PhoneRelay).
 */
data class PhoneHello(val playerId: String, val name: String, val away: Boolean, val mode: String) {
    companion object { fun parse(j: JSONObject) = PhoneHello(j.optString("player_id"), j.optString("name"), j.optBoolean("away"), j.optString("mode").ifEmpty { "pcm" }) }
}

object PhoneStream {
    /** How the phone's player reaches it away (Settings → Away from home → This phone), its value and its words. */
    val QUALITIES = listOf("opus" to "Opus 256 kbps · 24-bit/48 kHz", "exact" to "Bit-perfect (untouched)")
    const val DEFAULT = "opus"
    const val NOTE = "Opus: Lyrion plays to this phone's own player, so its volume levelling and player settings apply, and the server " +
        "sends it on as Opus 256 kbps at 24-bit/48 kHz — the rate Android's audio runs at, so the phone doesn't resample it. " +
        "Bit-perfect: each track reaches the phone as its own file, bit for bit, at its own rate and depth — for a USB DAC with USB " +
        "direct on; nothing is done to the samples, so no volume levelling. The volume is always the phone's own: Lyrion has this " +
        "player fixed at 100%."
    /** The setting as kept: "exact" (bit-perfect) or "opus" — an older "flac" (no longer offered: not lossless at 48 kHz) is Opus. */
    fun quality(v: String?): String = if (v == "exact") "exact" else DEFAULT
    /** The server's player for [q]: "exact" (the relay) for Untouched, else "pcm" (squeezelite). */
    fun mode(q: String?): String = if (quality(q) == "exact") "exact" else "pcm"

    /** The stream's path on the server, for [playerId] (Opus; [q] as kept). */
    fun path(playerId: String, q: String): String = "/api/phone/stream?player_id=" + enc(playerId) + "&q=" + quality(q)
    /** The relay's commands for [playerId] (Server-Sent Events). */
    fun eventsPath(playerId: String): String = "/api/phone/events?player_id=" + enc(playerId)
    /** One relayed track's own bytes. */
    fun trackPath(playerId: String, seq: Int): String = "/api/phone/track?player_id=" + enc(playerId) + "&seq=" + seq
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * A command from the server's relay player (its event channel), as the phone acts on it:
 *   add     a track to play: [seq], its type; [fresh] the first of a new start (what was queued goes), [play] start at once
 *   drop    the queued track [seq] let go (the playlist changed)
 *   stop / pause / resume
 * (No volume: LMS has this player fixed at 100%, and its volume is the phone's own — ThisPhone.)
 */
data class RelayCommand(val cmd: String, val seq: Int = 0, val mime: String = "", val fresh: Boolean = false, val play: Boolean = false) {
    companion object {
        /** A `data:` line's JSON, or null for anything that isn't a command the phone knows. */
        fun parse(json: String): RelayCommand? {
            val j = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val cmd = j.optString("cmd")
            if (cmd !in setOf("add", "drop", "stop", "pause", "resume")) return null
            if ((cmd == "add" || cmd == "drop") && j.optInt("seq", 0) <= 0) return null
            return RelayCommand(cmd, j.optInt("seq", 0), j.optString("mime"), j.optBoolean("fresh"), j.optBoolean("play"))
        }

    }
}

/** Hello: this phone's player, started if it isn't, as [mode] ("pcm" or "exact"); [maxRate] the highest rate its DAC takes. */
fun ServerClient.phoneHello(deviceId: String, name: String, mode: String = "pcm", maxRate: Int = 192000): PhoneHello =
    PhoneHello.parse(post("/api/phone/hello", JSONObject().put("device_id", deviceId).put("name", name).put("mode", mode).put("max_rate", maxRate), 30_000))

/** Home again: its player stopped. */
fun ServerClient.phoneBye(playerId: String) { post("/api/phone/bye", JSONObject().put("player_id", playerId), 15_000) }

/** What the phone plays, for the relay: [ev] started / pos / drained / failed, of track [seq], [ms] into it. */
fun ServerClient.phoneReport(playerId: String, ev: String, seq: Int, ms: Long, playing: Boolean) {
    post("/api/phone/report", JSONObject().put("player_id", playerId).put("ev", ev).put("seq", seq).put("ms", ms).put("playing", playing), 10_000)
}
