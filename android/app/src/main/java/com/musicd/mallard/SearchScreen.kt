package com.musicd.mallard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.ExternalSearch
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.SearchResults
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The answers from beyond the library for one query, part by part, as each landed with something (the page's ext-search-wrap). */
internal class ExtKept(val query: String) { val parts = mutableStateMapOf<String, ExternalSearch>() }

/**
 * The search as last typed and answered, kept while the app runs (bug list item 61) so an album opened from it and closed
 * again comes back to the same results, as the page's do; [reset] when the search is opened afresh.
 */
internal object SearchKept {
    var typed by mutableStateOf("")
    /** The library's last answer, and the words it answered. */
    var lib by mutableStateOf<SearchResults?>(null)
    var libFor by mutableStateOf("")
    var status by mutableStateOf("")
    /** The library's own trouble said across the screen (the page's banner): what, and whether as an error. */
    var trouble by mutableStateOf<Pair<String, Boolean>?>(null)
    var ext by mutableStateOf<ExtKept?>(null)
    /** A service's album made a favourite here (or taken off), by its id: the tile's + or ✓ until the search is asked again. */
    val favs = mutableStateMapOf<String, Boolean>()
    var grid by mutableStateOf(LazyGridState())
    /** The field to take the keyboard: on a search opened afresh, not on coming back to it. */
    var focus = false

    fun reset(q: String) { typed = q; stop(); grid = LazyGridState(); focus = true }

    /** The field emptied (the page's stopSearch): nothing found, nothing said. */
    fun stop() { lib = null; libFor = ""; status = ""; trouble = null; ext = null; favs.clear() }
}

/**
 * The search, natively (the Search group of old/plan-android-native.md, S1 and S2; bug list item 61; a preview:
 * Native Search in UI Settings), drawn as the page's (docs/specs/screens.json, search; style.css's search, the touch
 * layout): the field across the top bar with its ✕ and the count beside it; under Home's greeting, the library's artists
 * and labels as chips and its albums as tiles, each under its small heading, asked 120 ms after the last letter; then
 * Qobuz's and Tidal's artists and albums and Pitchfork's reviews, each part asked on its own 200 ms after it and shown as
 * it lands, the last words' kept dim until the new ones replace them; the banner where nothing matches; the mini player
 * and the row along the foot, out of the keyboard's way while the field has it. A tile opens the album's page over it
 * (a service's album made the library's first) and Back comes back to the same results.
 */
@Composable
internal fun SearchScreen(prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long, String, NavList) -> Unit,
                          onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    val (cols, coverPx) = wallColumns(prefs)
    var typing by remember { mutableStateOf(false) }
    val q = SearchKept.typed.trim()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    BackHandler { onBack() }

    // The library: 120 ms after the last letter; while the index is first built, how far, asked again every 350 ms; failed
    // (not reached, or the library still being read), asked again when the server answers again or in a while (item 157).
    var asks by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    AskAgain(failed) { asks++ }
    LaunchedEffect(q, asks) {
        failed = false
        if (q.isEmpty()) { SearchKept.stop(); return@LaunchedEffect }
        delay(120)
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { lib.search(q) } }
            val got = r.getOrNull()
            if (got == null) {
                val busy = (r.exceptionOrNull() as? ServerClient.ServerException)?.status == 503
                SearchKept.lib = null; SearchKept.libFor = q
                if (busy) { SearchKept.status = ""; SearchKept.trouble = "Reading your music library…" to true } else SearchKept.status = "search error"
                failed = r.exceptionOrNull()?.let { com.musicd.mallard.client.Failure.worthAgain(it) } == true
                return@LaunchedEffect
            }
            SearchKept.status = got.status()
            if (got.building) { SearchKept.lib = null; SearchKept.libFor = ""; delay(350); continue }
            SearchKept.trouble = null; SearchKept.lib = got; SearchKept.libFor = q
            return@LaunchedEffect
        }
    }
    // Beyond the library: each part asked on its own 200 ms after the last letter, landing as it answers; this query's take the
    // place of the last one's (kept dim until then); nothing from anywhere, and the last one's go too.
    LaunchedEffect(q) {
        if (q.isEmpty()) return@LaunchedEffect
        delay(200)
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        var added = 0
        coroutineScope {
            for (part in ExternalSearch.PARTS) launch {
                val r = withContext(Dispatchers.IO) { runCatching { lib.searchExternal(q, part) }.getOrNull() } ?: return@launch
                val n = r.count(part)
                if (n == 0) return@launch
                added += n
                val kept = SearchKept.ext?.takeIf { it.query == q } ?: ExtKept(q).also { SearchKept.ext = it }
                kept.parts[part] = r
            }
        }
        if (added == 0 && SearchKept.ext?.query != q) SearchKept.ext = null
    }

    fun openAlbum(albums: List<com.musicd.mallard.client.AlbumSummary>, i: Int) =
        onAlbum(albums[i].offset, zone, NavList(albums.map { NavItem(it.offset, it.title) }, i))
    // A service's album: made the library's (transient until a favourite), then its page as any album's.
    fun openService(service: String, id: String) {
        val c = client() ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.serviceOpen(service, id) } }
                .onSuccess { onAlbum(it, zone, NavList(listOf(NavItem(it, "")), 0)) }.onFailure { toast(com.musicd.mallard.client.Failure.said(it, "Couldn't open that album")) }
        }
    }
    fun toggleFav(service: String, id: String, now: Boolean) {
        val c = client() ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { c.serviceFavourite(service, id, !now).optBoolean("ok", true) }.getOrDefault(false) }
            if (ok) SearchKept.favs[id] = !now else toast("Couldn't change the favourite")
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val lib = SearchKept.lib
        val ext = SearchKept.ext
        val stale = ext != null && ext.query != q
        val noMatch = q.isNotEmpty() && lib != null && SearchKept.libFor == q && lib.empty && ext == null
        val banner = SearchKept.trouble ?: if (noMatch) "No matches for “$q”." to false else null
        LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), state = SearchKept.grid, contentPadding = PaddingValues(start = 14.dp, top = 79.dp, end = 14.dp, bottom = 164.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (banner != null || prefs.greeting) item("head", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    banner?.let { (said, error) -> Banner(said, error, Modifier.padding(top = 14.dp)) }
                    if (prefs.greeting) SearchGreeting()
                }
            }
            var first = true
            fun section(key: String, text: String) { val f = first; first = false; item(key, span = { GridItemSpan(maxLineSpan) }) { SearchSection(text, f) } }
            if (lib != null && q.isNotEmpty()) {
                if (lib.artists.isNotEmpty()) {
                    section("h-artists", "Artists")
                    item("artists", span = { GridItemSpan(maxLineSpan) }) { SearchChips(lib.artists) { n -> onPage("artist", n) } }
                }
                if (lib.albums.isNotEmpty()) {
                    if (lib.artists.isNotEmpty()) section("h-albums", "Albums")
                    val albums = lib.albums
                    items(albums.size, key = { "a" + albums[it].offset }, span = { if (prefs.list) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
                        if (prefs.list) AlbumRow(albums[i], prefs, coverPx, client) { openAlbum(albums, i) } else AlbumTile(albums[i], prefs, coverPx, client) { openAlbum(albums, i) }
                    }
                }
            }
            if (ext != null) for (part in ExternalSearch.PARTS) {
                val r = ext.parts[part] ?: continue
                val dim = Modifier.alpha(if (stale) 0.45f else 1f)
                if (part == "pitchfork") {
                    section("h-pf", "Pitchfork reviews")
                    items(r.pitchfork.size, key = { "pf$it-" + r.pitchfork[it].url }, span = { GridItemSpan(maxLineSpan) }) { i ->
                        val rv = r.pitchfork[i]
                        Box(dim) { ReviewRow(rv) { onPage("review", rv.json()) } }
                    }
                    continue
                }
                val albums = (if (part == "qobuz") r.qobuz else r.tidal) ?: continue
                val artists = if (part == "qobuz") r.qobuzArtists else r.tidalArtists
                section("h-$part", if (part == "qobuz") "Qobuz" else "Tidal")
                if (artists.isNotEmpty()) item("ar-$part", span = { GridItemSpan(maxLineSpan) }) {
                    Box(dim) { ServiceArtistStrip(artists) { ar -> onPage("service-artist", JSONObject().put("service", part).put("id", ar.id).put("name", ar.name).toString()) } }
                }
                items(albums.size, key = { "$part$it-" + albums[it].id }) { i ->
                    val a = albums[i]
                    val fav = SearchKept.favs[a.id] ?: a.favourited
                    Box(dim) { ServiceAlbumTile(a, fav, prefs, onFav = { toggleFav(part, a.id, fav) }, onOpen = { openService(part, a.id) }) }
                }
            }
        }
        FieldBar(SearchKept.typed, SearchKept.status, onTyped = { SearchKept.typed = it }, onTyping = { typing = it }, onSearch = { keyboard?.hide() },
            onClear = { if (SearchKept.typed.isEmpty()) onBack() else SearchKept.typed = "" })
        // Out of the keyboard's way while the field has it, as the page's (android.css, html.is-typing).
        if (!typing) Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            TouchNav("search", onTap = { what -> if (what == "search") scope.launch { SearchKept.grid.animateScrollToItem(0) } else onNav(what) })
        }
    }
}

