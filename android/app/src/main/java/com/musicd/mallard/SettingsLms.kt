package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.SettingsLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Settings → LMS server (public/index.html's LMS server pane): the connection, LMS's own settings pages, its plugins
 * (PluginsPane.kt), a rescan, and the streaming albums in the library. Out of SettingsMallard.kt, which only shrinks.
 */

// ---- LMS server --------------------------------------------------------------------------------------------------

@Composable
internal fun LmsPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("…") }
    var mode by remember { mutableStateOf("") }
    var plugins by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        val j = withContext(Dispatchers.IO) { runCatching { c.lmsConnection() }.getOrNull() }
        status = if (j == null) "Couldn't ask the server" else {
            val where = j.optString("host") + ":" + j.optInt("port", 9000)
            val name = j.optJSONObject("server")?.let { s -> listOf(s.optString("name"), s.optString("version")).filter { it.isNotEmpty() }.joinToString(" ") }.orEmpty()
            if (j.optBoolean("connected")) "Connected to $where" + (if (name.isNotEmpty()) " · $name" else "")
            else "Not connected to $where" + (j.optString("lastError").takeIf { it.isNotEmpty() && !j.isNull("lastError") }?.let { " — $it" } ?: "")
        }
    }
    if (plugins) { PluginsPane(client) { plugins = false }; return }
    if (settingsOpen) { LmsPage(null, "Server settings", client) { settingsOpen = false }; return }
    SettingsFrame {
        PaneHead("LMS server", onBack)
        Block {
            SetupRow("Server", Modifier, null) {}
            SetupNote(status, Modifier)
        }
        SettingsDivider()
        Block {
            SetupRow("Server settings", Modifier, Modifier.clickable { info("The full Lyrion server settings (library, plugins, players, advanced…), opened here in Mallard. Uses Material Skin's pages when that plugin is installed.") }) {
                SetupButton("Open", false, Modifier.clickable { settingsOpen = true })
            }
            SetupNote("Opens Lyrion's own settings pages here in Mallard.", Modifier)
        }
        SettingsDivider()
        Block {
            SetupRow("Plugins", Modifier, Modifier.clickable { info("Install, update and remove LMS plugins — Qobuz, TIDAL, Spotty, radio apps and more — from your server's own plugin list. LMS applies changes when it restarts, which you can do from here too.") }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BetaTag()   // plugins are a work in progress (BetaTag.kt)
                    SetupButton("Open", false, Modifier.clickable { plugins = true })
                }
            }
        }
        SettingsDivider()
        Block {
            val choices = SettingsLists.RESCAN_MODES.map { Choice(it.first, it.second) }
            SetupRow("Rescan media library", Modifier, Modifier.clickable { info("Rescan the LMS library on the server (playback keeps working while it scans). Choose what to scan, then tap Rescan.") }) {
                SetupButton("Rescan", false, Modifier.clickable {
                    val c = client() ?: return@clickable
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { c.post("/api/lms/rescan", JSONObject().put("mode", mode.ifEmpty { JSONObject.NULL }), 20_000) } }
                        setupToast(context, r.fold({ "Rescan started — the library updates when LMS finishes" }, { Failure.said(it, "Rescan failed") }))
                    }
                })
            }
            SelectBox(choiceWords(choices, mode), choices, Modifier, Modifier.padding(top = 8.dp).clickable { pickChoice(context, choices, mode) { mode = it } }, width = 300.dp)
        }
        SettingsDivider()
        OnlineLibraryBlock(client, info)
    }
}

/**
 * Streaming albums in the library (LMS's Online Music Library import of Qobuz / TIDAL favourites): how many, or why
 * none, in the server's own words (/api/library/online), so the web app and Mallard say the same; Import now asks LMS
 * to fetch the favourites again.
 */
@Composable
private fun OnlineLibraryBlock(client: () -> ServerClient?, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        val j = withContext(Dispatchers.IO) { runCatching { c.getJson("/api/library/online", 15_000) }.getOrNull() }
        val a = j?.optJSONArray("services")
        lines = if (j == null) listOf("Couldn't ask the server." to true)
            else if (a == null || a.length() == 0) listOf("No streaming plugin is installed on your server." to false)
            else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("say") to it.optBoolean("problem") }
    }
    Block {
        SetupRow("Streaming albums in your library", Modifier, Modifier.clickable { info("LMS's Online Music Library integration copies the albums you've favourited in Qobuz or TIDAL into your library, where Mallard lists them beside your own files. Albums you only browse inside the Qobuz plugin aren't in the library. Import now asks LMS to fetch your favourites again.") }) {
            SetupButton("Import now", false, Modifier.clickable {
                val c = client() ?: return@clickable
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { c.post("/api/lms/rescan", JSONObject().put("mode", "onlinelibrary"), 20_000) } }
                    setupToast(context, r.fold({ "Importing your streaming favourites — they appear in the library once LMS has them" }, { Failure.said(it, "Couldn't start the import") }))
                }
            })
        }
        for ((said, problem) in lines.orEmpty()) Text(said, Modifier.padding(top = 6.dp), color = if (problem) paletteColor(Palette.DANGER) else paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.4.sp)
        if (lines == null) SetupNote("…", Modifier)
    }
}
