package com.musicd.mallard

import android.content.Intent
import android.net.Uri
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
import com.musicd.mallard.client.Room
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
            "playback" -> PlaybackPane(client, onBack, showInfo)
            "player" -> PlayerPane(client, onBack, showInfo)
            "labels" -> LabelsPane(client, onBack, showInfo)
            "metadata" -> MetadataPane(client, onBack, showInfo)
            "homescreen" -> HomeScreenPane(client, onBack)
            "smartpicks" -> PicksPane(client, onBack, showInfo)
            "lms" -> LmsPane(client, onBack, showInfo)
            "display" -> DisplayPane(client, onBack, showInfo)
            "shelf", "away" -> MorePane(pane, client, onBack, showInfo)   // SettingsShelf.kt, SettingsAway.kt
            "appearance" -> AppearancePane(onBack, showInfo)
            "system" -> SystemPane(client, onBack)
            else -> ConnectionPane(client, onBack)
        }
        info?.let { InfoTip(it, Modifier) }
    }
}

/** A pane's head: Back in the brass disc, its name in the serif. */
@Composable
internal fun PaneHead(title: String, onBack: () -> Unit) {
    SettingsHead(Modifier.clip(CircleShape).clickable(onClick = onBack), R.drawable.np_back, title, Modifier)
}

/** The players, as the server lists them; none until it answers. */
@Composable
private fun rememberRooms(client: () -> ServerClient?): List<Room> {
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.rooms() }.getOrNull() }?.let { rooms = it }
    }
    return rooms
}

// ---- Playback ----------------------------------------------------------------------------------------------------

@Composable
private fun PlaybackPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms = rememberRooms(client)
    var zone by remember { mutableStateOf(NativeShell.zone.ifEmpty { Store.zone(context).orEmpty() }) }
    var radio by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf(false) }
    LaunchedEffect(zone) {
        val c = client() ?: return@LaunchedEffect
        if (zone.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.getJson("/api/radio?zone=" + Uri.encode(zone)) }.getOrNull() }?.let { radio = it.optBoolean("enabled"); known = true }
    }
    SettingsFrame {
        PaneHead("Playback", onBack)
        Block {
            val choices = rooms.map { Choice(it.id, it.name) }
            SetupRow("Player", Modifier, Modifier.clickable { info("Choose which Lyrion player Play, Queue and the transport controls send to.") }) {
                SelectBox(choiceWords(choices, zone).ifEmpty { "None" }, choices, Modifier, Modifier.clickable {
                    if (choices.isNotEmpty()) pickChoice(context, choices, zone) { v -> zone = v; NativeShell.roomChosen(v) }
                })
            }
        }
        SettingsDivider()
        Block {
            SetupRow("Random album radio", Modifier, Modifier.clickable { info("When this player's queue runs out, keep it going by queueing whole random albums from your library rather than shuffling single tracks. It stands down on its own while LMS's own Don't Stop The Music is on for the player, so the two never fight over the queue. Per player — it follows the player chosen above.") }) {
                Toggle(radio, Modifier.clickable(enabled = zone.isNotEmpty()) {
                    val c = client() ?: return@clickable
                    val was = radio; radio = !radio
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { c.radio(zone, radio) } }
                        r.onFailure { radio = was; setupToast(context, Failure.said(it, "Couldn't change radio")) }
                    }
                }, known)
            }
            SetupNote("Applies to the player chosen above.", Modifier)
        }
    }
}

// ---- Player settings ---------------------------------------------------------------------------------------------

/** One of a player's prefs as a list: its name, the help, its choices; saved as LMS's own value. */
private class PrefRow(val pref: String, val name: String, val info: String, val choices: List<Choice>)

