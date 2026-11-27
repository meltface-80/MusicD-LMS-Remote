package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.NowPlayingView
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The seek bar on the room's own clock, read here alone (item 158: read by Now Playing, its every half second drew the whole screen again). */
@Composable
internal fun RoomSeekBar(room: RoomState, v: NowPlayingView, client: () -> ServerClient?, onSeek: (Double) -> Unit) = SeekBar(v, room.now, client, onSeek)

/**
 * Now playing's seek bar, where the track is and how long it is, as the
 * page's (docs/specs/screens.json, now-playing; style.css, `.np-progress`):
 * the level meter (brass segments over faint ones, 7 lit of each 10) with a
 * white thumb riding along it, or, when the server draws waveforms
 * (Settings), the track's waveform in the bar's place, the played part in
 * brass. The thumb travels from half its width in to half its width short
 * of the end, as the page's does, so a moment in the track is at one place
 * on both. A drag shows its time at once and seeks ([onSeek], in seconds) as
 * it ends; a tap seeks there.
 */
@Composable
internal fun SeekBar(v: NowPlayingView, now: Long, client: () -> ServerClient?, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = v.lengthSeconds
    // The track's levels, asked for once a track (the page's key: its title and album); again while the server is busy.
    var levels by remember(v.title, v.album) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(v.title, v.album) {
        val c = client() ?: return@LaunchedEffect
        if (!withContext(Dispatchers.IO) { runCatching { c.waveformOn() }.getOrDefault(false) }) return@LaunchedEffect
        for (attempt in 0 until 10) {
            val r = withContext(Dispatchers.IO) { runCatching { c.waveform(v.title, v.album, v.artist, length) } }
            if (r.exceptionOrNull() !is ServerClient.Busy) { levels = r.getOrNull(); break }
            delay(3000)
        }
    }
    val fraction = dragging ?: v.fraction(now)
    val played = MaterialTheme.colorScheme.primary
    val rest = paletteColor(Palette.METER_OFF)
    val ahead = MaterialTheme.colorScheme.onSurfaceVariant
    val thumb = paletteColor(Palette.MODAL_TEXT)
    val wave = levels
    // The thumb's travel, as the page's: it can't hang off either end.
    fun at(x: Float, width: Float, thumbW: Float): Float = if (width <= thumbW) 0f else ((x - thumbW / 2) / (width - thumbW)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.fillMaxWidth().height(if (wave != null) 64.dp else 14.dp)) {
            if (wave != null) {
                // The waveform under the bar, inset by half the thumb, so a peak is under the thumb as it is heard (the page's drawWave).
                Canvas(Modifier.parity("now-playing.wave").fillMaxSize()) {
                    val thumbW = 14.dp.toPx()
                    val head = thumbW / 2 + fraction * (size.width - thumbW)
                    val pitch = 3.dp.toPx() / 2
                    val span = size.width - thumbW
                    val bars = Waveform.bars(wave, maxOf(1, (span / pitch).toInt()))
                    val step = span / bars.size
                    bars.forEachIndexed { i, h ->
                        val x = thumbW / 2 + i * step
                        val barH = maxOf(1f, h * (size.height - 2))
                        val done = x + step / 2 <= head
                        drawRect(if (done) played else ahead.copy(alpha = 0.72f), Offset(x, (size.height - barH) / 2), Size(step * 2 / 3, barH))
                    }
                }
            }
            // The bar itself: the meter (none over a waveform) and the thumb, dragged or tapped.
            Canvas(Modifier.parity("now-playing.seek", Contract.label("now-playing.seek")).fillMaxSize()
                .pointerInput(length) {
                    if (length == null) return@pointerInput
                    val thumbW = 14.dp.toPx()
                    detectDragGestures(
                        onDragStart = { dragging = at(it.x, size.width.toFloat(), thumbW) },
                        onDrag = { change, _ -> dragging = at(change.position.x, size.width.toFloat(), thumbW) },
                        onDragEnd = { val f = dragging; dragging = null; if (f != null) onSeek(f * length) },
                        onDragCancel = { dragging = null }
                    )
                }
                .pointerInput(length) {
                    if (length == null) return@pointerInput
                    val thumbW = 14.dp.toPx()
                    detectTapGestures { onSeek(at(it.x, size.width.toFloat(), thumbW) * length) }
                }) {
                val thumbW = 14.dp.toPx()
                val head = thumbW / 2 + fraction * (size.width - thumbW)
                if (wave == null) {
                    // The level meter: 7 of each 10 lit to the thumb, 4 tall, through the thumb's middle.
                    val on = 7.dp.toPx(); val pitch = 10.dp.toPx(); val barH = 4.dp.toPx(); val top = (size.height - barH) / 2
                    var x = 0f
                    while (x < size.width) {
                        val w = minOf(on, size.width - x)
                        drawRect(rest, Offset(x, top), Size(w, barH))
                        val lit = minOf(w, head - x)
                        if (lit > 0f) drawRect(played, Offset(x, top), Size(lit, barH))
                        x += pitch
                    }
                }
                drawCircle(if (length == null) ahead else thumb, thumbW / 2, Offset(head, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val faint = paletteColor(Palette.TEXT_FAINT)
            Text(NowPlayingView.clock(dragging?.let { it * (length ?: 0.0) } ?: v.position(now)), Modifier.parity("now-playing.position"), fontSize = 12.sp, lineHeight = 17.sp, color = faint)
            Spacer(Modifier.weight(1f))
            Text(length?.let { NowPlayingView.clock(it) }.orEmpty(), Modifier.parity("now-playing.length"), fontSize = 12.sp, lineHeight = 17.sp, color = faint)
        }
    }
}
