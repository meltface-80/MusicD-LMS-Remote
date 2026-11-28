package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LogoCandidate
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.labelAlbums
import com.musicd.mallard.client.logoCandidates
import com.musicd.mallard.client.setLabelLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * One record label (the web app's label view): its albums as a wall, in the order Settings → Labels sets, and Set logo —
 * the logos Discogs has for it (a tap keeps one) or any picture's address pasted.
 */
@Composable
internal fun LabelScreen(name: String, zoneId: String, client: () -> ServerClient?, nav: CollectionNav, onAlbum: (Long, String, NavList) -> Unit) {
    val context = LocalContext.current
    val prefs = WallPrefs.parse(NativeShell.prefs)
    val (cols, coverPx) = wallColumns(prefs)
    var albums by remember(name) { mutableStateOf<List<AlbumSummary>?>(null) }
    var failed by remember(name) { mutableStateOf<String?>(null) }
    var logo by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(name, reload) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.labelAlbums(name, Store.labelOrder(context)) } }
            .fold({ albums = it; failed = null }, { failed = "Couldn't load albums: " + Failure.said(it, "no answer") })
    }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    CollectionFrame(zoneId, client, nav) { zone ->
        val shown = albums.orEmpty()
        AlbumGrid(listOf(GridBlock(null, shown)), prefs, cols, coverPx, client, albums != null, emptySet(), grid, list,
            onOpen = { i -> onAlbum(shown[i].offset, zone, NavList(shown.map { NavItem(it.offset, it.title) }, i)) }, onLongPress = {}) {
            Column {
                WallHead(name, prefs, (failed ?: if (albums?.isEmpty() == true) "No albums found for this label." else null)?.let { it to (failed != null) })
                if (shown.isNotEmpty()) Text(if (shown.size == 1) "1 album" else "${shown.size} albums", Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
                Row(Modifier.padding(top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActPill("Set logo", false) { logo = true }
                }
            }
        }
    }
    if (logo) LogoSheet(name, client, onClose = { logo = false })
}

/** Set logo: Discogs' logos for the label (a tap keeps one), or an image's address pasted, Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoSheet(label: String, client: () -> ServerClient?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var found by remember { mutableStateOf<List<LogoCandidate>?>(null) }
    var hint by remember { mutableStateOf("Searching Discogs…") }
    var url by remember { mutableStateOf("") }
    LaunchedEffect(label) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.logoCandidates(label) } }
            .fold({ found = it; hint = if (it.isEmpty()) "No logos found on Discogs" else "" }, { found = emptyList(); hint = Failure.said(it, "No logos found on Discogs") })
    }
    fun save(u: String) {
        val c = client() ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.setLabelLogo(label, u) } }.fold({ toast("Logo saved"); onClose() }, { toast(Failure.said(it, "Couldn't save the logo")) })
        }
    }
    SheetPanel(onClose = onClose, head = {
        SheetTitle("Set logo", Modifier)
        SheetClose(Modifier.clip(CircleShape).clickable(onClick = onClose))
    }, foot = null) {
        if (hint.isNotEmpty()) Text(hint, Modifier.padding(bottom = 10.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
        val f = found.orEmpty()
        if (f.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (cand in f) Column(Modifier.width(96.dp).clickable { save(cand.img) }) {
                val img = rememberWebImage(cand.img)
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), RoundedCornerShape(8.dp))) {
                    if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp).padding(6.dp))
                }
                Text(cand.title, Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(url, { url = it }, Modifier.weight(1f).clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp), singleLine = true, textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 15.sp),
                cursorBrush = SolidColor(paletteColor(Palette.ACCENT)), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                decorationBox = { inner -> Box { if (url.isEmpty()) Text("Or paste any image URL…", color = paletteColor(Palette.TEXT_FAINT), fontSize = 15.sp); inner() } })
            Button(onClick = { if (url.isNotBlank()) save(url.trim()) }, colors = footColors(true)) { Text("Save") }
        }
    }
}
