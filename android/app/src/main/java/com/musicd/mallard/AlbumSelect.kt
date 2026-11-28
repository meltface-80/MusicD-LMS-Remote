package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient

/*
 * Tracks selected on the album page (docs/specs/screens.json, album;
 * public/app/album-tracks.js): a long press on a track starts it, with that
 * track the first pick; then each row's circle, or the row itself, picks or
 * unpicks. The ⋯ with the count, top right in place of previous and next,
 * opens what to do with them: play now, play next, add to the end of the
 * queue (each in the album's order, as the page sends them), or clear.
 */

/** The ⋯ with the count, and its menu; [picks] are the tracks' places in order of picking. */
@Composable
internal fun SelectMenu(picks: List<Int>, onAct: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val n = picks.size
    Box(modifier) {
        Row(Modifier.parity("album.select-menu", Contract.label("album.select-menu")).height(40.dp).clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .clickable { open = !open }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // The count, in brass: the one sign of how many are picked.
            Text("$n", Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp),
                color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
            Icon(painterResource(R.drawable.np_select_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("$n track" + (if (n == 1) "" else "s") + " selected", Modifier.parity("album.select-title").padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                color = paletteColor(Palette.TEXT_FAINT), fontSize = 11.sp, letterSpacing = 0.08.em)
            DropdownMenuItem(text = { Text(Contract.label("album.select-play-now"), fontSize = 15.sp) }, onClick = { open = false; onAct("play_now") },
                modifier = Modifier.parity("album.select-play-now", Contract.label("album.select-play-now")))
            DropdownMenuItem(text = { Text(Contract.label("album.select-play-next"), fontSize = 15.sp) }, onClick = { open = false; onAct("play_next") },
                modifier = Modifier.parity("album.select-play-next", Contract.label("album.select-play-next")))
            DropdownMenuItem(text = { Text(Contract.label("album.select-queue"), fontSize = 15.sp) }, onClick = { open = false; onAct("queue") },
                modifier = Modifier.parity("album.select-queue", Contract.label("album.select-queue")))
            DropdownMenuItem(text = { Text("Add to playlist", fontSize = 15.sp) }, onClick = { open = false; onAct("playlist") })
            DropdownMenuItem(text = { Text(Contract.label("album.select-clear"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp) }, onClick = { open = false; onAct("clear") },
                modifier = Modifier.parity("album.select-clear", Contract.label("album.select-clear")))
        }
    }
}

/**
 * The picked tracks played or queued, in the album's order, one at a time
 * as the page sends them: only the first honours Play now, the rest queue
 * behind it; Play next goes last to first, each straight after the track
 * playing, so they end in order. What came of it, as the page says it.
 */
internal fun playPicks(c: ServerClient, a: Album, picks: List<Int>, zoneId: String, kind: String): String {
    // One request (/api/play-tracks): the server plays them in the order given, skipping any whose place has moved.
    var order = picks.sorted()
    if (kind == "play_next") order = order.reversed()
    val r = runCatching { c.playTracks(a.album.offset, order, zoneId, kind) }
    r.exceptionOrNull()?.let { return it.message ?: "Couldn't reach the server" }
    val j = r.getOrThrow()
    val played = j.optInt("played", order.size); val missing = j.optInt("missing", 0)
    val verb = when (kind) { "queue" -> "Queued"; "play_next" -> "Playing next:"; else -> "Playing" }
    return "$verb $played track" + (if (played == 1) "" else "s") + (if (missing > 0) " ($missing no longer there)" else "")
}
