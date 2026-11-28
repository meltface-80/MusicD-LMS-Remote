package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLEncoder

/*
 * This phone as an LMS player of its own (the server's lib/phoneplayers.js + lib/relayplayer.js), as Mandarin's phone
 * zone: the phone says hello, the server runs its player, LMS streams each track to it as the file's own bytes, and the
 * phone plays them ONE ITEM PER TRACK (PhoneRelay), reporting back. Each track is the file itself — bit-perfect, at home
 * always — or, away from home with Opus chosen (the default, as Mandarin's), its Opus 256 kbps, made by the server per
 * track and played at 24-bit/48 kHz. Its volume is the phone's own (ThisPhone); LMS's ReplayGain is applied on the phone.
 */
data class PhoneHello(val playerId: String, val name: String, val away: Boolean) {
    companion object { fun parse(j: JSONObject) = PhoneHello(j.optString("player_id"), j.optString("name"), j.optBoolean("away")) }
}

object PhoneStream {
    /** How this phone's tracks come away from home (Settings → Away from home → This phone): its value and its words. */
    val QUALITIES = listOf("opus" to "Opus 256 kbps · 24-bit/48 kHz", "exact" to "Bit-perfect (untouched)")
    const val DEFAULT = "opus"
    const val NOTE = "Away from home, each track comes as Opus 256 kbps, made from the file by the server and played at 24-bit/48 kHz — " +
        "the rate Android's audio runs at, about a quarter of a CD's data — or bit-perfect: the file itself, untouched, at its own rate " +
        "and depth (for a USB DAC with USB direct on). At home it is always bit-perfect. Lyrion's volume levelling is applied on the " +
        "phone, and the volume is the phone's own: Lyrion has this player fixed at 100%."
    /** The setting as kept: "exact" (bit-perfect) or "opus" — an older "flac" (no longer offered) is Opus. */
    fun quality(v: String?): String = if (v == "exact") "exact" else DEFAULT
    /** Whether a track queued now comes as Opus: away from home with Opus chosen (Mandarin's rule; at home always the file). */
    fun opus(away: Boolean, q: String?): Boolean = away && quality(q) == "opus"

    /** The player's commands for [playerId] (Server-Sent Events). */
    fun eventsPath(playerId: String): String = "/api/phone/events?player_id=" + enc(playerId)
    /** A track by its LMS id (lib/phonefiles.js): the file itself, or ([opus]) its Opus — the address the phone's cache keeps it under. */
    fun filePath(trackId: String, opus: Boolean): String = "/api/phone/file?id=" + enc(trackId) + if (opus) "&q=opus" else ""
    /** The cache's name for a [filePath] address (CachePlan.key), or null for any other. */
    fun cacheKey(path: String): String? =
        Regex("/api/phone/file\\?id=(\\d+)(&q=opus)?$").find(path)?.let { CachePlan.key(it.groupValues[1], it.groupValues[2].isNotEmpty()) }

    /** One track: the file itself, or ([opus]) its Opus 256 kbps. */
    fun trackPath(playerId: String, seq: Int, opus: Boolean = false): String =
        "/api/phone/track?player_id=" + enc(playerId) + "&seq=" + seq + if (opus) "&q=opus" else ""
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * A command from the server's relay player (its event channel), as the phone acts on it:
 *   add     a track to play: [seq], its type; [fresh] the first of a new start (what was queued goes), [play] start at once,
 *           [gainDb] LMS's ReplayGain for it (null: none), applied on the phone as Mandarin's phone does;
 *           [trackId] its LMS id when it is the library's own and starts at its beginning (null otherwise): then the
 *           phone plays it by id, from its cache when it has it (PhoneStream.filePath)
 *   drop    the queued track [seq] let go (the playlist changed)
 *   stop / pause / resume
 * (No volume: LMS has this player fixed at 100%, and its volume is the phone's own — ThisPhone.)
 */
data class RelayCommand(val cmd: String, val seq: Int = 0, val mime: String = "", val fresh: Boolean = false, val play: Boolean = false,
                        val gainDb: Double? = null, val trackId: String? = null) {
    companion object {
        /** A `data:` line's JSON, or null for anything that isn't a command the phone knows. */
        fun parse(json: String): RelayCommand? {
            val j = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val cmd = j.optString("cmd")
            if (cmd !in setOf("add", "drop", "stop", "pause", "resume")) return null
            if ((cmd == "add" || cmd == "drop") && j.optInt("seq", 0) <= 0) return null
            return RelayCommand(cmd, j.optInt("seq", 0), j.optString("mime"), j.optBoolean("fresh"), j.optBoolean("play"),
                if (!j.has("gain_db") || j.isNull("gain_db")) null else j.optDouble("gain_db"),
                j.optString("track_id").takeIf { !j.isNull("track_id") && it.matches(Regex("\\d+")) })
        }

    }
}

/** Hello: this phone's player, started if it isn't; [maxRate] the highest rate it (its USB DAC) takes. */
fun ServerClient.phoneHello(deviceId: String, name: String, maxRate: Int = 192000): PhoneHello =
    PhoneHello.parse(post("/api/phone/hello", JSONObject().put("device_id", deviceId).put("name", name).put("max_rate", maxRate), 30_000))

/** Home again: its player stopped. */
fun ServerClient.phoneBye(playerId: String) { post("/api/phone/bye", JSONObject().put("player_id", playerId), 15_000) }

/** What the phone plays, for the relay: [ev] started / pos / drained / failed, of track [seq], [ms] into it. */
fun ServerClient.phoneReport(playerId: String, ev: String, seq: Int, ms: Long, playing: Boolean) {
    post("/api/phone/report", JSONObject().put("player_id", playerId).put("ev", ev).put("seq", seq).put("ms", ms).put("playing", playing), 10_000)
}

/** What this phone's player saw (an error, a fallback), for the server's log, where the owner can read it. */
fun ServerClient.phoneNote(playerId: String, text: String) {
    post("/api/phone/report", JSONObject().put("player_id", playerId).put("ev", "note").put("text", text.take(400)), 10_000)
}

/** The next [count] tracks of this phone's queue after the one playing, by LMS id (for its cache ahead). */
fun ServerClient.phoneAhead(playerId: String, count: Int): List<String> {
    val a = getJson("/api/phone/ahead?player_id=" + java.net.URLEncoder.encode(playerId, "UTF-8") + "&count=" + count, 15_000).optJSONArray("tracks")
        ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
}
