package com.musicd.mallard.client

import org.json.JSONObject

/*
 * This phone as a player (Mandarin's rule, owner request): the phone's own LMS player is fixed at 100% in LMS, and its
 * volume is the PHONE's — the volume buttons, the lock screen and Mallard's own slider move the phone (Android's media
 * volume, or a USB DAC's level). Every volume call goes through ServerClient, so this is the one place it is decided:
 * for the zone that is this phone, ServerClient asks [volume] instead of the server, and /api/zone-state's answer for it
 * carries the phone's level, so every screen shows what the buttons set.
 *
 * [deviceId] goes with every request (X-Mallard-Device), so the server can list "This phone" among the rooms at home too.
 */
object ThisPhone {
    /** The app's own id for this phone (Store.deviceId), set once at start. */
    @Volatile var deviceId: String? = null
    /** The phone's own volume, for its own player; set by the app. */
    @Volatile var volume: LocalVolume? = null
    /** Away from home, this phone's player — what every screen's room is sent as (AwayRooms); null at home. Set by the app. */
    @Volatile var awayRoom: () -> String? = { null }

    /**
     * This phone's LMS player id for [deviceId], whether or not it runs: the server's macFor (lib/phoneplayers.js) —
     * "02:6d:" and the first four bytes of SHA-1("mallard-phone:" + id). Keep the two in step.
     */
    fun playerId(deviceId: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(("mallard-phone:" + deviceId).toByteArray(Charsets.UTF_8))
        return (listOf("02", "6d") + (0 until 4).map { String.format("%02x", h[it].toInt() and 0xff) }).joinToString(":")
    }

    /** The hook, when [zoneId] is this phone's player. */
    fun owning(zoneId: String): LocalVolume? = volume?.takeIf { zoneId.isNotEmpty() && it.owns(zoneId) }

    /**
     * /api/zone-state's answer with the phone's own volume put in for its outputs: [level] 0–100, or null for a level the
     * phone can't change (a USB DAC at fixed volume), which the screens show as "Volume control is fixed".
     */
    fun withLevel(j: JSONObject, level: Int?, muted: Boolean): JSONObject {
        val outs = j.optJSONObject("zone")?.optJSONArray("outputs") ?: return j
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (level == null) o.remove("volume")
            else o.put("volume", JSONObject().put("value", level).put("min", 0).put("max", 100).put("step", 1).put("type", "number"))
            o.put("is_muted", muted)
        }
        return j
    }
}

/** A phone's own volume (the app's PhoneVolume): Android's media volume, or the USB DAC's level. */
interface LocalVolume {
    /** Whether [zoneId] is this phone's player. */
    fun owns(zoneId: String): Boolean
    /** The level, 0–100; null when it can't be changed here. */
    fun level(): Int?
    fun muted(): Boolean
    fun set(value: Int)
    /** One step up (1) or down (-1). */
    fun step(direction: Int)
    fun mute(on: Boolean)
}
