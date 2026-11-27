package com.musicd.mallard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Track

/** A track's "Disc 2" part, left out where the album says its discs itself. */
private val DISC_PART = Regex("^Disc \\d+$")

/**
 * The album page's tracks (docs/specs/screens.json, album), as the page's
 * list (style.css, `.track-list`): "TRACKS" tracked out over a hairline,
 * then a row each on its own hairline, its number, its title, who else is
 * on it and its length; the track playing in the room ([playingId]) wears
 * the page's brass disc, turning while the room plays. A tap opens the
 * row's Play now, Play next and Queue, as the page's track menu has them.
 * [onPlay] is given the track's index, how it is played and what to say of it.
 * A long press starts selecting ([onPick], with the track); selecting
 * ([picks], the places picked), each row shows its circle, and a tap on the
 * row or the circle picks or unpicks it. A classical album's list is
 * AlbumWorks.kt's, which draws its movements with [TrackRow] too.
 */
internal fun LazyListScope.albumTracks(a: Album, playingId: Long?, playing: Boolean, picks: List<Int>?, onPick: (Int) -> Unit, onPlay: (Int, String, String) -> Unit, head: Boolean = true) {
    if (head) tracksHead("Tracks")   // under a phone's Tracks tab, which says it, none (item 355)
    itemsIndexed(a.tracks) { i, t -> TrackRow(i, t, t.title, false, false, playingId, playing, picks, onPick, onPlay) }
}

/** The list's head, tracked out over its hairlines: "Tracks", or "Works" for a classical album. */
internal fun LazyListScope.tracksHead(text: String) {
    item {
        val faint = paletteColor(Palette.TEXT_FAINT)
        Text(text.uppercase(), Modifier.parity("album.tracks-head").fillMaxWidth().padding(bottom = 8.dp),
            color = faint, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.W600, letterSpacing = 0.16.em)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * One track's row, the [i]th of the album: numbered, named [title] (its own, or its name as a
 * movement of a work, indented under the work's row when [movement]); under a disc's heading
 * ([noDisc]) the row needn't say which disc it is on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(i: Int, t: Track, title: String, movement: Boolean, noDisc: Boolean, playingId: Long?, playing: Boolean, picks: List<Int>?, onPick: (Int) -> Unit, onPlay: (Int, String, String) -> Unit) {
    var open by remember(t.id) { mutableStateOf(false) }
    val white = paletteColor(Palette.MODAL_TEXT)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val rowShape = RoundedCornerShape(10.dp)
    val selecting = picks != null
    val picked = picks?.contains(i) == true
    if (selecting && open) open = false   // an open row and a selection are two intents: the row closes as selecting starts
    val sub = if (noDisc) t.sub.split(" · ").filter { !DISC_PART.matches(it) }.joinToString(" · ") else t.sub
    Column(Modifier.parity("album.track", Contract.label("album.track", 0, title)).fillMaxWidth()
        .let { m -> if (picked) m.clip(RoundedCornerShape(8.dp)).background(paletteColor(Palette.ACCENT_SOFT)) else if (open) m.clip(rowShape).background(paletteColor(Palette.ROW_OPEN)) else m }
        .combinedClickable(onLongClick = { if (!selecting) onPick(i) }, onClick = { if (selecting) onPick(i) else open = !open })) {
        Row(Modifier.fillMaxWidth().padding(start = (if (open) 8.dp else 4.dp) + (if (movement) 34.dp else 0.dp), end = if (open) 8.dp else 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The number's box is 22 wide at least (the page's min-width), its figures measured as drawn.
            var numberWidth by remember { mutableIntStateOf(0) }
            Box(Modifier.defaultMinSize(minWidth = 22.dp).height(20.dp)) {
                Text("${i + 1}", color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 20.sp, onTextLayout = { numberWidth = it.size.width })
                // The disc on the track playing, in the middle of the room between the number's last figure and the name (the box's
                // end and the row's 12 gap after it), on the name's line, as the page's (nowdisc.js, item 50): from the box's end
                // half the room past it, less half the box's spare width.
                if (playingId != null && playingId == t.id) {
                    // Turned in its own layer (item 158): read in the composition, the turn drew the whole row again on every frame.
                    val turn = if (playing) rememberInfiniteTransition(label = "disc").animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "turn") else null
                    val figures = with(LocalDensity.current) { numberWidth.toDp() }
                    val box = if (figures > 22.dp) figures else 22.dp
                    Icon(painterResource(R.drawable.np_disc), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.parity("album.track-playing").align(Alignment.CenterEnd).offset(x = 13.dp - (box - figures) / 2).size(14.dp).graphicsLayer { rotationZ = turn?.value ?: 0f })
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = white, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub, Modifier.padding(top = 1.dp), color = dim, fontSize = 12.sp, lineHeight = 17.sp)
                // MQA, as its samples say (item 418): the page's .mqa-badge, MQA Studio's in the accent.
                t.mqa?.let { m ->
                    val c = if (m.studio) MaterialTheme.colorScheme.primary else dim
                    Text(m.label, Modifier.parity("album.track-mqa").padding(top = 3.dp).border(1.dp, if (m.studio) c else paletteColor(Palette.BORDER), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp), color = c, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.W600)
                }
            }
            if (t.lengthText.isNotEmpty()) Text(t.lengthText, color = dim, fontSize = 13.sp, lineHeight = 20.sp)
            // The circle, selecting: hollow means it can be picked, a tick that it is (the page's .t-mark).
            if (selecting) Box(Modifier.parity("album.track-select", Contract.label("album.track-select")).align(Alignment.CenterVertically).size(22.dp).clip(CircleShape)
                .background(if (picked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(2.dp, if (picked) MaterialTheme.colorScheme.primary else paletteColor(Palette.TEXT_FAINT), CircleShape).clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                if (picked) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
            }
        }
        if (open) {
            // The row's buttons, as the page's (style.css, `.t-actions .action-btn`): 38 tall, Play now in brass, the other two on the raised ground.
            val primary = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            val plain = ButtonDefaults.buttonColors(containerColor = paletteColor(Palette.BG_ELEV_2), contentColor = MaterialTheme.colorScheme.onSurface)
            val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, ButtonDefaults.shape)
            val pad = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
            Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open = false; onPlay(i, "play_now", "Playing") }, colors = primary, contentPadding = pad,
                    modifier = Modifier.parity("album.track-play-now", Contract.label("album.track-play-now")).defaultMinSize(minHeight = 38.dp)) {
                    Text(Contract.label("album.track-play-now"), fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600)
                }
                Button(onClick = { open = false; onPlay(i, "play_next", "Playing next") }, colors = plain, contentPadding = pad,
                    modifier = Modifier.parity("album.track-play-next", Contract.label("album.track-play-next")).defaultMinSize(minHeight = 38.dp).then(edge)) {
                    Text(Contract.label("album.track-play-next"), fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600)
                }
                Button(onClick = { open = false; onPlay(i, "queue", "Queued") }, colors = plain, contentPadding = pad,
                    modifier = Modifier.parity("album.track-queue", Contract.label("album.track-queue")).defaultMinSize(minHeight = 38.dp).then(edge)) {
                    Text(Contract.label("album.track-queue"), fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600)
                }
            }
        }
        if (!open) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
