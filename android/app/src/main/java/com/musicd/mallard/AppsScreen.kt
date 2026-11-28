package com.musicd.mallard

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AppEntry
import com.musicd.mallard.client.AppMenu
import com.musicd.mallard.client.AppRow
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * LMS plugins, natively: the list of apps (or radio) LMS offers the player, and any plugin's menus drawn generically
 * from what MusicD LMS Remote's engine sends (lib/apps.js) — folders, playable items with Play / Play next / Add / More,
 * search boxes, choices, links, and a sign-in prompt as a notice. No plugin has code of its own here, so every plugin
 * installed on the server works, as it does in Material. Drawn in Mandarin's style: the brass back disc, the serif title,
 * covers in a grid where a menu is mostly albums, the mini player and the row along the foot.
 */

/** A plugin's picture: a cover key the server's /api/image serves, or a path on the LMS host it proxies. */
@Composable
private fun rememberAppImage(imageKey: String?, imagePath: String?, px: Int, client: () -> ServerClient?): ImageBitmap? {
    val byKey = rememberTileCover(imageKey, px, client)
    val byPath = rememberImage(if (imageKey == null && imagePath != null) client()?.absoluteUrl(imagePath) else null, client)
    return byKey ?: byPath
}

@Composable
internal fun AppPicture(imageKey: String?, imagePath: String?, size: Dp, client: () -> ServerClient?, round: Dp = 8.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(64, 600)
    val img = rememberAppImage(imageKey, imagePath, px, client)
    Box(Modifier.size(size).clip(RoundedCornerShape(round)).background(paletteColor(Palette.BG_ELEV_2))) {
        img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

// ---- the list of plugins ----------------------------------------------------------------------------------------

/** The plugins LMS offers ([kind] "app" or "radio"), each opening its menu. */
@Composable
internal fun AppsScreen(kind: String, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onOpen: (AppEntry) -> Unit,
                        onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    var entries by remember(kind) { mutableStateOf<List<AppEntry>?>(null) }
    var failed by remember(kind) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(kind, reload) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { c.apps(zone, refresh = reload > 0) } }
            .onSuccess { l -> entries = if (kind == "radio") l.radio else l.apps; failed = null }
            .onFailure { failed = Failure.said(it, "Couldn't ask the server") }
    }
    val title = if (kind == "radio") "Radio" else "Apps"
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val list = entries
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item { WallHead(title, WallPrefs(), (failed ?: if (list != null && list.isEmpty()) (if (kind == "radio") "LMS offers no radio here." else "No plugins are installed on your server, or none can play on this player.") else null)?.let { it to (failed != null) }) }
            item { BetaNote(Modifier.padding(bottom = 10.dp)) }   // plugins are a work in progress (BetaTag.kt)
            if (list == null && failed == null) item { Text("Loading…", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(list.orEmpty()) { i, e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(e) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppPicture(e.imageKey, e.imagePath, 44.dp, client)
                    Text(e.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.W500, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(R.drawable.st_caret), contentDescription = null, tint = paletteColor(Palette.TEXT_FAINT),
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f })
                }
                if (i < list!!.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
            }
            item {
                Text("Plugin settings and sign-ins are on the LMS server's settings pages.", Modifier.padding(top = 18.dp),
                    color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        WallBack(onBack)
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            TouchNav("", onTap = onNav)
        }
    }
}

// ---- one of a plugin's menus ------------------------------------------------------------------------------------

/**
 * One of a plugin's menus by its [token]; with [term], the search that token takes, run with it. Paged as it scrolls.
 * A tap does what the row says: open a folder ([onMenu]), play, ask for a search term, set a choice, open a link. A
 * playable row's ⋯ (or a long press) offers Play now, Play next, Add to queue and More (its context menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppMenuScreen(token: String, title: String, term: String, zoneId: String, client: () -> ServerClient?,
                           onBack: () -> Unit, onMenu: (token: String, title: String, term: String) -> Unit,
                           onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    val rows = remember(token, term) { mutableStateListOf<AppRow>() }
    var menu by remember(token, term) { mutableStateOf<AppMenu?>(null) }
    var failed by remember(token, term) { mutableStateOf<String?>(null) }
    var loading by remember(token, term) { mutableStateOf(false) }
    var reload by remember(token, term) { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<AppRow?>(null) }   // a search row waiting for its term
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // A page from [start]: the first replaces, the rest append. An expired token goes back (its menu is gone on the server).
    suspend fun page(start: Int) {
        val c = client() ?: return
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching { if (term.isNotEmpty()) c.appSearch(token, term, zone, start) else c.appMenu(token, zone, start) } }
        loading = false
        r.onSuccess { m -> if (start == 0) rows.clear(); rows.addAll(m.rows); menu = m; failed = null }
        r.onFailure { e ->
            if (e is ServerClient.ServerException && e.expired) { toast("That menu has expired — going back"); onBack(); return }
            failed = Failure.said(e, "The plugin didn't answer")
        }
    }
    LaunchedEffect(token, term, reload) { page(0) }

    /** A row's action run in the player, then what the plugin asks: Now playing, the menu again, back, or Home. */
    fun act(actionToken: String?, said: String?) {
        val c = client() ?: return
        if (actionToken == null) return
        if (zone.isEmpty()) { toast("Pick a player first"); return }
        if (busy) return else busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.appAct(actionToken, zone) } }
            busy = false
            r.onFailure { e ->
                if (e is ServerClient.ServerException && e.expired) { toast("That menu has expired — going back"); onBack() }
                else toast(Failure.said(e, "The plugin didn't answer"))
            }
            r.onSuccess { a ->
                RoomPoke.poke()
                a.message?.let { toast(it) } ?: said?.let { toast(it) }
                when (a.nextWindow) {
                    "nowPlaying" -> onNowPlaying(zone)
                    "refresh", "refreshOrigin" -> reload++
                    "parent", "grandparent", "parentNoRefresh" -> onBack()
                    "home" -> onNav("home")
                }
            }
        }
    }
    fun tap(row: AppRow) {
        when (row.tap) {
            "open" -> row.go?.let { onMenu(it, row.title, "") }
            "play" -> act(row.play, null)
            "search" -> asking = row
            "choose" -> act(row.doToken, null)
            "link" -> row.weblink?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
        }
    }

    val m = menu
    val heading = m?.title?.takeIf { it.isNotEmpty() } ?: title
    val notice = (failed ?: m?.notice ?: if (m != null && rows.isEmpty()) (if (term.isNotEmpty()) "Nothing found for “$term”." else "Nothing here.") else null)
        ?.let { it to (failed != null) }
    val more = m != null && rows.size < m.total
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (m?.grid == true) {
            val grid = rememberLazyGridState()
            LaunchedEffect(grid, more) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (more && !loading && last >= rows.size - 8) page(rows.size) }
            }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(horizontal = 18.dp), grid,
                contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { WallHead(heading, WallPrefs(), notice) }
                itemsIndexed(rows) { _, row -> AppTile(row, client, onTap = { tap(row) }, onAct = ::act, onMore = { t -> onMenu(t, row.title, "") }) }
            }
        } else {
            val list = rememberLazyListState()
            LaunchedEffect(list, more) {
                snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (more && !loading && last >= rows.size - 8) page(rows.size) }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), list, contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
                item { WallHead(heading, WallPrefs(), notice) }
                if (m == null && failed == null) item { Text("Loading…", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(rows) { i, row ->
                    AppListRow(row, client, onTap = { tap(row) }, onAct = ::act, onMore = { t -> onMenu(t, row.title, "") })
                    if (i < rows.lastIndex) HorizontalDivider(color = paletteColor(Palette.PANEL_HAIRLINE))
                }
                if (loading && rows.isNotEmpty()) item { Text("Loading more…", Modifier.padding(vertical = 14.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp) }
            }
        }
        WallBack(onBack)
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            TouchNav("", onTap = onNav)
        }
    }
    asking?.let { row -> SearchDialog(row.prompt ?: row.title, onCancel = { asking = null }) { typed -> asking = null; row.search?.let { onMenu(it, typed, typed) } } }
}

