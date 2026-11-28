package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Tailscale on the phone itself (owner request: "No Mallard android app tailscale login yet"): the same engine as the
 * server's (musicdnet/, built into the APK as libmusicdnet.so), run by the app, as Mandarin's phone runs it. Signed in
 * once with the owner's Tailscale account, it joins the tailnet as the phone and, away from home, forwards a port on
 * 127.0.0.1 to the server's own tailnet address, which the server says in /api/status (`tailscale`). The pure half:
 * the constants, the target, and the engine's status in words.
 */
object PhoneTailscale {
    /** The phone-side port: not Mandarin's 34500, so both apps can be away on one phone. */
    const val PORT = 34390
    const val HOSTNAME = "mallard-phone"
    /** The base Mallard talks to away, through the engine. */
    val AWAY = ServerAddress("127.0.0.1", PORT)

    /**
     * The engine's forward target from the server's tailnet address ("http://100.64.1.2:3390/" → "100.64.1.2:3390"),
     * or null when there isn't a usable one: an IPv4 address and a port, nothing else.
     */
    fun target(address: String?): String? {
        val a = address?.trim()?.removePrefix("http://")?.trimEnd('/') ?: return null
        return if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$").matches(a)) a else null
    }

    /** The lease Mallard renews while it is on screen and away: the engine stops itself after 150 s unused without one. */
    const val LEASE_S = 180
    const val RENEW_MS = 60_000L
}

/** The phone's engine as its /status says it: [state] Tailscale's own, [authUrl] while a sign-in waits. */
data class PhoneEngineState(val state: String, val authUrl: String?, val dnsName: String?, val ips: List<String>, val error: String?) {
    val signedIn: Boolean get() = state == "Running"

    /** In words, as Settings → Away from home → This phone says it. */
    val words: String get() = when (state) {
        "Running" -> "Signed in"
        "NeedsLogin" -> "Not signed in yet"
        "NeedsMachineAuth" -> "Waiting for approval in the Tailscale admin console"
        "", "NoState", "Stopped", "Starting" -> "Starting…"
        else -> state
    }

    /** The phone's own tailnet IPv4 address, once joined. */
    val address: String? get() = ips.firstOrNull { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) }

    companion object {
        private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
        fun parse(j: JSONObject) = PhoneEngineState(
            j.optString("state", ""), j.text("auth_url"), j.text("dns_name")?.removeSuffix("."),
            j.optJSONArray("ips")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), j.text("error"))
    }
}
