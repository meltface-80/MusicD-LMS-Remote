package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.NowPlayingView
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Playlist
import com.musicd.mallard.client.PlaylistTrack
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.deletePlaylist
import com.musicd.mallard.client.playPlaylist
import com.musicd.mallard.client.playPlaylistFrom
import com.musicd.mallard.client.playlistTracks
import com.musicd.mallard.client.playlists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Playlists (the web app's side-menu row; public/app.js initPlaylists): LMS's stored playlists, each a mosaic of its
 * first covers with its track count; a playlist's page with Play now and Add to queue, its tracks (each with its cover and
 * artist · album, a tap playing the playlist from there), and Delete — offered only for the owner's own playlist file
 * (core Playlist.own), with a blunt warning, because LMS deletes the file with no copy.
 */

@Composable
internal fun PlaylistsScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav, onOpen: (Playlist) -> Unit) {
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.playlists() } }.fold({ lists = it; failed = null }, { failed = Failure.said(it, "Couldn't ask the server") })
    }
    CollectionFrame(zoneId, client, nav) {
        val list = lists
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item { WallHead("Playlists", WallPrefs(), (failed ?: if (list != null && list.isEmpty()) "No playlists on your server yet." else null)?.let { it to (failed != null) }) }
            if (list == null && failed == null) item { ListNote("Loading…") }
            itemsIndexed(list.orEmpty()) { i, p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(p) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoverMosaic(p.art, 56.dp, client)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        p.tracks?.let { Text(if (it == 1) "1 track" else "$it tracks", color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp) }
                    }
                }
                if (i < list!!.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
            }
        }
    }
}

/** One playlist ([id], [title]; [own]: the owner's own file, so Delete is offered). */
@Composable
internal fun PlaylistScreen(id: String, title: String, own: Boolean, zoneId: String, client: () -> ServerClient?, nav: CollectionNav) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var tracks by remember { mutableStateOf<List<PlaylistTrack>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.playlistTracks(id) } }.fold({ tracks = it; failed = null }, { failed = Failure.said(it, "Couldn't read the playlist") })
    }
    CollectionFrame(zoneId, client, nav) { zone ->
        fun run(said: String, call: (ServerClient) -> Unit) {
            val c = client() ?: return
            if (zone.isEmpty()) { toast("Pick a player first"); return }
            scope.launch { withContext(Dispatchers.IO) { runCatching { call(c) } }.fold({ toast(said) }, { toast(Failure.said(it, "Couldn't play that")) }) }
        }
        val list = tracks
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item {
                WallHead(title, WallPrefs(), failed?.let { it to true })
                list?.let { Text(if (it.size == 1) "1 track" else "${it.size} tracks", Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp) }
                Row(Modifier.padding(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActPill("Play now", true, enabled = !list.isNullOrEmpty()) { run("Playing $title") { it.playPlaylist(id, zone, "play_now") } }
                    ActPill("Add to queue", false, enabled = !list.isNullOrEmpty()) { run("Added $title to the queue") { it.playPlaylist(id, zone, "queue") } }
                    if (own) ActPill("Delete", false) { asking = true }
                }
            }
            if (list == null && failed == null) item { ListNote("Loading…") }
            if (list != null && list.isEmpty()) item { ListNote("This playlist is empty.") }
            itemsIndexed(list.orEmpty()) { i, t ->
                TrackLine(t, client) { run("Playing from ${t.title}") { it.playPlaylistFrom(id, zone, t.index) } }
                if (i < list!!.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
            }
        }
    }
    if (asking) ConfirmDialog("Delete “$title” (${tracks?.size ?: 0} tracks) from the server? Its playlist file is removed and can't be brought back. Your music isn't touched.") { ok ->
        asking = false
        val c = client()
        if (ok && c != null) scope.launch {
            // A failed delete is indeterminate (a stale id makes LMS close the connection, and the id goes stale once the
            // playlist is already gone), so either way the list is read again rather than either outcome claimed.
            val r = withContext(Dispatchers.IO) { runCatching { c.deletePlaylist(id) } }
            toast(if (r.isSuccess) "Deleted $title" else "The server didn't confirm — the list is read again")
            nav.onBack()
        }
    }
}

/** A playlist's track: its cover (playlists mix albums), title, artist · album, and length; a tap plays from it. */
@Composable
private fun TrackLine(t: PlaylistTrack, client: () -> ServerClient?, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onTap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val px = with(LocalDensity.current) { 44.dp.roundToPx() }.coerceAtLeast(64)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
            rememberTileCover(t.imageKey, px, client)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.line.isNotEmpty()) Text(t.line, color = paletteColor(Palette.TEXT_DIM), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        t.duration?.let { Text(NowPlayingView.clock(it), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp) }
    }
}
