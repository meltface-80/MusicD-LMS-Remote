package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.musicd.mallard.client.QueueItem
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfPlaying

/**
 * The native Shelf's queue (bug list item 207; the page's shelf-queue.js), drawn in from the right over the shelf down
 * to the bar: Queue, the zone it is, how many tracks and how long; the track playing under Now playing, the rest under Up
 * next. A tap on a track to come offers Play from here (a stray touch must not jump the music); a tap on the one playing
 * brings its record to the front of the shelf.
 */
@Composable
internal fun ShelfQueue(play: ShelfPlay, zoneName: String, playing: Boolean, rem: Dp, client: () -> ServerClient?, onNow: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(paletteColor(Palette.BG_ELEV)).statusBarsPadding().padding(top = 16.dp)) {
        Column(Modifier.padding(horizontal = rem * 1.2f)) {
            Box(Modifier.heightIn(min = rem * 2.6f), contentAlignment = Alignment.CenterStart) {
                Text("Queue", Modifier.parity("shelf.queue-title"), color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontSize = (rem.value * 2f).sp, lineHeight = (rem.value * 2f).sp)
            }
            if (zoneName.isNotEmpty()) Text(zoneName.uppercase(), Modifier.parity("shelf.queue-zone").padding(top = rem * 0.7f), color = paletteColor(Palette.ACCENT_TEXT),
                fontSize = (rem.value * 0.7f).sp, fontWeight = FontWeight.W800, letterSpacing = 0.12.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sum = ShelfPlaying.queueSum(play.queue)
            if (sum.isNotEmpty()) Text(sum, Modifier.parity("shelf.queue-sum").padding(top = rem * 0.15f), color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.85f).sp)
        }
        if (play.queueSaid.isNotEmpty()) Text(play.queueSaid, Modifier.parity("shelf.queue-message").padding(start = rem * 1.2f, top = rem * 1.1f, end = rem * 1.2f),
            color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.92f).sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = rem * 0.7f), contentPadding = PaddingValues(start = rem * 0.55f, end = rem * 0.55f, bottom = rem * 1.2f)) {
            itemsIndexed(play.queue, key = { _, it -> it.id }) { i, it ->
                val now = playing && i == 0
                if (i == 0 || (playing && i == 1)) Text(if (now) "NOW PLAYING" else "UP NEXT", Modifier.parity("shelf.queue-head").padding(start = rem * 0.65f, top = if (i == 0) rem * 0.2f else rem * 0.9f, bottom = rem * 0.35f),
                    color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.68f).sp, fontWeight = FontWeight.W800, letterSpacing = 0.12.em)
                QueueRow(play, it, now, rem, client, onNow)
            }
        }
    }
}

/** A track of the queue: its cover, title, artist and length; the one playing lit; one tapped offering Play from here. */
@Composable
private fun QueueRow(play: ShelfPlay, it: QueueItem, now: Boolean, rem: Dp, client: () -> ServerClient?, onNow: () -> Unit) {
    val armed = play.armed == it.id && !now
    val shape = RoundedCornerShape(rem * 0.7f)
    val ground = when { now -> Modifier.background(paletteColor(Palette.ACCENT_SOFT), shape).border(1.dp, SHELF_BRASS_DIM, shape); armed -> Modifier.background(paletteColor(Palette.BG_ELEV_2), shape); else -> Modifier }
    Column(Modifier.fillMaxWidth().then(ground)) {
        Row(Modifier.parity("shelf.queue-track", Contract.label("shelf.queue-track", 0, it.title)).fillMaxWidth().heightIn(min = rem * 3.4f).clip(shape)
            .clickable { if (now) { play.armed = null; onNow() } else play.armed = if (armed) null else it.id }.padding(horizontal = rem * 0.55f, vertical = rem * 0.4f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.7f)) {
            val cover = rememberCover(it.imageKey, 120, client)
            Box(Modifier.size(rem * 2.7f).clip(RoundedCornerShape(3.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
                cover?.let { c -> Image(c, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(rem * 2.7f)) }
            }
            Column(Modifier.weight(1f)) {
                Text(it.title, color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.9f).sp, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (it.subtitle.isNotEmpty()) Text(it.subtitle, color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.8f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            it.lengthSeconds?.takeIf { s -> s > 0 }?.let { s -> Text(ShelfPlaying.clock(s), color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.78f).sp) }
        }
        if (armed) Row(Modifier.padding(start = rem * 3.95f, end = rem * 0.55f, bottom = rem * 0.55f).parity("shelf.queue-play", Contract.label("shelf.queue-play")).height(rem * 2.4f)
            .clip(CircleShape).background(paletteColor(Palette.ACCENT)).clickable { play.playFrom(it) }.padding(horizontal = rem),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.45f)) {
            Icon(painterResource(R.drawable.np_play), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(rem))
            Text("Play from here", color = paletteColor(Palette.ON_ACCENT), fontSize = (rem.value * 0.85f).sp, fontWeight = FontWeight.W700)
        }
    }
}
