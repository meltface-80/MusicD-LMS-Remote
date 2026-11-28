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

    /** The room the screens play in now: the one kept ("" when none has been chosen yet). */
    fun playRoom(context: Context): String = zone(context).orEmpty()

    fun setZone(context: Context, zoneId: String?) { prefs(context).edit().putString(KEY_ZONE, zoneId).apply() }

    /** The screen settings as JSON (core ScreenPrefs). */
    fun homePrefs(context: Context): String? = prefs(context).getString("home_prefs", null)
    fun setHomePrefs(context: Context, json: String) { prefs(context).edit().putString("home_prefs", json).apply() }

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
