package com.musicd.mallard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfChoices
import com.musicd.mallard.client.ShelfMotion
import com.musicd.mallard.client.ShelfSort
import kotlin.math.roundToInt

/** The page's dimmer brass (shelf.css --accent-dim): Spin's ring, a chip's and a choice's edge. */
internal val SHELF_BRASS_DIM = Color(0xFF8A7140)

/**
 * The native Shelf's bar along its foot (bug list item 207; docs/specs/screens.json, shelf), as the page's shelf-bar.css
 * lays it out on a phone: flat and square, the progress line along its top; a slim row with where the shelf is, its order
 * and Spin; then previous, play/pause and next, the cover with the zone in small brass capitals over the track (a tap
 * brings that record to the front of the shelf), and the zone's and the volume's buttons.
 */
@Composable
internal fun ShelfBar(shelf: ShelfControl, play: ShelfPlay, room: RoomState, rem: Dp, client: () -> ServerClient?, zonesOpen: Boolean, volumeOpen: Boolean,
                      onZones: () -> Unit, onVolume: () -> Unit, modifier: Modifier = Modifier) {
    val v = room.view
    val brass = paletteColor(Palette.ACCENT)
    Column(modifier.fillMaxWidth().background(paletteColor(Palette.BG_ELEV))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(paletteColor(Palette.BORDER)))
        // the track's position along the top, carried on by the room's clock
        Box(Modifier.parity("shelf.progress").fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxWidth(v?.fraction(room.now) ?: 0f).fillMaxHeight().background(brass))
        }
        Column(Modifier.fillMaxWidth().padding(start = rem * 1.25f, end = rem * 1.25f, top = rem * 0.35f, bottom = rem * 0.35f).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(rem * 0.2f)) {
            ShelfOwn(shelf, rem)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.5f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.15f)) {
                    val prevOn = v?.canPrevious == true; val nextOn = v?.canNext == true
                    Box(Modifier.parity("shelf.prev", Contract.label("shelf.prev")).size(rem * 2.6f).clip(CircleShape).clickable(enabled = prevOn) { play.control("previous") }.alpha(if (prevOn) 1f else 0.45f),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.np_previous), contentDescription = null, tint = paletteColor(Palette.TEXT), modifier = Modifier.size(rem * 1.35f))
                    }
                    val ppOn = play.zone.isNotEmpty() && room.answered
                    Box(Modifier.parity("shelf.playpause", Contract.label("shelf.playpause", if (v?.playing == true) 1 else 0)).size(rem * 2.9f).clip(CircleShape).background(brass)
                        .clickable(enabled = ppOn) { play.control("playpause") }.alpha(if (ppOn) 1f else 0.45f), contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (v?.playing == true) R.drawable.np_pause else R.drawable.np_play), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(rem * 1.35f))
                    }
                    Box(Modifier.parity("shelf.next", Contract.label("shelf.next")).size(rem * 2.6f).clip(CircleShape).clickable(enabled = nextOn) { play.control("next") }.alpha(if (nextOn) 1f else 0.45f),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.np_next), contentDescription = null, tint = paletteColor(Palette.TEXT), modifier = Modifier.size(rem * 1.35f))
                    }
                }
                Row(Modifier.weight(1f).parity("shelf.info", Contract.label("shelf.info")).clip(RoundedCornerShape(rem * 0.5f)).clickable { play.toPlaying(v) }.padding(rem * 0.2f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.75f)) {
                    val cover = rememberCover(v?.imageKey, 120, client)
                    Box(Modifier.parity("shelf.art").size(rem * 2.4f).clip(RoundedCornerShape(3.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
                        cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(rem * 2.4f)) }
                    }
                    Column(Modifier.weight(1f)) {
                        val kicker = room.zoneName?.takeIf { it.isNotEmpty() && play.zone.isNotEmpty() } ?: if (play.zonesRead && play.zones.isEmpty()) "No zones found" else "Choose a zone"
                        Text(kicker.uppercase(), Modifier.parity("shelf.zone"), color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value * 0.66f).sp, fontWeight = FontWeight.W800,
                            letterSpacing = 0.12.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val title = v?.title ?: if (play.zone.isNotEmpty() && room.answered) "Nothing playing" else ""
                        if (title.isNotEmpty()) Text(title, Modifier.parity("shelf.title"), color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.95f).sp, fontWeight = FontWeight.W700,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!v?.artist.isNullOrEmpty()) Text(v?.artist.orEmpty(), Modifier.parity("shelf.artist"), color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.82f).sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.1f)) {
                    Box(Modifier.parity("shelf.zones", Contract.label("shelf.zones")).size(rem * 2.6f).clip(CircleShape).clickable(onClick = onZones), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.np_device), contentDescription = null, tint = paletteColor(if (zonesOpen) Palette.ACCENT_TEXT else Palette.TEXT), modifier = Modifier.size(rem * 1.45f))
                    }
                    val volOn = room.volume != null
                    Box(Modifier.parity("shelf.volume", Contract.label("shelf.volume")).size(rem * 2.6f).clip(CircleShape).clickable(enabled = volOn, onClick = onVolume).alpha(if (volOn) 1f else 0.45f),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.np_volume), contentDescription = null, tint = paletteColor(if (volumeOpen) Palette.ACCENT_TEXT else Palette.TEXT), modifier = Modifier.size(rem * 1.45f))
                    }
                }
            }
        }
    }
}

