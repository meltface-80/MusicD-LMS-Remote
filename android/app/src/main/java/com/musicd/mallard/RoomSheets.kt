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
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Play on's two sheets natively, drawn as Mandarin's (group and device-power), against Lyrion's own model: a group is
 * LMS's sync group (one player's queue heard on several), and power is each player's own on/off. And the question asked
 * when a player is chosen while the one left is playing.
 */

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** A sheet's button along the foot (.action-btn): a pill 40 tall, 13px; [primary] in brass, the rest on the raised ground with an edge. */
private val FOOT_PAD = PaddingValues(horizontal = 8.dp)

private data class Other(val id: String, val name: String)

/**
 * Group players with [zoneId]: a row for every other player, ticked when it is in [zoneId]'s sync group (it plays what
 * [zoneId] plays). Apply joins the newly ticked to [zoneId]'s group and takes the unticked out of it, one LMS call each;
 * [zoneId] itself keeps playing. [scope] outlives the sheet. [onSettled] is told the player the app follows (this one).
 */
@Composable
internal fun GroupSheet(zoneId: String, client: () -> ServerClient?, scope: CoroutineScope, onClose: () -> Unit, onSettled: (String) -> Unit) {
    val context = LocalContext.current
    var others by remember { mutableStateOf<List<Other>?>(null) }
    var members by remember { mutableStateOf(emptySet<String>()) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(zoneId) {
        val c = client()
        val j = if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.playerSettings(zoneId) }.getOrNull() }
        val sync = j?.optJSONObject("sync")
        val o = sync?.optJSONArray("others")
        others = if (o == null) emptyList() else (0 until o.length()).mapNotNull { o.optJSONObject(it) }.map { Other(it.optString("id"), it.optString("name")) }.filter { it.id.isNotEmpty() }
        val m = sync?.optJSONArray("members")
        members = if (m == null) emptySet() else (0 until m.length()).map { m.optString(it) }.filter { it.isNotEmpty() }.toSet()
        picked = members
    }
    val list = others ?: return
    SheetPanel(onClose, head = {
        SheetTitle("Group players", Modifier.parity("group.title"))
        SheetClose(Modifier.parity("group.close", Contract.label("group.close")).clip(CircleShape).clickable(onClick = onClose))
    }, foot = {
        val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, ButtonDefaults.shape)
        Button(onClick = onClose, enabled = !busy, colors = footColors(false), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("group.cancel", Contract.label("group.cancel")).weight(1f).defaultMinSize(minHeight = 40.dp).then(edge)) {
            Text(Contract.label("group.cancel"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
        Button(onClick = {
            val join = picked - members
            val leave = members - picked
            val c = client()
            if ((join.isEmpty() && leave.isEmpty()) || c == null) onClose() else scope.launch {
                busy = true
                val failed = withContext(Dispatchers.IO) {
                    runCatching {
                        for (id in leave) c.playerSync(id, null)
                        for (id in join) c.playerSync(id, zoneId)
                    }.exceptionOrNull()
                }
                busy = false
                if (failed != null) { toast(context, failed.message ?: "Couldn't change the group"); return@launch }
                toast(context, "Players updated")
                onClose()
                onSettled(zoneId)
            }
        }, enabled = !busy, colors = footColors(true), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("group.apply", Contract.label("group.apply")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("group.apply"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        if (list.isEmpty()) { SheetNote(Modifier.parity("group.empty"), "There are no other players to group with."); return@SheetPanel }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in list) {
                val on = o.id in picked
                GroupRow(Modifier.parity("group.row", Contract.label("group.row", 0, o.name)).clip(ROW).clickable(enabled = !busy) {
                    picked = if (on) picked - o.id else picked + o.id
                }, o.name, on, if (o.id in members) "In this group" else "")
            }
        }
        SheetNote(Modifier.parity("group.limit"), "Grouped players play the same music in sync. Each keeps its own volume.")
    }
}

private val ROW = RoundedCornerShape(10.dp)

/** A player's row (.group-row): its box (✓ when ticked), its name, the line under it; ticked, in brass on the soft brass with a brass edge. */
@Composable
private fun GroupRow(modifier: Modifier, name: String, on: Boolean, note: String) {
    val ink = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().background(if (on) paletteColor(Palette.ACCENT_SOFT) else Color.Transparent)
        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, ROW).padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).border(1.5.dp, if (on) ink else paletteColor(Palette.TEXT_FAINT), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text("✓", color = if (on) ink else Color.Transparent, fontSize = 13.sp, lineHeight = 13.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = ink, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private data class PowerRow(val id: String, val name: String, val on: Boolean?)

/**
 * Player power: each player with its power state (LMS's own), Power lit while on; a tap switches it, and the list is read
 * again a moment later, so the states are the players', not what was asked.
 */
@Composable
internal fun PowerSheet(client: () -> ServerClient?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<PowerRow>?>(null) }
    var reads by remember { mutableStateOf(0) }
    LaunchedEffect(reads) {
        val c = client()
        all = (if (c == null) null else withContext(Dispatchers.IO) {
            runCatching {
                c.rooms().map { r -> PowerRow(r.id, r.name, runCatching { c.playerSettings(r.id) }.getOrNull()?.let { j -> if (j.isNull("power")) null else j.optBoolean("power") }) }
            }.getOrNull()
        }) ?: all ?: emptyList()
    }
    val list = all ?: return
    fun act(block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { block(c) }.exceptionOrNull() }
            if (failed != null) toast(context, failed.message ?: "Couldn't reach the server")
            delay(600); reads++
        }
    }
    SheetPanel(onClose, head = {
        SheetTitle("Player power", Modifier.parity("device-power.title"))
        SheetClose(Modifier.parity("device-power.close", Contract.label("device-power.close")).clip(CircleShape).clickable(onClick = onClose))
    }, foot = {
        Button(onClick = onClose, colors = footColors(true), contentPadding = FOOT_PAD,
            modifier = Modifier.parity("device-power.done", Contract.label("device-power.done")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("device-power.done"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        if (list.isEmpty()) { SheetNote(Modifier.parity("device-power.empty"), "No players found."); return@SheetPanel }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in list) {
                val dev = RoundedCornerShape(14.dp)
                Row(Modifier.parity("device-power.control").fillMaxWidth().clip(dev).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, MaterialTheme.colorScheme.outline, dev)
                    .padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (p.on) { true -> "On"; false -> "Off"; null -> "Unknown" }, Modifier.padding(top = 2.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    val awake = p.on == true
                    DevButton(Modifier.parity("device-power.standby", Contract.label("device-power.standby", if (awake) 1 else 0, p.name)).clip(CircleShape)
                        .clickable { act { it.power(p.id, !awake) } }, "Power", awake)
                }
            }
        }
    }
}

/** A player's button (.dev-btn): a pill 36 tall, 13px, dim on an edge; lit (the player on) in brass on the soft brass. */
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
        toName = names[to] ?: "this player"
        question = "Move what's playing in ${names[from] ?: "the other player"} to $toName?"
    }
    val q = question ?: return
    val (from, to) = ask ?: return
    DisposableEffect(Unit) { RoomAsk.drawn.value = true; onDispose { RoomAsk.drawn.value = false } }
    ConfirmDialog(q) { yes ->
        question = null; RoomAsk.asked.value = null
        val c = client()
        if (yes && c != null) scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { c.transferZone(from, to) }.exceptionOrNull() }
            toast(context, if (failed == null) "Moved to $toName" else failed.message ?: "Couldn't move the music")
        }
    }
}
