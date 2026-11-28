package com.musicd.mallard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AudioDevices
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Settings → Audio Devices → a player (Mandarin's device page, owner request): its name at the head; On (Lyrion's power,
 * not for a phone's own player); random album radio; its name, renamed in Lyrion; About; then Lyrion's own settings for
 * it — shuffle, repeat, Don't Stop The Music, crossfade, volume levelling, volume control, on power on, sync. What was
 * Settings → Playback and → Player settings, for one player at a time. Read again every ten seconds, and after each change.
 */

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
internal fun DevicePane(id: String, client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var radio by remember { mutableStateOf<Boolean?>(null) }
    val phone = id.startsWith("02:6d:", ignoreCase = true)
    val thisPhone = id == PhoneZone.ownId(context)
    LaunchedEffect(reads) {
        while (true) {
            untilOnScreen()
            val c = client()
            if (c != null) withContext(Dispatchers.IO) {
                runCatching { c.playerSettings(id) }.fold({ settings = it; failed = null }, { failed = Failure.said(it, "Lyrion didn't answer for this player") })
                runCatching { c.getJson("/api/radio?zone=" + Uri.encode(id)) }.getOrNull()?.let { radio = it.optBoolean("enabled") }
            }
            delay(10_000)
        }
    }
    fun act(said: String, block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
            setupToast(context, r.fold({ said }, { Failure.said(it, "Couldn't save") }))
            reads++
        }
    }
    val s = settings
    val name = if (thisPhone) "This phone" else s?.optString("name")?.takeIf { it.isNotEmpty() } ?: "Device"
    SettingsFrame {
        PaneHead(name, onBack)
        if (s == null) { SetupNote(failed ?: "Asking Lyrion…", Modifier); return@SettingsFrame }
        Block {
            val power = if (s.isNull("power")) null else s.optBoolean("power")
            if (!phone && power != null) SetupRow(if (power) "On" else "Off", Modifier, null) {
                Toggle(power, Modifier.clickable { act(if (power) "Switched off" else "Switched on") { it.power(id, !power) } })
            }
            SetupRow("Random album radio", Modifier, Modifier.clickable { info(AudioDevices.RADIO_INFO) }) {
                Toggle(radio == true, Modifier.clickable(enabled = radio != null) {
                    val on = radio != true
                    radio = on
                    act(if (on) "Random album radio on" else "Random album radio off") { it.radio(id, on) }
                }, radio != null)
            }
            if (thisPhone) SetupNote("This phone's own player: Lyrion has it fixed at 100% and its volume is the phone's.", Modifier)
        }
        if (thisPhone) {
            // This phone's own settings, kept on the phone (owner request: on its own page, not in Away from home).
            SettingsDivider(18, 18)
            Block { DeviceTitle("Streaming"); PhoneStreamRows() }   // SettingsCache.kt
        }
        SettingsDivider(18, 18)
        Block {
            DeviceTitle("Name")
            // Lyrion answers no name for a phone's player it isn't connected to (it plays only while chosen): say what it will be.
            if (phone) SetupNote(s.optString("name").takeIf { thisPhone && it.isNotEmpty() }?.let { "Lyrion knows it as “$it”." }
                ?: "A phone's own player, named after the phone (${android.os.Build.MODEL}) while it plays.".takeIf { thisPhone } ?: "A phone's own player, named after the phone.", Modifier)
            else RenameField(s.optString("name")) { n -> act("Renamed ✓") { it.renamePlayer(id, n) } }
        }
        SettingsDivider(18, 18)
        Block {
            DeviceTitle("About")
            for ((k, v) in listOf("Model" to s.optString("model"), "Player id" to id)) if (v.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(k, color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.5.sp)
                    Text(v, Modifier.weight(1f), color = paletteColor(Palette.TEXT), fontSize = 13.5.sp, textAlign = TextAlign.End)
                }
            }
        }
        PlayerSettings(id, s, info) { said, block -> act(said, block) }
    }
}