private val PREF_ROWS = listOf(
    PrefRow("transitionType", "Crossfade", "How one track blends into the next.",
        listOf(Choice("0", "None"), Choice("1", "Crossfade"), Choice("2", "Fade in"), Choice("3", "Fade out"), Choice("4", "Fade in & out"))),
    PrefRow("transitionDuration", "Crossfade seconds", "Length of the crossfade or fade.", (0..10).map { Choice("$it", "$it s") }),
    PrefRow("transitionSmart", "Smart crossfade", "Skip the crossfade between consecutive tracks of the same album (gapless stays gapless).",
        listOf(Choice("0", "Off"), Choice("1", "On"))),
    PrefRow("replayGainMode", "Volume levelling", "Use ReplayGain tags to even out loudness between tracks or albums.",
        listOf(Choice("0", "Off"), Choice("1", "Track gain"), Choice("2", "Album gain"), Choice("3", "Smart gain"))),
    PrefRow("digitalVolumeControl", "Volume control", "Fix the output at 100% if your amplifier or DAC controls loudness, or you need perfect digital passthrough.",
        listOf(Choice("0", "Fixed at 100%"), Choice("1", "Volume adjusts outputs"))),
    PrefRow("powerOnResume", "On power on", "What playback does when the player is switched off and back on.",
        listOf(Choice("PauseOff-PlayOn", "Pause · resume"), Choice("PauseOff-NoneOn", "Pause · stay paused"), Choice("StopOff-PlayOn", "Stop · play"),
            Choice("StopOff-NoneOn", "Stop · do nothing"), Choice("StopOff-ResetPlayOn", "Reset · play"), Choice("StopOff-ResetOn", "Reset · do nothing"))),
    PrefRow("syncVolume", "Sync volume", "Volume changes apply to the whole sync group.", listOf(Choice("0", "Off"), Choice("1", "On"))),
    PrefRow("syncPower", "Sync power", "Power state follows the sync group.", listOf(Choice("0", "Off"), Choice("1", "On"))))

@Composable
private fun PlayerPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms = rememberRooms(client)
    var player by remember { mutableStateOf(NativeShell.zone.ifEmpty { Store.zone(context).orEmpty() }) }
    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var reads by remember { mutableStateOf(0) }
    LaunchedEffect(player, reads) {
        val c = client() ?: return@LaunchedEffect
        if (player.isEmpty()) return@LaunchedEffect
        settings = withContext(Dispatchers.IO) { runCatching { c.playerSettings(player) }.getOrNull() }
    }
    fun act(said: String, block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
            setupToast(context, r.fold({ said }, { Failure.said(it, "Couldn't save") }))
            reads++
        }
    }
    SettingsFrame {
        PaneHead("Player settings", onBack)
        Block {
            val choices = rooms.map { Choice(it.id, it.name) }
            SetupRow("Player", Modifier, null) {
                SelectBox(choiceWords(choices, player).ifEmpty { "None" }, choices, Modifier, Modifier.clickable {
                    if (choices.isNotEmpty()) pickChoice(context, choices, player) { v -> player = v; settings = null }
                })
            }
            settings?.optString("model")?.takeIf { it.isNotEmpty() }?.let { SetupNote(it, Modifier) }
        }
        val s = settings ?: return@SettingsFrame
        SettingsDivider()
        Block {
            val power = if (s.isNull("power")) null else s.optBoolean("power")
            SetupRow("Power", Modifier, null) {
                Toggle(power == true, Modifier.clickable { act(if (power == true) "Switched off" else "Switched on") { it.power(player, power != true) } }, power != null)
            }
            val modes = s.optJSONObject("modes")
            if (modes != null) {
                val shuffles = listOf(Choice("0", "Off"), Choice("1", "By song"), Choice("2", "By album"))
                SetupRow("Shuffle", Modifier, Modifier.clickable { info("Shuffle mode for this player's queue.") }) {
                    val v = modes.optInt("shuffle", 0).toString()
                    SelectBox(choiceWords(shuffles, v), shuffles, Modifier, Modifier.clickable {
                        pickChoice(context, shuffles, v) { n -> act("Saved ✓") { it.zoneSettings(player, shuffleMode = n.toInt()) } }
                    })
                }
                val repeats = listOf(Choice("0", "Off"), Choice("1", "One song"), Choice("2", "All"))
                SetupRow("Repeat", Modifier, Modifier.clickable { info("Repeat mode for this player's queue.") }) {
                    val v = modes.optInt("repeat", 0).toString()
                    SelectBox(choiceWords(repeats, v), repeats, Modifier, Modifier.clickable {
                        pickChoice(context, repeats, v) { n -> act("Saved ✓") { it.zoneSettings(player, loop = when (n) { "2" -> "loop"; "1" -> "loop_one"; else -> "disabled" }) } }
                    })
                }
            }
            val dstm = s.optJSONObject("dstm")
            val opts = dstm?.optJSONArray("options")
            if (opts != null && opts.length() > 0) {
                val choices = (0 until opts.length()).mapNotNull { opts.optJSONObject(it) }.map { Choice(it.optString("key"), it.optString("text")) }
                val v = dstm.optString("current", "0")
                SetupRow("Don't Stop The Music", Modifier, Modifier.clickable { info("When the queue runs out, LMS keeps playing using the mix chosen here. This is LMS's own feature.") }) {
                    SelectBox(choiceWords(choices, v), choices, Modifier, Modifier.clickable {
                        pickChoice(context, choices, v) { n -> act("Saved ✓") { it.playerDstm(player, n) } }
                    }, width = 170.dp)
                }
            }
        }
        val prefs = s.optJSONObject("prefs") ?: JSONObject()
        val shown = PREF_ROWS.filter { prefs.has(it.pref) && !prefs.isNull(it.pref) }
        if (shown.isNotEmpty()) {
            SettingsDivider()
            Block {
                for (row in shown) {
                    val v = prefs.optString(row.pref)
                    SetupRow(row.name, Modifier, Modifier.clickable { info(row.info) }) {
                        SelectBox(choiceWords(row.choices, v), row.choices, Modifier, Modifier.clickable {
                            pickChoice(context, row.choices, v) { n -> act("Saved ✓") { it.playerPref(player, row.pref, n) } }
                        }, width = 170.dp)
                    }
                }
            }
        }
        val sync = s.optJSONObject("sync")
        val others = sync?.optJSONArray("others")
        if (others != null && others.length() > 0) {
            SettingsDivider()
            Block {
                val choices = listOf(Choice("", "Not synced")) + (0 until others.length()).mapNotNull { others.optJSONObject(it) }.map { Choice(it.optString("id"), it.optString("name")) }
                val members = sync.optJSONArray("members")
                val v = members?.optString(0).orEmpty()
                SetupRow("Sync with", Modifier, Modifier.clickable { info("Group this player with another for synchronous playback.") }) {
                    SelectBox(choiceWords(choices, v), choices, Modifier, Modifier.clickable {
                        pickChoice(context, choices, v) { n -> act(if (n.isEmpty()) "Unsynced ✓" else "Synced ✓") { it.playerSync(player, n.ifEmpty { null }) } }
                    }, width = 170.dp)
                }
            }
        }
    }
}

