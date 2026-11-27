package com.musicd.mallard

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.NowPlayingView
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.QueueView
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Now playing's Queue tab, natively, as the page's (docs/specs/screens.json,
 * queue; public/app/queue.js): what is to come under "Now playing", what has
 * played above it in a fold-out. A tap plays on from a track, or puts a played
 * one back next; a long press picks tracks, in order, to play now, next, or
 * take out; played tracks are picked with Select. Each asks first, as the
 * page does. The queue is asked for again whenever the room changes ([changed]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueScreen(zoneId: String, client: () -> ServerClient?, changed: Any?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember(zoneId) { mutableStateOf<QueueView?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var picks by remember { mutableStateOf<List<Long>?>(null) }        // selecting the queue: the picks, in order
    var menu by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyPicks by remember { mutableStateOf<List<Int>?>(null) }  // selecting played tracks: their places, in order
    var ask by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LaunchedEffect(zoneId, changed, reload) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.queue(zoneId) } }
            .onSuccess { if (it != view) { view = it; picks = null; historyPicks = null }; failed = null }
            .onFailure { if (view == null) failed = "Couldn't load queue: " + (com.musicd.mallard.client.Failure.said(it, "")) }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // A change to the room: what the server says, the queue asked for again a moment after.
    fun act(done: String?, block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
            r.exceptionOrNull()?.let { toast(com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server")) } ?: done?.let { toast(it) }
            delay(600); reload++
        }
    }
    val v = view
    val summary = failed ?: v?.summary ?: "Loading queue…"

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            // The page's summary line (.queue-summary): 13px dim, 28 tall at least, 36 under the tabs' line and 10 over the list; its tools small pills.
            Row(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 10.dp).defaultMinSize(minHeight = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (v?.empty == true) "" else summary, Modifier.parity("queue.summary").weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val p = picks
                if (p != null && v != null) {
                    Box {
                        Box(Modifier.parity("queue.select-menu", Contract.label("queue.select-menu")).pill().clickable { menu = !menu }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { PillWord("⋯") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(if (p.isEmpty()) "Tap tracks to select them" else "${p.size} selected", Modifier.parity("queue.select-title").padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val names = p.mapNotNull { id -> v.items.firstOrNull { it.id == id }?.title }
                            val what = QueueView.what(names)
                            // The picks played now or next, or (asked first) taken out.
                            val runPicks = { kind: String, done: String ->
                                menu = false
                                val send = { act(done) { it.queueRemove(zoneId, p) }; picks = null }
                                if (kind == "remove") ask = QueueView.askRemove(names) to send else send()
                            }
                            DropdownMenuItem(text = { Text(Contract.label("queue.remove")) }, enabled = p.isNotEmpty(),
                                modifier = Modifier.parity("queue.remove", Contract.label("queue.remove")), onClick = { runPicks("remove", "Removed $what") })
                        }
                    }
                    Box(Modifier.parity("queue.done", Contract.label("queue.done")).pill().clickable { picks = null }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { PillWord(Contract.label("queue.done")) }
                }
            }
        }
        if (v == null) return@LazyColumn
        if (v.empty) {
            item { Text("Queue is empty.", Modifier.parity("queue.empty").fillMaxWidth().padding(vertical = 30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }
        itemsIndexed(v.items) { i, it -> Column {
            if (i == 0) Row(Modifier.parity("queue.divider").fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                Text("Now playing", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
            }
            val p = picks
            val at = p?.indexOf(it.id) ?: -1
            // The track playing (the first) on the raised ground, as the page's .is-current row.
            QueueRow(client, it.imageKey, it.title, it.subtitle, it.lengthSeconds?.let { s -> NowPlayingView.clock(s.toDouble()) }.orEmpty(), if (at >= 0) at + 1 else null, false,
                Modifier.parity("queue.track", Contract.label("queue.track", 0, it.title)).let { m -> if (i == 0) m.background(paletteColor(Palette.BG_ELEV_2)) else m }.combinedClickable(
                    onLongClick = { if (p == null) picks = listOf(it.id) },
                    onClick = {
                        when {
                            p != null -> picks = if (at >= 0) p - it.id else p + it.id
                            i > 0 -> ask = QueueView.askPlayFrom(it.title) to {
                                view = v.playedFrom(i)   // "Now playing" moves at once; the room's own queue after
                                act(null) { c -> c.playFromHere(zoneId, it.id) }
                            }
                        }
                    }))
        } }
    }
    ask?.let { (message, yes) -> ConfirmDialog(message) { ok -> ask = null; if (ok) yes() } }
}

/** One row of the queue: its cover, title and artist, its length; [pick], its place in a selection. */
@Composable
private fun QueueRow(client: () -> ServerClient?, imageKey: String?, title: String, sub: String, length: String, pick: Int?, skipped: Boolean, modifier: Modifier) {
    var cover by remember(imageKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageKey) {
        val c = client() ?: return@LaunchedEffect
        if (imageKey == null) return@LaunchedEffect
        cover = withContext(Dispatchers.IO) { runCatching { c.bytes(c.imageUrl(imageKey, 120)).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull() }
    }
    // The page's row (.queue-list li): 10 of padding round a 52 cover with corners of 3, 14 to the words, the title 15px at 600 over the rest at 12, the length 12 faint.
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pick != null) Text("$pick", Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
            color = MaterialTheme.colorScheme.onSecondary, fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600, color = paletteColor(Palette.MODAL_TEXT), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(length, fontSize = 12.sp, lineHeight = 17.sp, color = if (skipped) MaterialTheme.colorScheme.outline else paletteColor(Palette.TEXT_FAINT))
    }
}

/** One of the queue's small tools (.q-tool): a dim ring 25 tall, its word 12px in 12 at each end. */
@Composable
private fun Modifier.pill(): Modifier = height(25.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))

/** A tool's word. */
@Composable
private fun PillWord(text: String) = Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
