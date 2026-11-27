package com.musicd.mallard

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Artist
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.ArtistsWall
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Artists wall natively (bug list item 130; docs/specs/screens.json, artists; the page's artists-wall.js): the
 * row's Artists. The heading with how many, the bar's Back; Sort at the top right (A to Z, most albums, at random;
 * kept); every act an album of the library's is credited to, as a round picture (their own once the server has found
 * one, else their first album's cover) with their name and how many albums, in the album wall's own columns and sizes
 * (Settings → UI Settings). Read a page at a time as the grid nears its end; the row's Search finds an artist by part
 * of the name (the server's find, so the whole library). A tap opens the artist's page ([onArtist]).
 */
@Composable
internal fun ArtistsScreen(prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onArtist: (String) -> Unit,
                           onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val saved = remember { context.getSharedPreferences("artists", Context.MODE_PRIVATE) }
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    val (cols, _) = wallColumns(prefs)
    var sort by remember { mutableStateOf(saved.getString("sort", "az") ?: "az") }
    val seed = remember { (System.currentTimeMillis() % 100_000).toInt() + 1 }
    var finding by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(0) }
    val grid = rememberLazyGridState()
    BackHandler { if (finding) { finding = false; find = "" } else onBack() }
    // A new order or find starts the wall again from its top.
    LaunchedEffect(sort, find) { artists = emptyList(); total = null; asked = 0; grid.scrollToItem(0) }
    // The next page, when the grid nears its end (and the first at once).
    val near by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= artists.size - cols * 3 } }
    // Failed, asked again when the server answers again or in a while (item 157: only a scroll asked again).
    var asks by remember { mutableStateOf(0) }
    AskAgain(failed != null) { asks++ }
    LaunchedEffect(sort, find, near, artists.size, asks) {
        val t = total
        if (!near || (t != null && !ArtistsWall.more(artists.size, t)) || asked > artists.size) return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        asked = artists.size + 1
        val got = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.artistsWall(ArtistsWall.path(sort, artists.size, find, seed)) } }
        got.onSuccess { p -> artists = artists + p.artists; total = p.total; failed = null }
            .onFailure { failed = "Couldn't load artists: " + com.musicd.mallard.client.Failure.said(it, "no answer"); asked = 0 }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), state = grid, contentPadding = WALL_PADDING,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (finding) Box(Modifier.height(2.dp))
                else WallHead(ArtistsWall.heading(total), prefs, failed?.let { it to true })
            }
            items(artists.size, key = { "a" + it + artists[it].name }) { i ->
                val a = artists[i]
                ArtistTile(Modifier.parity("artists.artist", Contract.label("artists.artist", 0, a.name)).clickable { onArtist(ArtistAct.of(a.name, a.artistId)) }, a, prefs, client)
            }
            if (find.isNotBlank() && total == 0) item("none", span = { GridItemSpan(maxLineSpan) }) {
                Text(ArtistsWall.none(find), Modifier.parity("artists.none").fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            }
        }
        WallBack(onBack)
        if (finding) ArtistFind(find, onFind = { find = it }, onClear = { if (find.isNotEmpty()) find = "" else finding = false })
        else ArtistsSort(sort) { sort = ArtistsWall.nextSort(sort); saved.edit().putString("sort", sort).apply() }
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            TouchNav("artists", onTap = { what -> if (what == "search") finding = true else onNav(what) })
        }
    }
}

/** An act's tile: their picture in a circle (their own, else their first album's cover), their name, how many albums. */
@Composable
private fun ArtistTile(modifier: Modifier, a: Artist, prefs: WallPrefs, client: () -> ServerClient?) {
    val title = (12 * prefs.text).sp; val titleLine = (15 * prefs.text).sp
    val sub = (10.5 * prefs.text).sp; val subLine = (13.65 * prefs.text).sp
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.parity("artists.artist-art").fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2))) {
            val art = rememberTileCover(ArtistsWall.art(a), 300, client)
            if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(a.name.take(1).uppercase(), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 28.sp, fontWeight = FontWeight.W600)
        }
        Text(a.name, Modifier.parity("artists.artist-name").padding(top = 10.dp).lineBox(title, titleLine), color = MaterialTheme.colorScheme.onSurface,
            fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(ArtistsWall.subtitle(a), Modifier.parity("artists.artist-subtitle").padding(top = 1.dp).lineBox(sub, subLine), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = sub, lineHeight = subLine, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Sort, top right: the brass pill with the order it is in; a tap goes to the next (.artists-sort-btn). */
@Composable
private fun BoxScope.ArtistsSort(sort: String, onTap: () -> Unit) {
    Box(Modifier.parity("artists.sort", Contract.label("artists.sort", ArtistsWall.SORTS.indexOf(sort).coerceAtLeast(0))).align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)
        .height(40.dp).defaultMinSize(minWidth = 44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onTap).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(ArtistsWall.sortWord(sort), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W700, letterSpacing = 0.02.em)
    }
}

/** Finding an artist: the field in the bar beside Back, its glass and its ✕ (the first tap clears the words, the next closes it). */
@Composable
private fun BoxScope.ArtistFind(find: String, onFind: (String) -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 12.dp, end = 14.dp).fillMaxWidth().height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(find, onFind, Modifier.parity("artists.find-field", Contract.label("artists.find-field")).weight(1f).focusRequester(focus), singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 19.sp), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.tn_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (find.isEmpty()) Text("Find an artist…", color = paletteColor(Palette.TEXT_FAINT), fontSize = 16.sp, lineHeight = 19.sp, maxLines = 1)
                        field()
                    }
                }
            })
        Box(Modifier.parity("artists.find-clear", Contract.label("artists.find-clear")).size(32.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.np_close), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
