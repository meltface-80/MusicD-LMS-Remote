package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Settings → Audio Devices (owner request: Playback and Player settings "condensed into one settings page called Audio
 * Devices like Mandarin"): every Lyrion player as Mandarin draws its devices — one card each with its kind's icon, its
 * name, its model under it and what it is doing — and a tap opens the player's own page (its power, random album radio,
 * name, and Lyrion's settings for it). Read from /api/zones, which carries each player's model, power and connection.
 */

/** One player on the list. [phone]: a Mallard phone's own player; [thisPhone]: this phone's. */
data class DeviceRow(
    val id: String, val name: String, val model: String, val state: String,
    val power: Boolean?, val connected: Boolean, val phone: Boolean, val thisPhone: Boolean
) {
    /** Switched off in Lyrion (a player with power of its own). */
    val off: Boolean get() = power == false
    /** The word on its card: Offline, Off, Playing, Paused, Idle. */
    val stateShown: String get() = when {
        !connected && !thisPhone -> "Offline"
        off -> "Off"
        state == "playing" -> "Playing"
        state == "paused" -> "Paused"
        else -> "Idle"
    }
    val playing: Boolean get() = state == "playing" && connected && !off
    /** Its icon: a phone's, or a network player's. */
    val kind: String get() = if (phone) "phone" else "upnp"
    /** The line under its name: its model, and for this phone what it is. */
    val sub: String get() = listOf(model.takeIf { it.isNotEmpty() }, if (thisPhone) "this phone" else null).filterNotNull().joinToString(" · ")
}

object AudioDevices {
    /** The players in /api/zones' answer, in its order (this phone, at home, last); [ownId] this phone's player id. */
    fun parse(j: JSONObject, ownId: String?): List<DeviceRow> {
        val a = j.optJSONArray("zones") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { z ->
            val id = z.optString("zone_id")
            DeviceRow(id, z.optString("display_name", id), z.optString("model"), z.optString("state", "stopped"),
                if (z.has("power") && !z.isNull("power")) z.optBoolean("power") else null,
                if (z.has("connected")) z.optBoolean("connected") else true,
                id.startsWith("02:6d:", ignoreCase = true), id == ownId)
        }
    }

    const val ZONE_INFO = "The player Play, Queue and the transport controls send to — on every screen. The same as Play on."
    const val DEVICES_INFO = "Every player Lyrion knows. Tap one for its own page: switch it on or off, random album radio, its name, and Lyrion's settings for it — crossfade, volume levelling, Don't Stop The Music, sync."
    const val RADIO_INFO = "When this player's queue runs out, keep it going by queueing whole random albums from your library rather than shuffling single tracks. It stands down on its own while Lyrion's own Don't Stop The Music is on for the player, so the two never fight over the queue."
}
