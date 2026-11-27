package com.musicd.mallard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Room
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.VolumeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Now playing's two menus under the transport, as the page's popovers have
 * them (docs/specs/screens.json, now-playing): Play on, and the volume sheet.
 * The mini player's are the same (mini-player), so each item carries both
 * screens' keys, and the buttons take each bar's size.
 */

/**
 * Play on: the rooms, the one playing here marked, each a tap to play there;
 * then Group zones and Device power, the app's own sheets over the screen
 * (RoomSheets.kt, item 68), and the all-room actions.
 */
@Composable
internal fun PlayOnMenu(zoneId: String, client: () -> ServerClient?, act: ((ServerClient) -> Unit) -> Unit, onRoom: (String) -> Unit,
                        size: Dp = 56.dp, iconSize: Dp = 32.dp) {
    var open by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    LaunchedEffect(open) {
        val c = client() ?: return@LaunchedEffect
        if (open) rooms = withContext(Dispatchers.IO) { runCatching { c.rooms() }.getOrNull() } ?: rooms
    }
    Box {
        IconButton(onClick = { open = !open }, modifier = Modifier.parity("now-playing.device mini-player.device", Contract.label("now-playing.device")).size(size)) {
            Icon(painterResource(R.drawable.np_device), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(iconSize))
        }
        // The page's popover (.np-device-popover): 230 wide on the raised ground with its edge and corners of 12, 10 of padding;
        // its title small capitals, each room 15px (the one here brass and bold), then the actions 14px brass over a hairline each.
        val faint = paletteColor(Palette.TEXT_FAINT)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(12.dp), containerColor = paletteColor(Palette.BG_ELEV_2),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.width(230.dp).padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text("PLAY ON", Modifier.parity("now-playing.device-title mini-player.device-title").padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp),
                fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.08.em, color = faint)
            for (r in rooms) {
                val here = r.id == zoneId
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(r.name, fontSize = 15.sp, lineHeight = 18.75.sp, fontWeight = if (here) FontWeight.W600 else FontWeight.W400, color = if (here) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                            if (r.outputs.size > 1) Text(r.outputs.joinToString(" + "), fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; if (!here) onRoom(r.id) }, contentPadding = PaddingValues(10.dp),
                    modifier = Modifier.parity("now-playing.device-room mini-player.device-room", Contract.label("now-playing.device-room", 0, r.name))
                )
            }
            // Each action 8 under the last, over a hairline (the page's .np-popover-action): 14px at 600 in the brass, 38 tall.
            val actionPad = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
            ActionGap()
            DropdownMenuItem(text = { Text(Contract.label("now-playing.group"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, color = MaterialTheme.colorScheme.secondary) }, onClick = { open = false; sheet = "group" }, contentPadding = actionPad,
                modifier = Modifier.parity("now-playing.group mini-player.group", Contract.label("now-playing.group")).height(38.dp))
            ActionGap()
            DropdownMenuItem(text = { Text(Contract.label("now-playing.power"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, color = MaterialTheme.colorScheme.secondary) }, onClick = { open = false; sheet = "power" }, contentPadding = actionPad,
                modifier = Modifier.parity("now-playing.power mini-player.power", Contract.label("now-playing.power")).height(38.dp))
            ActionGap()
            DropdownMenuItem(text = { Text(Contract.label("now-playing.pause-all"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, color = MaterialTheme.colorScheme.secondary) }, onClick = { open = false; act { it.pauseAll() } }, contentPadding = actionPad,
                modifier = Modifier.parity("now-playing.pause-all mini-player.pause-all", Contract.label("now-playing.pause-all")).height(38.dp))
            ActionGap()
            DropdownMenuItem(text = { Text(Contract.label("now-playing.mute-all"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, color = MaterialTheme.colorScheme.secondary) }, onClick = { open = false; act { it.muteAll(true) } }, contentPadding = actionPad,
                modifier = Modifier.parity("now-playing.mute-all mini-player.mute-all", Contract.label("now-playing.mute-all")).height(38.dp))
            ActionGap()
            DropdownMenuItem(text = { Text(Contract.label("now-playing.unmute-all"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, color = MaterialTheme.colorScheme.secondary) }, onClick = { open = false; act { it.muteAll(false) } }, contentPadding = actionPad,
                modifier = Modifier.parity("now-playing.unmute-all mini-player.unmute-all", Contract.label("now-playing.unmute-all")).height(38.dp))
        }
    }
    // Grouped, the room is a new one (Roon mints a room for each set of speakers): the page told first, so its list has it, then followed here.
    if (sheet == "group") GroupSheet(zoneId, client, scope, onClose = { sheet = "" }) { id -> NativeShell.keepZone(id); onRoom(id) }
    if (sheet == "power") PowerSheet(client) { sheet = "" }
}

/** The room before each of Play on's actions: 8, then the hairline. */
@Composable
private fun ActionGap() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/**
 * The volume button: the speaker (struck when the room is muted), which
 * opens [VolumeSheet]. The sheet is the screen's to draw where the page
 * draws it, so the button only says it is [open]; a room with no volume has
 * the note in the sheet ([fixedNote]) or, the mini player's, a disabled
 * button, as the page's is.
 */
@Composable
internal fun VolumeMenu(volume: VolumeScale?, muted: Boolean, open: Boolean, onOpen: (Boolean) -> Unit,
                        size: Dp = 56.dp, iconSize: Dp = 32.dp, fixedNote: Boolean = true) {
    val can = fixedNote || volume != null
    IconButton(onClick = { onOpen(!open) }, enabled = can,
        modifier = Modifier.parity("now-playing.volume mini-player.volume", Contract.label("now-playing.volume")).size(size)) {
        Icon(painterResource(if (muted) R.drawable.np_mute else R.drawable.np_volume), contentDescription = null,
            tint = if (can) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(iconSize))
    }
}

/** Where the page puts the sheet: [above] the composable it is drawn in (the mini bar), or at its foot, [inset] up from the bottom (Now playing). */
internal class SheetAt(private val above: Boolean, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(0, (if (above) anchorBounds.top else anchorBounds.bottom) - popupContentSize.height - inset)
}

/**
 * The volume sheet, as the page's (`.vol-sheet`, one for Now playing and
 * the mini player): a glass pill the width of the screen with the speaker
 * and the level in brass, the slider over the speaker's own scale (its ends
 * written under it; none for a speaker that only steps), and the − and + in
 * brass rings. The level follows a drag at once and is sent as it ends, as
 * the page's slider does; a tap outside closes it. A room with no volume
 * says it is fixed. Drawn by the screen, in the composable the sheet is
 * placed against ([above] it, or at its foot [inset] up).
 */
@Composable
internal fun VolumeSheet(open: Boolean, onClose: () -> Unit, zoneId: String, volume: VolumeScale?, act: ((ServerClient) -> Unit) -> Unit,
                         above: Boolean, inset: Dp = 0.dp) {
    if (!open) return
    val insetPx = with(LocalDensity.current) { inset.roundToPx() }
    val at = remember(above, insetPx) { SheetAt(above, insetPx) }
    Popup(popupPositionProvider = at, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        val brass = MaterialTheme.colorScheme.primary
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        val shape = RoundedCornerShape(16.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).shadow(24.dp, shape).clip(shape).background(paletteColor(Palette.BG_VEIL))
                .border(1.dp, paletteColor(Palette.GLASS_EDGE), shape).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 30.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val s = volume
            if (s == null) {
                Row(Modifier.parity("now-playing.volume-fixed").padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.np_volume), contentDescription = null, tint = dim, modifier = Modifier.size(16.dp))
                    Text("Volume control is fixed", fontSize = 13.sp, color = dim, maxLines = 1)
                }
                return@Row
            }
            var dragging by remember { mutableStateOf<Double?>(null) }
            // The speaker and the level, in the brass.
            Row(Modifier.parity("now-playing.volume-level mini-player.volume-level"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.np_volume), contentDescription = null, tint = brass, modifier = Modifier.size(26.dp))
                Text((dragging ?: s.value)?.let { Math.round(it).toString() } ?: "—", Modifier.widthIn(min = 42.dp), fontSize = 26.sp, fontWeight = FontWeight.W600, color = brass, maxLines = 1)
            }
            // The slider: a 6dp track, brass to the thumb, with the scale's ends written under it. The thumb can't hang off either end, as the page's can't.
            if (!s.incremental) {
                val span = (s.max - s.min).takeIf { it > 0 } ?: 1.0
                fun at(x: Float, width: Float, thumbW: Float): Double = s.min + span * (if (width <= thumbW) 0f else ((x - thumbW / 2) / (width - thumbW)).coerceIn(0f, 1f))
                Box(Modifier.parity("now-playing.volume-slider mini-player.volume-slider", Contract.label("now-playing.volume-slider")).weight(1f).height(30.dp)
                        .pointerInput(s) {
                            val thumbW = 22.dp.toPx()
                            detectDragGestures(
                                onDragStart = { dragging = at(it.x, size.width.toFloat(), thumbW) },
                                onDrag = { change, _ -> dragging = at(change.position.x, size.width.toFloat(), thumbW) },
                                onDragEnd = { val d = dragging; dragging = null; if (d != null) act { it.zoneVolume(zoneId, d) } },
                                onDragCancel = { dragging = null }
                            )
                        }
                        .pointerInput(s) {
                            val thumbW = 22.dp.toPx()
                            detectTapGestures { val d = at(it.x, size.width.toFloat(), thumbW); act { it.zoneVolume(zoneId, d) } }
                        }) {
                    val rest = paletteColor(Palette.BORDER)
                    val level = ((dragging ?: s.value ?: s.min) - s.min) / span
                    Canvas(Modifier.fillMaxSize()) {
                        val thumbW = 22.dp.toPx(); val trackH = 6.dp.toPx(); val top = (size.height - trackH) / 2
                        val head = thumbW / 2 + level.toFloat().coerceIn(0f, 1f) * (size.width - thumbW)
                        drawRoundRect(rest, Offset(0f, top), Size(size.width, trackH), CornerRadius(trackH / 2))
                        drawRoundRect(brass, Offset(0f, top), Size(head, trackH), CornerRadius(trackH / 2))
                        drawCircle(Color.Black.copy(alpha = 0.45f), thumbW / 2, Offset(head, size.height / 2 + 1.dp.toPx()))
                        drawCircle(brass, thumbW / 2, Offset(head, size.height / 2))
                    }
                    Row(Modifier.fillMaxWidth().align(Alignment.TopStart).offset(y = 33.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Math.round(s.min).toString(), fontSize = 14.sp, color = brass)
                        Text(Math.round(s.max).toString(), fontSize = 14.sp, color = brass)
                    }
                }
            }
            // A step down and up: brass rings of 44 on the raised ground, the glyph drawn at the page's weight. Outlined buttons: a plain
            // button's border modifier lay outside its 48 touch target and drew the ring 48 wide (bug list item 54).
            val ring = IconButtonDefaults.outlinedIconButtonColors(containerColor = paletteColor(Palette.BG_ELEV_2), contentColor = brass)
            OutlinedIconButton(onClick = { step(zoneId, s, -1, act) }, colors = ring, border = BorderStroke(1.dp, brass),
                modifier = Modifier.parity("now-playing.volume-down mini-player.volume-down", Contract.label("now-playing.volume-down")).size(44.dp)) {
                Icon(painterResource(R.drawable.np_minus), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            OutlinedIconButton(onClick = { step(zoneId, s, 1, act) }, colors = ring, border = BorderStroke(1.dp, brass),
                modifier = Modifier.parity("now-playing.volume-up mini-player.volume-up", Contract.label("now-playing.volume-up")).size(44.dp)) {
                Icon(painterResource(R.drawable.np_plus), contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** A step of the room's volume: on its scale, or a nudge where it has none. */
private fun step(zoneId: String, s: VolumeScale, direction: Int, act: ((ServerClient) -> Unit) -> Unit) {
    if (s.incremental) act { it.zoneVolumeStep(zoneId, direction) }
    else s.stepped(direction)?.let { to -> act { it.zoneVolume(zoneId, to) } }
}