/** The shelf's own row in the bar: how far along it is and in what order, and Spin with its countdown ring and turning disc. */
@Composable
private fun ShelfOwn(shelf: ShelfControl, rem: Dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(rem * 0.6f)) {
            val n = shelf.n
            val pos = if (n == 0) "0 albums" else "${ShelfChoices.fmt(ShelfMotion.mod(shelf.p.roundToInt(), n) + 1)} of ${ShelfChoices.fmt(n)}"
            Text(pos, Modifier.parity("shelf.pos"), color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.85f).sp, fontWeight = FontWeight.W700, maxLines = 1)
            Text(ShelfSort.name(shelf.choices.order()).uppercase().also { shelf.rev }, Modifier.parity("shelf.order"), color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.62f).sp,
                fontWeight = FontWeight.W800, letterSpacing = 0.12.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val spinOn = shelf.n >= 2
        Row(Modifier.parity("shelf.spin", Contract.label("shelf.spin")).height(rem * 2.2f).clip(CircleShape).border(1.dp, SHELF_BRASS_DIM, CircleShape)
            .clickable(enabled = spinOn) { shelf.spin(1, 2.4f) }.alpha(if (spinOn) 1f else 0.45f).padding(start = rem * 0.3f, end = rem * 0.9f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.5f)) {
            SpinDisc(shelf, rem * 1.95f)
            Text("Spin", color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value).sp, fontWeight = FontWeight.W700)
        }
    }
}

/** The disc on its spindle inside the countdown ring: it turns, and the ring runs down, while the shelf spins. */
@Composable
internal fun SpinDisc(shelf: ShelfControl, size: Dp) {
    val brass = paletteColor(Palette.ACCENT)
    val spinning = shelf.spinning != 0L
    // Turning only while it spins: nothing is drawn again while the shelf stands still.
    val turn = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) { turn.snapTo(0f); return@LaunchedEffect }
        while (true) { turn.snapTo(0f); turn.animateTo(360f, tween(450, easing = LinearEasing)) }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            if (!spinning) return@Canvas
            val left = 1f - ((System.currentTimeMillis() - shelf.spinning).toFloat() / shelf.spinLength).coerceIn(0f, 1f)
            if (turn.value >= 0f) drawArc(brass, -90f, 360f * left, false, Offset(2f, 2f), Size(this.size.width - 4f, this.size.height - 4f), style = Stroke(2.2f * density))
        }
        Icon(painterResource(R.drawable.np_disc), contentDescription = null, tint = brass,
            modifier = Modifier.size(size * 0.82f).graphicsLayer { rotationZ = turn.value })
    }
}
