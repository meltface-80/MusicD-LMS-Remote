package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.QobuzAlbum
import com.musicd.mallard.client.QobuzItem
import com.musicd.mallard.client.QobuzWords
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ServiceAlbum
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.qobuzAlbum
import com.musicd.mallard.client.qobuzBrowse
import com.musicd.mallard.client.qobuzFavorite
import com.musicd.mallard.client.qobuzPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Qobuz (the web app's side-menu row while the server's Qobuz plugin is signed in; public/app.js initQobuzBrowse and
 * fetchQobuzAlbumDetail): the plugin's menus walked a level at a time — categories as rows, albums as tiles with the
 * account favourite's ＋ / ✓ — and an album's page: Play Now, Add to queue, ＋ Add to Qobuz / ✓ In Qobuz, its tracks
 * (a tap plays that one). Back steps back a level. An album found by Search opens straight here (QobuzOpen).
 */
internal object QobuzOpen {
    @Volatile var album: QobuzItem? = null
    fun take(): QobuzItem? = album.also { album = null }
}

@Composable
internal fun QobuzScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val prefs = WallPrefs.parse(NativeShell.prefs)
    val path = remember { mutableStateListOf("" to "Browse Qobuz") }        // the levels walked: (item id, title)
    val items = remember { mutableStateListOf<QobuzItem>() }
    var total by remember { mutableStateOf(-1) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf(QobuzOpen.take()) }
    val favs = remember { mutableStateMapOf<String, Boolean>() }
    val grid = rememberLazyGridState()
    suspend fun more() {
        val c = client() ?: return
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        val at = path.last().first
        withContext(Dispatchers.IO) { runCatching { c.qobuzBrowse(at, items.size) } }.fold({ l ->
            if (path.last().first == at) { items.addAll(l.items); total = l.total; notice = l.notice ?: if (l.total == 0) "Nothing here." else null }
        }, { notice = "Couldn't load: " + Failure.said(it, "no answer"); total = items.size })
        loading = false
    }
    LaunchedEffect(path.size) { items.clear(); total = -1; notice = null; more() }
    LaunchedEffect(grid) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= items.size - 8) more() } }
    fun back() { when { album != null -> album = null; path.size > 1 -> path.removeAt(path.lastIndex); else -> nav.onBack() } }
    fun toggleFav(token: String, now: Boolean) {
        val c = client() ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.qobuzFavorite(token, !now) } }.fold({ on -> favs[token] = on; toast(QobuzWords.favourited(on)) }, { toast(Failure.said(it, QobuzWords.EXPIRED)) })
        }
    }
    val inner = CollectionNav(onBack = ::back, onNowPlaying = nav.onNowPlaying, onRoom = nav.onRoom, onNav = nav.onNav, onPage = nav.onPage)
    CollectionFrame(zoneId, client, inner) { zone ->
        val a = album
        if (a != null) { QobuzAlbumPage(a, zone, client, favs[a.token]) { on -> favs[a.token] = on }; return@CollectionFrame }
        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize().padding(horizontal = 14.dp), state = grid, contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    WallHead(path.last().second, prefs, null)
                    if (total < 0 && notice == null) ListNote("Loading…") else notice?.let { ListNote(it) }
                }
            }
            for (row in items) {
                if (row.node) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { path.add(row.itemId to row.title) }.padding(vertical = 14.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(row.title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.W600)
                            Text("›", color = paletteColor(Palette.TEXT_FAINT), fontSize = 20.sp)
                        }
                        HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
                    }
                } else item {
                    val on = favs[row.token] ?: false
                    ServiceAlbumTile(ServiceAlbum("qobuz", row.token, row.title, "", row.subtitle, null, on, row.imageKey), on, prefs,
                        onFav = { if (row.canFavorite) toggleFav(row.token, on) }, onOpen = { album = row }, client = client)
                }
            }
        }
    }
}

/** An album's page in Qobuz (the shared album page's Qobuz mode): its cover, Play Now, Add to queue, the account favourite, its tracks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QobuzAlbumPage(a: QobuzItem, zone: String, client: () -> ServerClient?, knownFav: Boolean?, onFav: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val room = followRoom(zone, client)
    var detail by remember(a.token) { mutableStateOf<QobuzAlbum?>(null) }
    var failed by remember(a.token) { mutableStateOf<String?>(null) }
    LaunchedEffect(a.token) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.qobuzAlbum(a.token) } }.fold({ detail = it; onFav(it.favorite) }, { failed = Failure.said(it, QobuzWords.EXPIRED) })
    }
    fun play(token: String, kind: String) {
        val c = client() ?: return
        if (zone.isEmpty()) { toast("Pick a zone first"); return }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.qobuzPlay(token, zone, kind) } }.fold({ toast(QobuzWords.played(kind, room.zoneName ?: zone)) }, { toast(Failure.said(it, QobuzWords.EXPIRED)) })
        }
    }
    val fav = knownFav ?: detail?.favorite ?: false
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 64.dp, bottom = 176.dp)) {
        val cover = rememberTileCover(a.imageKey, 800, client)
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
            if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(a.title, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = 24.sp, lineHeight = 30.sp)
        if (a.subtitle.isNotEmpty()) Text(a.subtitle, Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 15.sp)
        FlowRow(Modifier.padding(top = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActPill("Play Now", true) { play(a.token, "play_now") }
            if (a.canQueue) ActPill("Add to queue", false) { play(a.token, "queue") }
            if (detail?.canFavorite != false) ActPill(QobuzWords.favourite(fav), false) {
                val c = client() ?: return@ActPill
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { c.qobuzFavorite(a.token, !fav) } }.fold({ on -> onFav(on); toast(QobuzWords.favourited(on)) }, { toast(Failure.said(it, QobuzWords.EXPIRED)) })
                }
            }
        }
        val d = detail
        when {
            failed != null -> ListNote(failed!!, error = true)
            d == null -> ListNote("Loading…")
            d.notice != null && d.tracks.isEmpty() -> ListNote(d.notice.orEmpty())
            else -> for ((i, t) in d.tracks.withIndex()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { play(t.token, "play_now") }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.padding(end = 12.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.artist.isNotEmpty() && t.artist != a.subtitle) Text(t.artist, color = paletteColor(Palette.TEXT_DIM), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    t.duration?.let { s -> Text("%d:%02d".format(s.toInt() / 60, s.toInt() % 60), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp) }
                }
            }
        }
    }
}
