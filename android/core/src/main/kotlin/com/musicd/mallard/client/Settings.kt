package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * Settings, typed (the group T of old/plan-android-native.md, T1; bug list item 76): the list and Setup's list
 * as the page has them, the server's version at the list's foot (settings.js's loadVersion), the devices signed in to the
 * account (settings-account.js). The API keys' page went in item 494: Mandarin carries its own, and Last.fm's went.
 */

/** A row of a Settings list: the page it opens ([pane], the page's own name for it) and its title. */
data class SettingsRow(val pane: String, val title: String)

object SettingsLists {
    /** The Settings list, in the page's order. */
    val HOME = listOf(SettingsRow("account", "Account"), SettingsRow("folders", "Music Folders"), SettingsRow("streaming", "Services"),
        SettingsRow("playback", "Audio Devices"), SettingsRow("identify", "Library Scanner"), SettingsRow("setup", "Setup"),
        SettingsRow("backup", "Backup & restore"), SettingsRow("sqtool", "SQ-tool"), SettingsRow("system", "Updates"))

    /** SQ-tool's address on the server (item 288): its own page, opened in the browser as it opens standalone. */
    const val SQ_TOOL = "/sq-tool/"

    /** Setup's list, the app's Downloads before Away from home, as the page puts it in the app. */
    val SETUP = listOf(SettingsRow("smartpicks", "Smart Picks"), SettingsRow("homescreen", "Home Screen"),
        SettingsRow("ui", "UI Settings"), SettingsRow("display", "Wall Display"), SettingsRow("shelfset", "Shelf"),
        SettingsRow("downloads", "Downloads"), SettingsRow("away", "Away from home"))

    const val SETUP_DESC = "Smart Picks, the Home screen, the look, the wall display and Shelf, away from home"
    const val REPO = "https://github.com/meltface-80/Mandarin"

    /** The version at the list's foot, from /api/update/status: "Mandarin v1.0 (Build 17)"; null when there is none to say. */
    fun version(j: JSONObject?): String? {
        val current = j?.optString("current").orEmpty().takeIf { it.isNotEmpty() && !j!!.isNull("current") } ?: return null
        val parts = current.split(".")
        return if (parts.size >= 3) "Mandarin v${parts[0]}.${parts[1]} (Build ${parts[2]})" else "Mandarin v$current"
    }
}

/** A device signed in to the account: its name, an Android app's or a browser, this one or when it was last seen. */
data class SignedInDevice(val id: String, val name: String, val android: Boolean, val current: Boolean, val lastSeenMs: Long) {
    /** The line under its name: "Android app · this device", "Browser · 3 hours ago". */
    fun sub(nowMs: Long): String = (if (android) "Android app" else "Browser") + " · " + if (current) "this device" else ago(lastSeenMs, nowMs)

    companion object {
        /** How long ago, as the page says it: "active now", "12 min ago", "1 hour ago", "3 days ago". */
        fun ago(ms: Long, nowMs: Long): String {
            val m = Math.round((nowMs - ms) / 60000.0)
            if (m < 2) return "active now"
            if (m < 60) return "$m min ago"
            val h = Math.round(m / 60.0)
            if (h < 48) return h.toString() + if (h == 1L) " hour ago" else " hours ago"
            return "${Math.round(h / 24.0)} days ago"
        }
    }
}

/** Who is signed in, and the devices signed in to the account (/api/auth/devices). */
data class AccountDevices(val username: String, val devices: List<SignedInDevice>) {
    val who: String get() = "Signed in as " + username.ifEmpty { "?" }

    companion object {
        const val PATH = "/api/auth/devices"
        const val FAILED = "Couldn't load the account"

        fun parse(j: JSONObject): AccountDevices = AccountDevices(j.optString("username").takeIf { !j.isNull("username") }.orEmpty(),
            objs(j.optJSONArray("devices")).map {
                SignedInDevice(it.optString("id"), it.optString("name"), it.optString("kind") == "android", it.optBoolean("current"), it.optLong("last_seen"))
            })

        /** What Ask for a sign-in means, on ([open] false) or off (item 161), as the page says it (settings-account.js). */
        fun askNote(open: Boolean, hasAccount: Boolean): String = if (open)
            "Off: anything on your home network or your Tailscale network uses Mandarin without signing in — a TV showing the shelf or the wall, say. Anywhere else still can't. " +
                (if (hasAccount) "Switch it on to ask for the username and password again." else "Switching it on makes the account first.")
        else "Every device signs in with the username and password. Switch it off to use Mandarin on a TV or a wall display without typing them: anything on your home network or your Tailscale network then comes straight in."

        /** Why a new password can't be set yet (null: it can be tried), as the page says it. */
        fun passwordProblem(current: String, new: String, again: String): String? = when {
            current.isEmpty() -> "Enter your current password."
            new.length < 8 -> "Use at least 8 characters for the new password."
            new != again -> "The two new passwords don't match."
            else -> null
        }
    }
}

private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
