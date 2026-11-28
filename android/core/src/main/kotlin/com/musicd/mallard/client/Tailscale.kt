package com.musicd.mallard.client

import org.json.JSONObject

/*
 * The server's built-in Tailscale (away from home; lib/tailscale.js, /api/tailscale…), as Settings → Away from home
 * draws it in both apps (public/away-settings.js and Mallard's AwayPane).
 */

/** The engine as the server reports it: [state] Tailscale's own ("NeedsLogin", "Running"…), [address] once it serves. */
data class TailscaleState(
    val available: Boolean, val enabled: Boolean, val running: Boolean, val state: String, val authUrl: String?, val dnsName: String?,
    val serving: Boolean, val online: Boolean?, val health: List<String>, val address: String?, val error: String?, val hostname: String
) {
    /** Its state in words, as the web pane says it. */
    val words: String get() = if (!enabled) "Off" else when (state) {
        "Stopped", "Starting", "NoState" -> "Starting…"
        "NeedsLogin" -> "Not signed in yet"
        "NeedsMachineAuth" -> "Waiting for approval in the Tailscale admin console"
        "Running" -> if (serving) "Connected" else "Connecting…"
        else -> state
    }

    /** Running but not well: what to say about it, or null. */
    val warning: String? get() = if (state != "Running") null else when {
        online == false -> "Running, but not online on the tailnet — the server is rebinding it, then starting it afresh"
        health.isNotEmpty() -> "Tailscale warns: " + health.joinToString("; ")
        else -> null
    }

    companion object {
        private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
        fun parse(j: JSONObject) = TailscaleState(
            j.optBoolean("available"), j.optBoolean("enabled", true), j.optBoolean("running"), j.optString("state", "Stopped"),
            j.text("auth_url"), j.text("dns_name"), j.optBoolean("serving"), if (!j.has("online") || j.isNull("online")) null else j.optBoolean("online"),
            j.optJSONArray("health")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), j.text("address"), j.text("error"),
            j.optString("hostname", "musicd-lms"))
    }
}

fun ServerClient.tailscale(): TailscaleState = TailscaleState.parse(getJson("/api/tailscale"))
fun ServerClient.tailscaleLogin(): TailscaleState = TailscaleState.parse(post("/api/tailscale/login", JSONObject(), 30_000))
fun ServerClient.tailscaleLogout(): TailscaleState = TailscaleState.parse(post("/api/tailscale/logout", JSONObject(), 30_000))
fun ServerClient.tailscaleEnable(on: Boolean): TailscaleState = TailscaleState.parse(post("/api/tailscale/enable", JSONObject().put("on", on), 30_000))
