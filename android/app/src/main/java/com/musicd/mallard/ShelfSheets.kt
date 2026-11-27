package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.VolumeScale
import kotlin.math.roundToInt

/**
 * The native Shelf's zone list over the bar (bug list item 207; the page's #zones): Play in, each zone with what it is
 * doing, the one played in lit; a tap plays in that one (the room the app's screens play in too).
 */
@Composable
internal fun ShelfZones(play: ShelfPlay, rem: Dp, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(rem * 0.9f)
    Column(modifier.widthIn(min = rem * 15f).heightIn(max = rem * 22f).shadow(24.dp, shape).clip(shape).background(paletteColor(Palette.BG_ELEV))
        .border(1.dp, paletteColor(Palette.BORDER), shape).verticalScroll(rememberScrollState()).padding(rem * 0.4f)) {
        Text("PLAY IN", Modifier.parity("shelf.zones-head").padding(start = rem * 0.7f, top = rem * 0.4f, end = rem * 0.7f, bottom = rem * 0.3f),
            color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.72f).sp, fontWeight = FontWeight.W800, letterSpacing = 0.12.em)
        if (play.zones.isEmpty()) Text("No zones found", Modifier.parity("shelf.zones-none").padding(horizontal = rem * 0.7f, vertical = rem * 0.3f),
            color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.72f).sp, fontWeight = FontWeight.W800)
        for (z in play.zones) {
            val on = z.id == play.zone
            Row(Modifier.parity("shelf.zone-choice", Contract.label("shelf.zone-choice", 0, z.name)).fillMaxWidth().heightIn(min = rem * 2.8f).clip(RoundedCornerShape(rem * 0.6f))
                .clickable { play.choose(z.id); onClose() }.padding(horizontal = rem * 0.8f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(z.name, color = paletteColor(if (on) Palette.ACCENT_TEXT else Palette.TEXT), fontSize = (rem.value).sp, fontWeight = FontWeight.W600)
                if (z.said.isNotEmpty()) Text(z.said, color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.8f).sp, fontWeight = FontWeight.W600)
            }
        }
    }
}

/**
 * The native Shelf's volume sheet (the page's shelf-volume.js, v1.9.68-t2: the remote's sheet in the brass), centred just
 * above the bar: the speaker and the level, the slider with its ends under it, a round − and +. A drag sends as it goes,
 * one write at a time and the newest waiting; what is on screen is held against the room's answers for a moment, and +
 * and − step from it. A zone with only steps has − and + alone.
 */
@Composable
internal fun ShelfVolume(play: ShelfPlay, volume: VolumeScale?, rem: Dp, modifier: Modifier = Modifier) {
    val v = volume ?: return
    var shown by remember { mutableStateOf<Double?>(null) }
    var heldUntil by remember { mutableLongStateOf(0L) }
    val level = if (shown != null && System.currentTimeMillis() < heldUntil) shown else v.value
    fun show(x: Double) { shown = x; heldUntil = System.currentTimeMillis() + 2500; play.volumeTo(x) }
    val shape = RoundedCornerShape(rem)
    val brass = paletteColor(Palette.ACCENT)
    Row(modifier.widthIn(max = rem * 30f).fillMaxWidth().shadow(24.dp, shape).clip(shape).background(paletteColor(Palette.BG_ELEV)).border(1.dp, paletteColor(Palette.BORDER), shape)
        .padding(start = rem, top = rem, end = rem, bottom = rem * 1.9f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.9f)) {
        Row(Modifier.parity("shelf.volume-level"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.5f)) {
            Icon(painterResource(R.drawable.np_volume), contentDescription = null, tint = brass, modifier = Modifier.size(rem * 1.6f))
            Text(if (v.incremental || level == null) "" else level.roundToInt().toString(), Modifier.widthIn(min = rem * 2.6f), color = brass, fontSize = (rem.value * 1.6f).sp, fontWeight = FontWeight.W600)
        }
        if (!v.incremental && level != null) Box(Modifier.weight(1f)) {
            val span = (v.max - v.min).takeIf { it > 0 } ?: 1.0
            val frac = ((level - v.min) / span).toFloat().coerceIn(0f, 1f)
            fun at(x: Float, width: Float): Double = (v.min + span * (x / width).coerceIn(0f, 1f)).let { Math.round(it / v.step) * v.step }.coerceIn(v.min, v.max)
            Canvas(Modifier.parity("shelf.volume-slider", Contract.label("shelf.volume-slider")).fillMaxWidth().height(rem * 1.9f)
                .pointerInput(v) { detectTapGestures { show(at(it.x, size.width.toFloat())) } }
                .pointerInput(v) { detectDragGestures { ch, _ -> show(at(ch.position.x, size.width.toFloat())) } }) {
                val track = 0.4f * rem.toPx(); val y = size.height / 2; val knob = 0.8f * rem.toPx()
                drawRoundRect(paletteColor(Palette.BORDER), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track / 2))
                drawRoundRect(brass, Offset(0f, y - track / 2), Size(size.width * frac, track), CornerRadius(track / 2))
                drawCircle(brass, knob, Offset((size.width * frac).coerceIn(knob, size.width - knob), y))
            }
            Row(Modifier.parity("shelf.volume-scale").fillMaxWidth().padding(horizontal = rem * 0.25f).padding(top = rem * 2f), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtLevel(v.min), color = brass, fontSize = (rem.value * 0.85f).sp)
                Text(fmtLevel(v.max), color = brass, fontSize = (rem.value * 0.85f).sp)
            }
        } else Box(Modifier.weight(1f))
        fun step(sign: Int) { val now = v.value?.let { level ?: it }; if (v.incremental || now == null) play.volumeStep(sign, v.step) else show((now + sign * v.step).coerceIn(v.min, v.max)) }
        VolumeStep(Modifier.parity("shelf.volume-down", Contract.label("shelf.volume-down")).size(rem * 2.75f).clip(CircleShape).clickable { step(-1) }, R.drawable.np_minus, rem)
        VolumeStep(Modifier.parity("shelf.volume-up", Contract.label("shelf.volume-up")).size(rem * 2.75f).clip(CircleShape).clickable { step(1) }, R.drawable.np_plus, rem)
    }
}

