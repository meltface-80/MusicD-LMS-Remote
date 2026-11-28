package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.SetupSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Settings' panes, MusicD LMS Remote's own (public/index.html, the settings panes), each laid out as the server page
 * lays it out, in Mandarin's colours (SettingsWidgets.kt). What is kept on the server is read from it and saved to it;
 * the text and tile sizes are this phone's.
 */

/** A pane of Settings by its name, with the ⓘ's help along the foot while it is read. */
@Composable
internal fun SettingsPane(pane: String, client: () -> ServerClient?, onBack: () -> Unit) {
    var info by remember { mutableStateOf<String?>(null) }
    // The help shown long enough to read: four seconds and a fifth of one a word, 25 at most.
    LaunchedEffect(info) { val t = info ?: return@LaunchedEffect; delay(minOf(25_000L, 4_000L + t.split(Regex("\\s+")).size * 220L)); info = null }
    val showInfo = { t: String -> info = t }
    Box(Modifier.fillMaxSize()) {
        when (pane) {
            "playback" -> AudioDevicesPane(client, onBack, showInfo)   // SettingsAudio.kt: Audio Devices, as Mandarin's
            "labels" -> LabelsPane(client, onBack, showInfo)
            "metadata" -> MetadataPane(client, onBack, showInfo)
            "homescreen" -> HomeScreenPane(client, onBack)
            "smartpicks" -> PicksPane(client, onBack, showInfo)
            "lms" -> LmsPane(client, onBack, showInfo)
            "display" -> DisplayPane(client, onBack, showInfo)
            "away" -> AwayPane(client, onBack, showInfo)   // SettingsAway.kt
            "appearance" -> AppearancePane(onBack, showInfo)
            else -> SystemPane(client, onBack, showInfo)   // "system"; the server's address is here too (owner: Mallard's own pane folded in)
        }
        info?.let { InfoTip(it, Modifier) }
    }
}

/** A pane's head: Back in the brass disc, its name in the serif. */
@Composable
internal fun PaneHead(title: String, onBack: () -> Unit) {
    SettingsHead(Modifier.clip(CircleShape).clickable(onClick = onBack), R.drawable.np_back, title, Modifier)
}

// ---- Artwork & metadata ------------------------------------------------------------------------------------------

/** A key's row: what is set (masked, as the server says it), a box to type a new one, Save. */
@Composable
private fun KeyRow(client: () -> ServerClient?, name: String, path: String, field: String, help: String, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var masked by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { readSetting(client, path)?.let { masked = if (it.optBoolean("set")) it.optString("masked") else "" } }
    Block {
        SetupRow(name, Modifier, Modifier.clickable { info(help) }) {
            SetupButton("Save", false, Modifier.clickable {
                val v = typed.trim()
                if (v.isEmpty()) { setupToast(context, "Type the key first"); return@clickable }
                saveSetting(scope, context, client, path, JSONObject().put(field, v), "Saved ✓", undo = {}) { typed = ""; scope.launch { readSetting(client, path)?.let { masked = if (it.optBoolean("set")) it.optString("masked") else "" } } }
            })
        }
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
            .border(1.dp, paletteColor(Palette.BORDER), shape).padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 15.sp), singleLine = true, cursorBrush = SolidColor(paletteColor(Palette.ACCENT)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            decorationBox = { inner -> Box { if (typed.isEmpty()) Text(if (masked.isNotEmpty()) "Set: $masked" else "Not set", color = paletteColor(Palette.TEXT_FAINT), fontSize = 15.sp); inner() } })
    }
}

@Composable
private fun MetadataPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    SettingsFrame {
        PaneHead("Artwork & metadata", onBack)
        KeyRow(client, "Discogs token", "discogs-token", "token", "A Discogs personal access token improves label logos and label look-ups. Free from discogs.com → Settings → Developers.", info)
        SettingsDivider()
        KeyRow(client, "FanArt.tv key", "fanart-key", "key", "A FanArt.tv personal API key adds artist and label artwork. Free from fanart.tv.", info)
        SettingsDivider()
        KeyRow(client, "YouTube key", "youtube-key", "key", "A YouTube Data API key lets the wall display show the playing track's video. Free from Google Cloud Console.", info)
        SetupNote("Keys are saved on the server and shown masked.", Modifier.padding(bottom = 12.dp))
    }
}

// ---- Smart Picks -------------------------------------------------------------------------------------------------

