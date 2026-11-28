package com.musicd.mallard

import android.content.Context
import com.musicd.mallard.client.PhoneTailscale
import com.musicd.mallard.client.ServerAddress
import com.musicd.mallard.client.ServerClient

/**
 * What Mallard keeps on the phone: which server, which player the screens play in, and the screen settings (the
 * Library's view, the theme of the UI, the menu's rows). The server has no sign-in, so there is no token.
 */
object Store {
    private const val PREFS = "mallard"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_ZONE = "zone"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Shelf's own keeps, as the page keeps them in localStorage: "order" (the order last chosen), "genre-sort", "help". */
    fun shelf(context: Context, key: String): String? = prefs(context).getString("shelf_$key", null)
    fun setShelf(context: Context, key: String, value: String) { prefs(context).edit().putString("shelf_$key", value).apply() }

    fun server(context: Context): ServerAddress? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return null
        val port = p.getInt(KEY_PORT, ServerAddress.DEFAULT_PORT)
        return runCatching { ServerAddress(host, port) }.getOrNull()
    }

    fun setServer(context: Context, address: ServerAddress) {
        val before = server(context)
        prefs(context).edit().putString(KEY_HOST, address.host).putInt(KEY_PORT, address.port).apply {
            // Another server's players are not this one's: the player chosen there is forgotten.
            if (before != null && before != address) remove(KEY_ZONE)
        }.apply()
    }

    fun lastPort(context: Context): Int = prefs(context).getInt(KEY_PORT, ServerAddress.DEFAULT_PORT)

    /** The player the screens play in, as last chosen. */
    fun zone(context: Context): String? = prefs(context).getString(KEY_ZONE, null)

    /** The room the screens play in now: away, this phone's own player (PhoneZone); else the one kept ("" when none yet). */
    fun playRoom(context: Context): String = PhoneZone.id ?: zone(context).orEmpty()

    /** This phone's own id for the server (its player's MAC is made from it): made once, kept. */
    fun deviceId(context: Context): String = prefs(context).getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { prefs(context).edit().putString("device_id", it).apply() }

    /** This phone's player: "opus" (256 kbps, 24-bit/48 kHz) or "exact" (bit-perfect) (Settings → Away from home → This phone). */
    fun awayQuality(context: Context): String = com.musicd.mallard.client.PhoneStream.quality(prefs(context).getString("away_quality", null))
    fun setAwayQuality(context: Context, q: String) { prefs(context).edit().putString("away_quality", q).apply() }

    /** The USB DAC away from home (UsbDac, UsbDriver; Mandarin's settings): USB direct, the DAC's level, fixed volume, its limit, which device. */
    fun usbDirect(context: Context): Boolean = prefs(context).getBoolean("usb_direct", false)
    fun setUsbDirect(context: Context, on: Boolean) { prefs(context).edit().putBoolean("usb_direct", on).apply() }
    fun usbVolume(context: Context): Int = prefs(context).getInt("usb_volume", -1)
    fun setUsbVolume(context: Context, pct: Int) { prefs(context).edit().putInt("usb_volume", if (pct < 0) -1 else pct.coerceIn(0, 100)).apply() }
    /** Fixed volume as set, else [default] (fixed for a DAC with no volume control of its own). */
    fun usbFixed(context: Context, default: Boolean): Boolean = prefs(context).let { if (it.contains("usb_fixed")) it.getBoolean("usb_fixed", default) else default }
    fun setUsbFixed(context: Context, on: Boolean) { prefs(context).edit().putBoolean("usb_fixed", on).apply() }
    fun usbLimit(context: Context): Int = prefs(context).getInt("usb_limit", 80).coerceIn(1, 100)
    fun setUsbLimit(context: Context, pct: Int) { prefs(context).edit().putInt("usb_limit", pct.coerceIn(1, 100)).apply() }
    fun usbDevice(context: Context): String? = prefs(context).getString("usb_device", null)
    fun setUsbDevice(context: Context, key: String?) { prefs(context).edit().apply { if (key.isNullOrBlank()) remove("usb_device") else putString("usb_device", key) }.apply() }

    fun setZone(context: Context, zoneId: String?) { prefs(context).edit().putString(KEY_ZONE, zoneId).apply() }

    /** The screen settings as JSON (core ScreenPrefs). */
    fun homePrefs(context: Context): String? = prefs(context).getString("home_prefs", null)
    fun setHomePrefs(context: Context, json: String) { prefs(context).edit().putString("home_prefs", json).apply() }

    /** Settings → Labels: a label's albums "alpha" or "random", and the fewest albums a label needs to be listed. */
    fun labelOrder(context: Context): String = if (prefs(context).getString("label_order", "alpha") == "random") "random" else "alpha"
    fun setLabelOrder(context: Context, order: String) { prefs(context).edit().putString("label_order", order).apply() }
    fun labelMin(context: Context): Int = prefs(context).getInt("label_min", 1).coerceAtLeast(1)
    fun setLabelMin(context: Context, min: Int) { prefs(context).edit().putInt("label_min", min).apply() }

    /** The side menu's rows as the owner arranged them. */
    fun menuKept(context: Context): String? = prefs(context).getString("menu_kept", null)
    fun setMenuKept(context: Context, kept: String) { prefs(context).edit().putString("menu_kept", kept).apply() }

    /**
     * The server's address on its tailnet ("http://100.x.y.z:3390"), as it last said it (/api/status `tailscale`): where
     * Mallard's own Tailscale goes away from home (Away.kt). Kept, so it is known when the phone is already out.
     */
    fun awayLearned(context: Context): String? = prefs(context).getString("away_learned", null)
    fun setAwayLearned(context: Context, address: String?) {
        if (address != null && address != awayLearned(context)) prefs(context).edit().putString("away_learned", address).apply()
    }

    /** Away from home over Mallard's own Tailscale, and its engine forwarding (Away.kt): then the server is at 127.0.0.1. */
    @Volatile var awayVia: Boolean = false

    /** The address the app talks to the server on now: through the phone's Tailscale away, the saved one at home. */
    fun active(context: Context): ServerAddress? = server(context)?.let { if (awayVia) PhoneTailscale.AWAY else it }

    /** The server's client, or null before a server has been chosen. */
    fun client(context: Context): ServerClient? = active(context)?.let { ServerClient(it) }

    /** The client the screens use (the same: at home the saved address, away the phone's Tailscale). */
    fun screenClient(context: Context): ServerClient? = client(context)
}
