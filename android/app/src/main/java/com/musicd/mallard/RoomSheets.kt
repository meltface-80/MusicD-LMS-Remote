package com.musicd.mallard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.DevicePower
import com.musicd.mallard.client.Grouping
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.RoomMove
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Play on's two sheets natively (the Rooms group of old/plan-android-native.md, R1; bug list item 68), drawn as the
 * page's (docs/specs/screens.json, group and device-power; zone-grouping.js, device-power.js, style.css .group-row, .dev-row),
 * and the question the page asks when a room is chosen while the one left is playing (album-grid.js), asked by the app now.
 */

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** A sheet's button along the foot (.action-btn): a pill 40 tall, 13px; [primary] in brass, the rest on the raised ground with an edge. */
private val FOOT_PAD = PaddingValues(horizontal = 8.dp)

/**
 * Group zones around [zoneId]'s room: a row for each speaker its first may play with, ticked when in the room (the first
 * always, said to keep playing: its queue is the one kept), another room's said; Apply splits off what was unticked, then
 * groups the rest, and the app follows the room that holds the first speaker once Roon has made it ([onSettled]), on
 * [scope], which outlives the sheet.
 */
@Composable
internal fun GroupSheet(zoneId: String, client: () -> ServerClient?, scope: CoroutineScope, onClose: () -> Unit, onSettled: (String) -> Unit) {
    val context = LocalContext.current
    var grouping by remember { mutableStateOf<Grouping?>(null) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(zoneId) {
        val c = client()
        val all = if (c == null) emptyList() else withContext(Dispatchers.IO) { runCatching { c.outputs() }.getOrNull() } ?: emptyList()
        val g = Grouping(all, zoneId)
        picked = g.anchorIds.toSet(); grouping = g
    }
    val g = grouping ?: return
    SheetPanel(onClose, head = {
        SheetTitle("Group zones", Modifier.parity("group.title"))
        SheetClose(Modifier.parity("group.close", Contract.label("group.close")).clip(CircleShape).clickable(onClick = onClose))
    }, foot = {
        val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, ButtonDefaults.shape)
        Button(onClick = onClose, enabled = !busy, colors = footColors(false), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("group.cancel", Contract.label("group.cancel")).weight(1f).defaultMinSize(minHeight = 40.dp).then(edge)) {
            Text(Contract.label("group.cancel"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
        Button(onClick = {
            val change = g.change(picked)
            val c = client()
            if (change == null || c == null) onClose() else scope.launch {
                busy = true
                // Split first, then group: Roon takes a group's whole set in one call, the first speaker's queue kept.
                val failed = withContext(Dispatchers.IO) {
                    runCatching { if (change.ungroup.isNotEmpty()) c.ungroupOutputs(change.ungroup); if (change.group.isNotEmpty()) c.groupOutputs(change.group) }.exceptionOrNull()
                }
                busy = false
                if (failed != null) { toast(context, failed.message ?: "Could not change grouping"); return@launch }
                toast(context, "Zones updated")
                onClose()
                settle(c, g.primary?.id ?: return@launch, change.want)?.let(onSettled)
            }
        }, enabled = !busy, colors = footColors(true), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("group.apply", Contract.label("group.apply")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("group.apply"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        val empty = g.emptyNote
        if (empty != null) { SheetNote(Modifier.parity("group.empty"), empty); return@SheetPanel }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in g.offerable) {
                val first = o.id == g.primary?.id
                val on = o.id in picked
                GroupRow(Modifier.parity("group.row", Contract.label("group.row", 0, o.name)).clip(ROW).clickable(enabled = !first && !busy) {
                    picked = if (on) picked - o.id else picked + o.id
                }, o, on, g.note(o))
            }
        }
        g.limitNote?.let { SheetNote(Modifier.parity("group.limit"), it) }
    }
}

private val ROW = RoundedCornerShape(10.dp)

/** A speaker's row (.group-row): its box (✓ when ticked), its name, the line under it; ticked, in brass on the soft brass with a brass edge. */
@Composable
private fun GroupRow(modifier: Modifier, o: Speaker, on: Boolean, note: String) {
    val ink = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().background(if (on) paletteColor(Palette.ACCENT_SOFT) else Color.Transparent)
        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, ROW).padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).border(1.5.dp, if (on) ink else paletteColor(Palette.TEXT_FAINT), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text("✓", color = if (on) ink else Color.Transparent, fontSize = 13.sp, lineHeight = 13.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(o.name, color = ink, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotEmpty()) Text(note, Modifier.parity("group.row-note").padding(top = 2.dp).alpha(if (on) 0.8f else 1f),
                color = if (on) ink else paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/** The sheet's small print (.lib-sheet-note): 11.5px, faint, 16 under what is above it. */
@Composable
private fun SheetNote(modifier: Modifier, text: String) {
    Text(text, modifier.padding(top = 16.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 11.5.sp, lineHeight = 17.25.sp)
}

/**
 * Roon mints a room for each set of speakers, so the room grouped is a new one: its id read until it holds [want] (or two
 * seconds have gone), as the page's settleZoneAfterGrouping, so the app is pointed at it rather than at a room gone.
 */
private suspend fun settle(c: ServerClient, anchor: String, want: Set<String>): String? {
    var found: String? = null
    for (attempt in 0 until 8) {
        delay(250)
        val got = withContext(Dispatchers.IO) { runCatching { Grouping.settled(c.getJson("/api/zones"), anchor, want) }.getOrNull() } ?: continue
        found = got.first
        if (got.second) break
    }
    return found
}

/**
 * Device power: each device with power controls as a section, each control with its state, Power (lit while on) and
 * Select input, and the whole device into standby where it has more than one; what it says with none; Done. Each tap
 * acts at once and the list is read again a moment later, so the states are the device's, not what was asked.
 */
@Composable
internal fun PowerSheet(client: () -> ServerClient?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Speaker>?>(null) }
    var reads by remember { mutableStateOf(0) }
    LaunchedEffect(reads) {
        val c = client()
        all = (if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.outputs() }.getOrNull() }) ?: all ?: emptyList()
    }
    val list = all ?: return
    fun act(block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { block(c) }.exceptionOrNull() }
            if (failed != null) toast(context, failed.message ?: "Could not reach the extension")
            delay(400); reads++
        }
    }
    SheetPanel(onClose, head = {
        SheetTitle("Device power", Modifier.parity("device-power.title"))
        SheetClose(Modifier.parity("device-power.close", Contract.label("device-power.close")).clip(CircleShape).clickable(onClick = onClose))
    }, foot = {
        Button(onClick = onClose, colors = footColors(true), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("device-power.done", Contract.label("device-power.done")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("device-power.done"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        val devices = DevicePower.withControls(list)
        if (devices.isEmpty()) { SheetNote(Modifier.parity("device-power.empty"), DevicePower.emptyNote(list)); return@SheetPanel }
        devices.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(color = Color(0x12FFFFFF))
            Text(o.name.uppercase(), Modifier.parity("device-power.device").padding(vertical = 13.dp, horizontal = 2.dp), color = paletteColor(Palette.TEXT_FAINT),
                fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.W700, letterSpacing = 0.14.em)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sc in o.controls) {
                    val dev = RoundedCornerShape(14.dp)
                    Row(Modifier.parity("device-power.control").fillMaxWidth().clip(dev).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, MaterialTheme.colorScheme.outline, dev)
                        .padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sc.statusLine.isNotEmpty()) Text(sc.statusLine, Modifier.padding(top = 2.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val awake = sc.status != "standby"
                            if (sc.standby) DevButton(Modifier.parity("device-power.standby", Contract.label("device-power.standby", if (awake) 1 else 0, sc.name)).clip(CircleShape)
                                .clickable { act { it.standby(o.id, sc.key, "toggle") } }, "Power", awake)
                            DevButton(Modifier.parity("device-power.select", Contract.label("device-power.select", 0, sc.name)).clip(CircleShape)
                                .clickable { act { it.convenienceSwitch(o.id, sc.key) } }, "Select input", false)
                        }
                    }
                }
                // Roon's standby with no control: every standby control of the device at once, only a distinct action where it has more than one.
                if (o.controls.count { it.standby } > 1) DevButton(Modifier.parity("device-power.all-off", Contract.label("device-power.all-off")).fillMaxWidth().clip(CircleShape)
                    .clickable { act { it.standby(o.id, null, "standby") } }, "Put whole device into standby", false)
            }
        }
    }
}

/** A device's button (.dev-btn): a pill 36 tall, 13px, dim on an edge; lit (the device on) in brass on the soft brass. */
@Composable
private fun DevButton(modifier: Modifier, word: String, lit: Boolean) {
    Box(modifier.defaultMinSize(minHeight = 36.dp).background(if (lit) paletteColor(Palette.ACCENT_SOFT) else Color.Transparent)
        .border(1.dp, if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
        Text(word, color = if (lit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/**
 * A room chosen on a native screen ([NativeShell.roomChosen]): the room it came from, and the one chosen, while the
 * question whether to move what's playing there is open ([RoomAskOver]).
 */
internal object RoomAsk {
    val asked = mutableStateOf<Pair<String, String>?>(null)
    /** The question drawn (for the log, item 121: asked, it is first checked, and drawn only if the room left is playing). */
    val drawn = mutableStateOf(false)
    fun chosen(from: String, to: String) { if (from.isNotEmpty() && to.isNotEmpty() && from != to) asked.value = from to to }
}

/**
 * The page's question when a room is chosen (album-grid.js), the app's own now: only when the room left is playing an
 * album, "Move what's playing in Kitchen to Study?"; Yes moves it there. Composed by the shell over every screen.
 */
@Composable
internal fun RoomAskOver(client: () -> ServerClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ask by RoomAsk.asked
    var question by remember { mutableStateOf<String?>(null) }
    var toName by remember { mutableStateOf("") }
    LaunchedEffect(ask) {
        question = null
        val (from, to) = ask ?: return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        val names = withContext(Dispatchers.IO) {
            runCatching { if (!c.playingAlbum(from)) null else c.rooms().associate { it.id to it.name } }.getOrNull()
        }
        if (names == null) { RoomAsk.asked.value = null; return@LaunchedEffect }
        toName = names[to] ?: "this zone"
        question = RoomMove.question(names[from] ?: "the other zone", toName)
    }
    val q = question ?: return
    val (from, to) = ask ?: return
    DisposableEffect(Unit) { RoomAsk.drawn.value = true; onDispose { RoomAsk.drawn.value = false } }
    ConfirmDialog(q) { yes ->
        question = null; RoomAsk.asked.value = null
        val c = client()
        if (yes && c != null) scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { c.transferZone(from, to) }.exceptionOrNull() }
            toast(context, if (failed == null) RoomMove.moved(toName) else failed.message ?: "Couldn't move the music")
        }
    }
}