/**
 * The top bar with the field across it (the touch layout's .tn-search .topbar, the hifi palette): the ground's veil with
 * its hairline under it, the field 40 tall in brass while it has the keyboard (the glass, the words, ✕), and the count
 * under it once there is one (item 372: beside it, it took the field's width as it grew).
 */
@Composable
private fun FieldBar(typed: String, status: String, onTyped: (String) -> Unit, onTyping: (Boolean) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (SearchKept.focus) { SearchKept.focus = false; focus.requestFocus() } }
    val brass = MaterialTheme.colorScheme.primary
    val ring = Color(0x388B6FB0)   // the page's rgba(139,111,176,.22), three wide round the field
    Column(Modifier.fillMaxWidth().background(paletteColor(Palette.BG_VEIL))) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.parity("search.field").weight(1f).height(40.dp)
                .drawBehind { if (focused) { val w = 3.dp.toPx(); drawRoundRect(ring, Offset(-w, -w), Size(size.width + 2 * w, size.height + 2 * w), CornerRadius(size.height)) } }
                .clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, if (focused) brass else MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.tn_search), contentDescription = null, tint = brass, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (typed.isEmpty()) Text("Search…", color = paletteColor(Palette.TEXT_FAINT), fontSize = 16.sp, lineHeight = 19.sp, maxLines = 1)
                    BasicTextField(typed, onTyped, Modifier.parity("search.field", Contract.label("search.field")).fillMaxWidth().focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused; onTyping(it.isFocused) }, singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 19.sp), cursorBrush = SolidColor(brass),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
                }
                Box(Modifier.parity("search.clear", Contract.label("search.clear")).size(28.dp).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                    ClearCross(MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // The count under the field (item 372), never beside it: the field keeps its width whatever the count says.
        if (status.isNotEmpty()) Text(status, Modifier.parity("search.status").padding(start = 28.dp, end = 14.dp, bottom = 8.dp), color = paletteColor(Palette.TEXT_FAINT),
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The ✕ in the field (the page's 18 stroke of 2, round ends), drawn in [color]. */
@Composable
private fun ClearCross(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = 2.dp.toPx(); val a = size.width * 6f / 24f; val b = size.width * 18f / 24f
        drawLine(color, Offset(b, a), Offset(a, b), w, StrokeCap.Round)
        drawLine(color, Offset(a, a), Offset(b, b), w, StrokeCap.Round)
    }
}
