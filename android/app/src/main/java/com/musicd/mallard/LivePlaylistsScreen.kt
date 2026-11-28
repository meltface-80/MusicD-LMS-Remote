package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LivePlaylist
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.liveEditOf
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.deleteLivePlaylist
import com.musicd.mallard.client.livePlaylistPage
import com.musicd.mallard.client.livePlaylists
import com.musicd.mallard.client.playLivePlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Dynamic Playlists (the web app's side-menu row; public/app.js initLivePlaylists): saved Library views, each re-run when
 * opened, so it always holds what matches today. The list (a mosaic, the name, "100 of 1,179 albums"); a playlist's page
 * is its albums as a wall, paged as it scrolls, with Play now, Add to queue and Delete. The rules are made and edited in
 * the Library's Focus, as on the web.
 */

@Composable
internal fun LivePlaylistsScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav, onOpen: (LivePlaylist) -> Unit) {
    var lists by remember { mutableStateOf<List<LivePlaylist>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.livePlaylists() } }.fold({ lists = it; failed = null }, { failed = Failure.said(it, "Couldn't ask the server") })
    }
    CollectionFrame(zoneId, client, nav) {
        val list = lists
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item {
                WallHead("Dynamic Playlists", WallPrefs(), (failed ?: if (list != null && list.isEmpty()) "None yet. In the Library, set a Focus and a Sort, then save it as a Dynamic Playlist — it fills itself from whatever matches." else null)?.let { it to (failed != null) })
            }
            if (list == null && failed == null) item { ListNote("Loading…") }
            itemsIndexed(list.orEmpty()) { i, p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(p) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoverMosaic(p.art, 56.dp, client)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p.count + if (p.order == "random") " · shuffled" else "", color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
                    }
                }
                if (i < list!!.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
            }
        }
    }
}

/** A Dynamic Playlist's rules handed to the Library to edit (its Edit rules): taken once, by the Library's next opening. */
internal object LiveEditing {
    @Volatile private var pending: com.musicd.mallard.client.LiveEdit? = null
    fun set(e: com.musicd.mallard.client.LiveEdit) { pending = e }
    fun take(): com.musicd.mallard.client.LiveEdit? = pending.also { pending = null }
}

/** One Dynamic Playlist ([id], [name]): its albums as a wall, Play now / Add to queue / Edit rules / Delete above them. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LivePlaylistScreen(id: String, name: String, zoneId: String, client: () -> ServerClient?, nav: CollectionNav,
                                onAlbum: (Long, String, NavList) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val prefs = WallPrefs.parse(NativeShell.prefs)
    val (cols, coverPx) = wallColumns(prefs)
    val albums = remember(id) { mutableStateListOf<AlbumSummary>() }
    var total by remember(id) { mutableIntStateOf(-1) }
    var failed by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    suspend fun more() {
        val c = client() ?: return
        if (loading || (total >= 0 && albums.size >= total)) return
        loading = true
        withContext(Dispatchers.IO) { runCatching { c.livePlaylistPage(id, albums.size) } }
            .fold({ p -> albums.addAll(p.albums); total = p.total; failed = null }, { failed = Failure.said(it, "Couldn't read the playlist") })
        loading = false
    }
    LaunchedEffect(id) { more() }
    // The next page as the wall nears its end.
    LaunchedEffect(grid, list) {
        snapshotFlow { maxOf(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { last -> if (last >= albums.size - cols * 2) more() }
    }
    CollectionFrame(zoneId, client, nav) { zone ->
        fun play(kind: String, said: String) {
            val c = client() ?: return
            if (zone.isEmpty()) { toast("Pick a player first"); return }
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { c.playLivePlaylist(id, zone, kind) } }
                    .fold({ cut -> toast(if (cut) "$said — the first 200 albums" else said) }, { toast(Failure.said(it, "Couldn't play that")) })
            }
        }
        AlbumGrid(listOf(GridBlock(null, albums.toList())), prefs, cols, coverPx, client, total >= 0, emptySet(), grid, list,
            onOpen = { i -> val shown = albums.toList(); onAlbum(shown[i].offset, zone, NavList(shown.map { NavItem(it.offset, it.title) }, i)) },
            onLongPress = {}) {
            Column {
                WallHead(name, prefs, (failed ?: if (total == 0) "Nothing in your library matches these rules right now." else null)?.let { it to (failed != null) })
                if (total > 0) Text(if (total == 1) "1 album" else "$total albums", Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
                // Four buttons: on a narrow phone they wrap rather than run off the edge.
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActPill("Play now", true, enabled = total > 0) { play("play_now", "Playing $name") }
                    ActPill("Add to queue", false, enabled = total > 0) { play("queue", "Added $name to the queue") }
                    // Edit rules (the page's): its rules in the Library's Focus, with its order and size; Save changes writes them back.
                    ActPill("Edit rules", false) {
                        val c = client()
                        if (c != null) scope.launch {
                            withContext(Dispatchers.IO) { runCatching { c.liveEditOf(id) } }.fold({ e ->
                                if (e == null) toast("That playlist has gone") else { LiveEditing.set(e); nav.onNav("library") }
                            }, { toast(Failure.said(it, "Couldn't read it")) })
                        }
                    }
                    ActPill("Delete", false) { asking = true }
                }
            }
        }
    }
    if (asking) ConfirmDialog("Delete the Dynamic Playlist “$name”? Only the saved rules go — no music is touched.") { ok ->
        asking = false
        val c = client()
        if (ok && c != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.deleteLivePlaylist(id) } }.fold({ toast("Deleted $name"); nav.onBack() }, { toast(Failure.said(it, "Couldn't delete it")) })
        }
    }
}