/** A round − or + in the brass, its tap in [modifier]. */
@Composable
private fun VolumeStep(modifier: Modifier, icon: Int, rem: Dp) {
    val brass = paletteColor(Palette.ACCENT)
    Box(modifier.background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, brass, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = brass, modifier = Modifier.size(rem * 1.35f))
    }
}

private fun fmtLevel(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()

/** What the gestures are, said once (the page's #help): the first time Shelf is opened here, and after each update until Don't show again is ticked. */
@Composable
internal fun ShelfHelp(rem: Dp, onDismiss: (never: Boolean) -> Unit) {
    var never by remember { mutableStateOf(false) }
    val brass = paletteColor(Palette.ACCENT)
    // A dialog of its own over the screen, as the page's: nothing under it answers until it is dismissed.
    Dialog(onDismissRequest = { onDismiss(never) }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color(0x8C000000)).padding(rem * 1.5f), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(rem * 1.1f)
        Column(Modifier.widthIn(max = rem * 30f).fillMaxWidth().shadow(24.dp, shape).clip(shape).background(paletteColor(Palette.BG_ELEV)).border(1.dp, paletteColor(Palette.BORDER), shape)
            .padding(start = rem * 1.5f, top = rem * 1.4f, end = rem * 1.5f, bottom = rem * 1.2f)) {
            Text("Flicking through the shelf", Modifier.parity("shelf.help-title").padding(bottom = rem * 0.9f), color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontSize = (rem.value * 1.4f).sp)
            Column(Modifier.parity("shelf.help-list").padding(bottom = rem * 1.2f), verticalArrangement = Arrangement.spacedBy(rem * 0.65f)) {
                for ((b, rest) in HELP) Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = paletteColor(Palette.ACCENT_TEXT), fontWeight = FontWeight.W700)) { append(b) }
                    append(rest)
                }, color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value).sp, lineHeight = (rem.value * 1.4f).sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.parity("shelf.help-never", Contract.label("shelf.help-never")).clickable { never = !never }.padding(vertical = rem * 0.3f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.55f)) {
                    Box(Modifier.size(rem * 1.15f).clip(RoundedCornerShape(3.dp)).background(if (never) brass else paletteColor(Palette.BG_ELEV_2)).border(1.dp, if (never) brass else paletteColor(Palette.TEXT_DIM), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center) {
                        if (never) Icon(painterResource(R.drawable.dv_tick), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(rem * 0.85f))
                    }
                    Text("Don’t show again", color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.92f).sp)
                }
                Box(Modifier.parity("shelf.help-ok", Contract.label("shelf.help-ok")).height(rem * 2.9f).clip(CircleShape).background(brass).clickable { onDismiss(never) }.padding(horizontal = rem * 1.25f),
                    contentAlignment = Alignment.Center) {
                    Text("Dismiss", color = paletteColor(Palette.ON_ACCENT), fontSize = (rem.value * 0.92f).sp, fontWeight = FontWeight.W700)
                }
            }
        }
    }
    }
}

/** The gestures, each its words in the brass and the rest (the page's help list, its keyboard's own line left to the page). */
private val HELP = listOf(
    "Swipe" to " moves one album",
    "Swipe & hold" to " keeps it turning, faster the further out you hold",
    "Flick" to " spins it, and it lands on an album three seconds later",
    "Tap" to " a spine to bring it to the front, and the front case to turn it over and open the album",
    "Hold" to " a track on the opened album to choose it, then tap more to add them — play them from under the cover",
    "The small tabs" to " at the edges fold the choices away, and bring the queue in from the right",
)
