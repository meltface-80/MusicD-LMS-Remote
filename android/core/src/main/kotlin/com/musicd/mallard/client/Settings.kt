package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Settings' lists: MusicD LMS Remote's own Settings, section for section in the server page's order (public/index.html,
 * the settings-nav list), each with the line under its name — but Playback and Player settings are one page, Audio
 * Devices, as Mandarin's (owner request). Mallard draws them in Mandarin's colours; the layout is the
 * server page's, which is the owner's choice.
 */

/** A section of the Settings list: its pane, its name, and the line under it. */
data class SettingsRow(val pane: String, val title: String, val desc: String = "")

object SettingsLists {
    /** The Settings list, in the server page's order, then Mallard's own (the server it talks to). */
    val HOME = listOf(
        SettingsRow("playback", "Audio Devices", "Every player: power, radio & Lyrion's settings"),
        SettingsRow("labels", "Labels", "Record labels on Home and in search"),
        SettingsRow("metadata", "Artwork & metadata", "Discogs & FanArt.tv keys"),
        SettingsRow("homescreen", "Home Screen", "Which rows show, and in what order"),
        SettingsRow("smartpicks", "Smart Picks", "Daily discoveries & schedule"),
        SettingsRow("lms", "LMS server", "Server settings, plugins & rescan"),
        SettingsRow("away", "Away from home", "Tailscale built in: your music anywhere"),
        SettingsRow("display", "Wall display", "Always-on screen, video & the Shelf"),
        SettingsRow("appearance", "Appearance", "Mallard's colours"),
        SettingsRow("system", "System", "Server address, updates & version"))

    const val REPO = "https://github.com/meltface-80/MusicD-LMS-Remote"

    /** LMS's rescan choices, as the server page offers them (its value, its words). */
    val RESCAN_MODES = listOf("" to "Look for new and changed media files", "full" to "Clear library and rescan everything",
        "playlists" to "Only rescan playlists", "onlinelibrary" to "Only rescan online music library")

    /** The version at the list's foot, from /api/update/status: "Mallard server v1.0.85"; null when there is none to say. */
    fun version(j: JSONObject?): String? {
        val current = j?.optString("current").orEmpty().takeIf { it.isNotEmpty() && !j!!.isNull("current") } ?: return null
        return "Mallard server v$current"
    }
}
