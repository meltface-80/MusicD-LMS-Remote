package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient

/**
 * The mini player, natively (docs/specs/screens.json, mini-player), as the
 * page's bar along the foot of an album's page: a brass play/pause disc, the
 * cover and the track (a tap: Now playing), the room and the format above
 * it, Play on and the volume (Now playing's own menus), and the track's
 * progress as a level meter along the top. Docked (bug list item 102, as the
 * owner chose it; android.css): the full width, straight on the row along the
 * foot, solid in that row's colour with a hairline above it, not rounded, the
 * meter the full width above the cover and the words. It shows [room], the page's
 * room [zoneId] as its screen follows it ([followRoom]); with no room it
 * says so, as the page's bar does.
 */
@Composable
internal fun MiniPlayer(zoneId: String, room: RoomState, client: () -> ServerClient?, onOpen: () -> Unit, onRoom: (String) -> Unit, onPage: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val v = room.view
    val scope = rememberCoroutineScope()
    var volumeOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun act(block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.sendTap(context, c, block)
    }
    val cover = rememberCover(v?.imageKey, 120, client)
    val brass = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth()) {
        // The volume sheet just above the bar, as the page's is.
        VolumeSheet(volumeOpen, { volumeOpen = false }, zoneId, room.volume, ::act, above = true, inset = (-14).dp)
        Column(Modifier.fillMaxWidth().background(paletteColor(Palette.BG))) {
            // The hairline above (border-top 1, --border); the meter 9 under it and 14 in; the row 20 under the line, 12 above the foot.
            Box(Modifier.fillMaxWidth().height(1.dp).background(paletteColor(Palette.BORDER)))
            RoomMeter(room, brass, Modifier.parity("mini-player.progress").fillMaxWidth().padding(start = 14.dp, top = 9.dp, end = 14.dp).height(4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 7.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledIconButton(onClick = { act { it.control(zoneId, "playpause") } }, enabled = v != null, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = brass, disabledContentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.parity("mini-player.playpause", Contract.label("mini-player.playpause", if (v?.playing == true) 1 else 0)).size(48.dp)) {
                    Icon(painterResource(if (v?.playing == true) R.drawable.np_pause else R.drawable.np_play), contentDescription = null, modifier = Modifier.size(30.dp))
                }
                // The cover and the text are one tap: Now playing.
                Row(Modifier.weight(1f).parity("mini-player.open", Contract.label("mini-player.open")).clickable(enabled = v != null, onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (v?.imageKey != null) Box(Modifier.parity("mini-player.cover").size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
                    }
                    Column(Modifier.weight(1f)) {
                        val kicker = listOfNotNull(room.zoneName?.ifEmpty { null }, v?.formatWord).joinToString(" · ")
                        // Each line at the font's own height (the page's line-height: normal), not Material's 24sp body line: three of those stood taller than the cover (bug list item 53).
                        if (kicker.isNotEmpty()) Text(kicker.uppercase(), Modifier.parity("mini-player.kicker").padding(bottom = 1.dp), color = MaterialTheme.colorScheme.secondary,
                            fontSize = 11.5.sp, lineHeight = 15.7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // With no room, or nothing playing in it, the bar says so, as the page's does; a dash until the server answers.
                        val title = when { zoneId.isEmpty() -> "No Zone Selected"; !room.answered -> "—"; v == null -> "Nothing playing"; else -> v.title.ifEmpty { "—" } }
                        val sub = when {
                            zoneId.isEmpty() -> "Tap the speaker to choose where to play"
                            !room.answered -> "—"
                            v == null -> "Pick an album to play here"
                            else -> listOf(v.artist, v.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" }
                        }
                        Text(title, Modifier.parity("mini-player.title"), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 23.2.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, Modifier.parity("mini-player.artist"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 19.1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Play on and the volume side by side with nothing between (the page's .mt-controls, gap 0): in the row's own 10
                // they stood 10 apart, and took that 10 from the title (bug list item 56).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayOnMenu(room.id, client, ::act, onRoom, size = 46.dp, iconSize = 28.dp)   // the room followed: away, this phone's (item 487)
                    VolumeMenu(room.volume, room.muted, volumeOpen, { volumeOpen = it }, size = 46.dp, iconSize = 28.dp, fixedNote = false)
                }
            }
        }
    }
}

/** The meter on the room's own clock, read here alone (item 158: read by the bar, its every half second drew the whole bar again). */
@Composable
private fun RoomMeter(room: RoomState, lit: Color, modifier: Modifier) = Meter(room.view?.fraction(room.now) ?: 0f, lit, modifier)

/** The track's progress as the page's level meter: brass segments over faint ones, [fraction] of the way lit. */
@Composable
private fun Meter(fraction: Float, lit: Color, modifier: Modifier) {
    val unlit = paletteColor(Palette.METER_OFF)
    Canvas(modifier) {
        val on = 7.dp.toPx(); val pitch = 10.dp.toPx()
        val head = fraction * size.width
        var x = 0f
        while (x < size.width) {
            val w = minOf(on, size.width - x)
            drawRect(unlit, Offset(x, 0f), Size(w, size.height))
            val done = minOf(w, head - x)
            if (done > 0f) drawRect(lit, Offset(x, 0f), Size(done, size.height))
            x += pitch
        }
    }
}