@Composable
private fun PicksPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("smart-picks") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var hour by remember { mutableStateOf(kept?.optInt("hour", 4) ?: 4) }
    var auto by remember { mutableStateOf(kept?.optBoolean("auto_add") ?: false) }
    var ready by remember { mutableStateOf(kept?.optBoolean("service_ready") ?: true) }
    var known by remember { mutableStateOf(kept != null) }
    var rebuilding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { readSetting(client, "smart-picks")?.let { on = it.optBoolean("enabled"); hour = it.optInt("hour", hour); auto = it.optBoolean("auto_add"); ready = it.optBoolean("service_ready", true); known = true } }
    fun save(patch: JSONObject, said: String, undo: () -> Unit) = saveSetting(scope, context, client, "smart-picks", patch, said, undo)
    SettingsFrame {
        PaneHead("Smart Picks", onBack)
        Block {
            SetupRow("Smart Picks", Modifier, Modifier.clickable { info("Off by default. When on, the server analyses your library once a day and asks ListenBrainz and MusicBrainz for albums by artists you don't own, then looks them up in your server's Qobuz plugin. When off it does nothing at all — no daily build, no timer, no requests to anybody.") }) {
                Toggle(on, Modifier.clickable { val was = on; on = !on; save(JSONObject().put("enabled", on), SetupSettings.picksSaid(on)) { on = was } }, known)
            }
            SetupNote("Off by default. Everything below applies only while this is on.", Modifier)
        }
        Block {
            SetupRow("Build the day's picks at", Modifier, Modifier.clickable { info("The hour the daily build runs, in the server's local time. Early morning keeps it away from anything you are listening to. If the server is off at that hour, the build runs the next time it starts.") }) {
                SelectBox(SetupSettings.HOURS[hour.coerceIn(0, 23)].words, SetupSettings.HOURS, null, Modifier.clickable {
                    pickChoice(context, SetupSettings.HOURS, hour.toString()) { v -> val was = hour; hour = v.toInt(); save(JSONObject().put("hour", hour), SetupSettings.hourSaid(hour)) { hour = was } }
                })
            }
        }
        Block {
            SetupRow("Add the genre picks automatically", Modifier, Modifier.clickable { info("Favourites the five adjacent picks in your Qobuz account as soon as they are chosen. They only become playable from the library after your next scan. The stretch pick is never added automatically. Off by default.") }) {
                Toggle(auto, Modifier.clickable { val was = auto; auto = !auto; save(JSONObject().put("auto_add", auto), if (auto) "Picks will be added to Qobuz" else "Picks won't be added") { auto = was } }, known)
            }
            if (!ready) SetupNote("Qobuz isn't usable on your server right now, so picks can't be found or played.", Modifier)
        }
        Block {
            SetupRow("Rebuild today's picks now", Modifier, null) {
                SetupButton(if (rebuilding) "…" else "Rebuild", rebuilding, Modifier.clickable(enabled = !rebuilding) {
                    val c = client() ?: return@clickable
                    rebuilding = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { c.rebuildPicks() } }
                        rebuilding = false
                        setupToast(context, r.fold({ "Rebuilding today's picks — check back in a few minutes" }, { "Couldn't rebuild: " + Failure.said(it, "no answer") }))
                    }
                })
            }
        }
    }
}

// ---- Wall display ------------------------------------------------------------------------------------------------

@Composable
private fun DisplayPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("display") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var seconds by remember { mutableStateOf(kept?.optInt("seconds", 10) ?: 10) }
    var known by remember { mutableStateOf(kept != null) }
    LaunchedEffect(Unit) { readSetting(client, "display")?.let { on = it.optBoolean("enabled"); seconds = it.optInt("seconds", seconds); known = true } }
    fun save() = saveSetting(scope, context, client, "display", JSONObject().put("enabled", on).put("seconds", seconds), null, undo = {}) { j ->
        if (!j.optBoolean("ok")) setupToast(context, "Display settings didn't persist — check the data volume")
    }
    SettingsFrame {
        PaneHead("Wall display", onBack)
        Block {
            SetupRow("Wall display", Modifier, Modifier.clickable { info("An always-on now-playing screen for a spare tablet or TV, at /display on the server. It shows the cover, the track, the album's details and, with a YouTube key, the track's video.") }) {
                Toggle(on, Modifier.clickable { on = !on; save() }, known)
            }
            SetupRow("Rotate every ${seconds}s", Modifier, null) {
                val choices = listOf(5, 10, 15, 20, 30, 45, 60).map { Choice("$it", "$it s") }
                SelectBox("$seconds s", choices, Modifier, Modifier.clickable { pickChoice(context, choices, "$seconds") { seconds = it.toInt(); save() } })
            }
            SetupNote("Open it on the screen: " + (client()?.address?.baseUrl ?: "the server") + "/display", Modifier)
        }
        ShelfBlock(client, info)   // SettingsShelf.kt
    }
}

// ---- Appearance --------------------------------------------------------------------------------------------------

private val UI_NAMES = listOf("Album & artist text", "Grid screen title", "Menu & Home Screen text", "Grid layout", "Tile size")
private val UI_INFO = listOf(
    "The size of the album name and artist name under every tile.",
    "The size of the title at the top of a grid screen.",
    "The size of the side menu, Home's greeting and row headings.",
    "How album grids are laid out. Auto fits as many columns as the screen suits; 3 or 2 columns fixes the count; List shows one row per album.",
    "The size of album tiles on every screen.")

@Composable
private fun AppearancePane(onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(pagePrefs(context)) }
    SettingsFrame {
        PaneHead("Appearance", onBack)
        Block {
            SetupRow("Colours", Modifier, null) {}
            SetupNote("Mallard wears Mandarin's graphite and brass. The server page's themes are its own.", Modifier)
        }
        SettingsDivider()
        for ((i, key) in listOf("text", "title", "menu", "layout", "tile").withIndex()) {
            val choices = when (key) { "layout" -> SetupSettings.LAYOUT; "tile" -> SetupSettings.TILE; else -> SetupSettings.TEXT }
            val value = SetupSettings.uiValue(prefs, key)
            Block {
                SetupRow(UI_NAMES[i], Modifier, Modifier.clickable { info(UI_INFO[i]) }) {
                    SelectBox(choiceWords(choices, value), choices, Modifier, Modifier.clickable {
                        pickChoice(context, choices, value) { v -> prefs = setPagePref(context, key, v) }
                    }, width = 142.5.dp)
                }
            }
        }
        SetupNote("Saved on this phone only.", Modifier.padding(bottom = 12.dp))
    }
}