// ---- Labels ------------------------------------------------------------------------------------------------------

@Composable
private fun LabelsPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("labels") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var count by remember { mutableStateOf(kept?.optInt("count", 0) ?: 0) }
    var known by remember { mutableStateOf(kept != null) }
    LaunchedEffect(Unit) { readSetting(client, "labels")?.let { on = it.optBoolean("enabled"); count = it.optInt("count", 0); known = true } }
    SettingsFrame {
        PaneHead("Labels", onBack)
        Block {
            SetupRow("Record labels", Modifier, Modifier.clickable { info("Off by default. When on, the server works out each album's record label (from your files' tags and online sources) for Label of the week on Home and for search. When off it does no label work at all.") }) {
                Toggle(on, Modifier.clickable {
                    val was = on; on = !on
                    saveSetting(scope, context, client, "labels", JSONObject().put("enabled", on), if (on) "Labels on — the first scan runs in the background" else "Labels off", undo = { on = was })
                }, known)
            }
            SetupNote(if (on && count > 0) "$count labels found so far." else "The label list, its logos and merges are managed on the server page.", Modifier)
        }
    }
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

// ---- Mallard -----------------------------------------------------------------------------------------------------

@Composable
private fun ConnectionPane(client: () -> ServerClient?, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsFrame {
        PaneHead("Mallard", onBack)
        Block {
            SetupRow("Server", Modifier, null) {
                SetupButton("Change", false, Modifier.clickable {
                    context.startActivity(Intent(context, ConnectActivity::class.java))
                })
            }
            SetupNote(client()?.address?.toString() ?: "None chosen", Modifier)
        }
        SettingsDivider()
        Block {
            SetupRow("Mallard", Modifier, null) {}
            SetupNote("Version ${BuildConfig.VERSION_NAME} — the native Android remote for MusicD LMS Remote, built on Mandarin Remote.", Modifier)
        }
    }
}
