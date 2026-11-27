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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumMerge
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.albumMerges
import com.musicd.mallard.client.unmerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Merged albums (the web app's side-menu row; public/app.js initMergedAlbums): every multi-disc set merged into one album,
 * its cover, name, artist and its discs, each with Unmerge. A merge whose albums have left the library still lists, so it
 * can be undone. Merges are made from a wall's multi-select (Mallard's WallSelect), as on the web.
 */

@Composable
internal fun MergesScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav, onAlbum: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var merges by remember { mutableStateOf<List<AlbumMerge>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<AlbumMerge?>(null) }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.albumMerges() } }.fold({ merges = it; failed = null }, { failed = Failure.said(it, "Couldn't ask the server") })
    }
    fun unmerge(m: AlbumMerge) {
        val c = client() ?: return
        busy = m.id
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.unmerge(m.id) } }
                .fold({ toast("Unmerged — ${m.title} is listed as its separate albums again"); reload++ }, { toast(Failure.said(it, "Couldn't unmerge")) })
            busy = ""
        }
    }
    CollectionFrame(zoneId, client, nav) {
        val list = merges
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item {
                WallHead("Merged albums", WallPrefs(), (failed ?: if (list != null && list.isEmpty()) "Nothing merged yet. Hold an album on any wall to pick the discs of a set, then choose Merge." else null)?.let { it to (failed != null) })
            }
            if (list == null && failed == null) item { ListNote("Loading…") }
            itemsIndexed(list.orEmpty()) { i, m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).let { r -> m.offset?.let { o -> r.clickable { onAlbum(o) } } ?: r }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoverMosaic(listOfNotNull(m.imageKey), 56.dp, client)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (m.artist.isNotEmpty()) Text(m.artist, color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val here = if (m.present >= m.partCount) "${m.partCount} albums merged" else "${m.present} of ${m.partCount} albums in the library now"
                        Text(here, color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp)
                    }
                    ActPill(if (busy == m.id) "Unmerging…" else "Unmerge", false, enabled = busy.isEmpty()) { asking = m }
                }
                if (i < list!!.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
            }
        }
    }
    asking?.let { m -> ConfirmDialog("Unmerge ${m.title}? Its ${m.partCount} albums are listed separately again.") { ok -> asking = null; if (ok) unmerge(m) } }
}