/** The ⋯ of a playable row: Play now, Play next, Add to queue, and More (its context menu), each only where the plugin offers it. */
@Composable
private fun RowMenu(row: AppRow, open: Boolean, onDismiss: () -> Unit, onAct: (String?, String?) -> Unit, onMore: (String) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = paletteColor(Palette.BG_ELEV_2)) {
        row.play?.let { t -> DropdownMenuItem(text = { Text("Play now") }, onClick = { onDismiss(); onAct(t, null) }) }
        row.insert?.let { t -> DropdownMenuItem(text = { Text("Play next") }, onClick = { onDismiss(); onAct(t, "Playing next") }) }
        row.add?.let { t -> DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onDismiss(); onAct(t, "Added to queue") }) }
        row.more?.let { t -> DropdownMenuItem(text = { Text("More…") }, onClick = { onDismiss(); onMore(t) }) }
    }
}

private fun AppRow.hasMenu() = play != null || add != null || insert != null || more != null

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppListRow(row: AppRow, client: () -> ServerClient?, onTap: () -> Unit, onAct: (String?, String?) -> Unit, onMore: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val enabled = row.tap != null
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(enabled = enabled || row.hasMenu(), onClick = { if (enabled) onTap() else if (row.hasMenu()) open = true },
        onLongClick = { if (row.hasMenu()) open = true }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when (row.kind) {
            "choice" -> Box(Modifier.size(22.dp).clip(if (row.choice == "checkbox") RoundedCornerShape(5.dp) else CircleShape)
                .border(2.dp, if (row.selected) MaterialTheme.colorScheme.primary else paletteColor(Palette.TEXT_FAINT), if (row.choice == "checkbox") RoundedCornerShape(5.dp) else CircleShape)
                .background(if (row.selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                if (row.selected) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp)
            }
            "search" -> Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(paletteColor(Palette.BG_ELEV_2)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.tn_search), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            else -> if (row.hasPicture) AppPicture(row.imageKey, row.imagePath, 48.dp, client)
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, color = if (row.kind == "text") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (row.kind == "text") FontWeight.W400 else FontWeight.W500,
                maxLines = if (row.kind == "text") 8 else 2, overflow = TextOverflow.Ellipsis)
            row.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (row.hasMenu()) Box {
            Icon(painterResource(R.drawable.np_more), contentDescription = "More", tint = paletteColor(Palette.TEXT_DIM),
                modifier = Modifier.clip(CircleShape).clickable { open = true }.padding(6.dp).size(22.dp))
            RowMenu(row, open, { open = false }, onAct, onMore)
        } else if (row.tap == "open") Icon(painterResource(R.drawable.st_caret), contentDescription = null, tint = paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(row: AppRow, client: () -> ServerClient?, onTap: () -> Unit, onAct: (String?, String?) -> Unit, onMore: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = { if (row.tap != null) onTap() else if (row.hasMenu()) open = true }, onLongClick = { if (row.hasMenu()) open = true })) {
            val px = 400
            val img = rememberAppImage(row.imageKey, row.imagePath, px, client)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.COVER_RING), RoundedCornerShape(12.dp))) {
                img?.let { Image(it, contentDescription = row.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Text(row.title, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        RowMenu(row, open, { open = false }, onAct, onMore)
    }
}

/** A plugin's search: the term asked for in its own words. */
@Composable
private fun SearchDialog(prompt: String, onCancel: () -> Unit, onSearch: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, containerColor = paletteColor(Palette.BG_ELEV),
        title = { Text(prompt, color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = 20.sp) },
        text = {
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(typed, { typed = it }, Modifier.fillMaxWidth().clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp), singleLine = true, textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 16.sp),
                cursorBrush = SolidColor(paletteColor(Palette.ACCENT)), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (typed.isNotBlank()) onSearch(typed.trim()) }))
        },
        confirmButton = { TextButton(onClick = { if (typed.isNotBlank()) onSearch(typed.trim()) }) { Text("Search", color = MaterialTheme.colorScheme.secondary) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } })
}