/** Lyrion's own settings for the player: its play modes, Don't Stop The Music, its prefs, sync. */
@Composable
private fun PlayerSettings(id: String, s: JSONObject, info: (String) -> Unit, act: (String, (ServerClient) -> Unit) -> Unit) {
    val context = LocalContext.current
    SettingsDivider(18, 18)
    Block {
        DeviceTitle("Playback")
        s.optJSONObject("modes")?.let { modes ->
            val shuffles = listOf(Choice("0", "Off"), Choice("1", "By song"), Choice("2", "By album"))
            val sv = modes.optInt("shuffle", 0).toString()
            SetupRow("Shuffle", Modifier, Modifier.clickable { info("Shuffle mode for this player's queue.") }) {
                SelectBox(choiceWords(shuffles, sv), shuffles, Modifier, Modifier.clickable {
                    pickChoice(context, shuffles, sv) { n -> act("Saved ✓") { it.zoneSettings(id, shuffleMode = n.toInt()) } }
                })
            }
            val repeats = listOf(Choice("0", "Off"), Choice("1", "One song"), Choice("2", "All"))
            val rv = modes.optInt("repeat", 0).toString()
            SetupRow("Repeat", Modifier, Modifier.clickable { info("Repeat mode for this player's queue.") }) {
                SelectBox(choiceWords(repeats, rv), repeats, Modifier, Modifier.clickable {
                    pickChoice(context, repeats, rv) { n -> act("Saved ✓") { it.zoneSettings(id, loop = when (n) { "2" -> "loop"; "1" -> "loop_one"; else -> "disabled" }) } }
                })
            }
        }
        val dstm = s.optJSONObject("dstm")
        val opts = dstm?.optJSONArray("options")
        if (dstm != null && opts != null && opts.length() > 0) {
            val choices = (0 until opts.length()).mapNotNull { opts.optJSONObject(it) }.map { Choice(it.optString("key"), it.optString("text")) }
            val v = dstm.optString("current", "0")
            SetupRow("Don't Stop The Music", Modifier, Modifier.clickable { info("When the queue runs out, Lyrion keeps playing using the mix chosen here. This is Lyrion's own feature.") }) {
                SelectBox(choiceWords(choices, v), choices, Modifier, Modifier.clickable {
                    pickChoice(context, choices, v) { n -> act("Saved ✓") { it.playerDstm(id, n) } }
                }, width = 170.dp)
            }
        }
        val prefs = s.optJSONObject("prefs") ?: JSONObject()
        for (row in PREF_ROWS.filter { prefs.has(it.pref) && !prefs.isNull(it.pref) }) {
            val v = prefs.optString(row.pref)
            SetupRow(row.name, Modifier, Modifier.clickable { info(row.info) }) {
                SelectBox(choiceWords(row.choices, v), row.choices, Modifier, Modifier.clickable {
                    pickChoice(context, row.choices, v) { n -> act("Saved ✓") { it.playerPref(id, row.pref, n) } }
                }, width = 170.dp)
            }
        }
    }
    val sync = s.optJSONObject("sync")
    val others = sync?.optJSONArray("others")
    if (sync != null && others != null && others.length() > 0) {
        SettingsDivider(18, 18)
        Block {
            val choices = listOf(Choice("", "Not synced")) + (0 until others.length()).mapNotNull { others.optJSONObject(it) }.map { Choice(it.optString("id"), it.optString("name")) }
            val v = sync.optJSONArray("members")?.optString(0).orEmpty()
            SetupRow("Sync with", Modifier, Modifier.clickable { info("Group this player with another for synchronous playback.") }) {
                SelectBox(choiceWords(choices, v), choices, Modifier, Modifier.clickable {
                    pickChoice(context, choices, v) { n -> act(if (n.isEmpty()) "Unsynced ✓" else "Synced ✓") { it.playerSync(id, n.ifEmpty { null }) } }
                }, width = 170.dp)
            }
        }
    }
}

/** The player's name in Lyrion (Mandarin's rename box): saved when Done is pressed, if it changed. */
@Composable
private fun RenameField(now: String, onSave: (String) -> Unit) {
    var text by remember(now) { mutableStateOf(now) }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(text, { text = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 16.sp), cursorBrush = SolidColor(paletteColor(Palette.ACCENT)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { val n = text.trim(); if (n.isNotEmpty() && n != now) onSave(n) }))
    }
    SetupNote("Press Done to rename it in Lyrion.", Modifier)
}
