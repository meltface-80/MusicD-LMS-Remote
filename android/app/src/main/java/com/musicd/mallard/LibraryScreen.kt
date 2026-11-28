package com.musicd.mallard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Facets
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.LibraryView
import com.musicd.mallard.client.LiveEdit
import com.musicd.mallard.client.LiveSaves
import com.musicd.mallard.client.saveLivePlaylist
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** The wall's view kept as its query string, as the page hands it over. */
private val VIEW_SAVER = Saver<LibraryView, String>(save = { it.query() }, restore = { LibraryView.parse(it) })

/**
 * The Library wall, natively (stage N4 of old/plan-android-native.md; bug list items 51 and 52; a
 * preview: Settings → Setup → UI Settings), drawn as the page's (docs/specs/screens.json, library;
 * style.css, the touch layout): the heading with the count at the top, the bar's Back over the albums top
 * left and Focus and Sort top right, the grid at UI Settings' columns and text size (or its list), paged 60
 * at a time as the end comes near, the banner where the library can't be read or nothing matches, and the
 * mini player and the row along the foot. The page opens it in its own [view] of the library (the sort and
 * the focus, as the query string the page's wall asks with) and to its [prefs]; a change here goes back to
 * the page ([onView]) so its own wall and Home's Library row follow. The row's Search opens the name filter
 * across the top. A tile opens the album's page over it ([onAlbum], with the wall as its list for previous
 * and next), a long press starts selecting (WallSelect.kt), and Back comes back here as it was.
 */
@Composable
internal fun LibraryScreen(viewQuery: String, prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long, String, NavList) -> Unit,
                           onView: (String) -> Unit, onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The view, the filter and the room held through an album's page or Now playing over the wall (item 339): plain
    // remember is dropped when the screen is left, and the wall came back in the view it was opened with.
    var view by rememberSaveable(stateSaver = VIEW_SAVER) { mutableStateOf(LibraryView.parse(viewQuery)) }
    val offline = false
    val wall = remember(view, offline) { LibraryWall.state(view.query(), offline) }
    var zone by rememberSaveable { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    var focusOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var facets by remember { mutableStateOf<Facets?>(null) }
    // A view that comes with a name filter shows it in its field, to see and clear (item 338).
    var filtering by rememberSaveable { mutableStateOf(view.prefix.isNotEmpty()) }
    var typed by rememberSaveable { mutableStateOf(view.prefix) }
    var selecting by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    val (cols, coverPx) = wallColumns(prefs)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // The view changed: the wall afresh, and the page told.
    fun change(v: LibraryView) { if (v != view) { view = v; onView(v.query()) } }
    // A Dynamic Playlist's rules being edited (its Edit rules, LiveEditing): shown here without being kept as the Library's
    // own view, which comes back when the sheet is dismissed without Save or Show (the page's openLibFocusSheet(editTarget)).
    var editing by remember { mutableStateOf<LiveEdit?>(null) }
    var before by remember { mutableStateOf<LibraryView?>(null) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { LiveEditing.take()?.let { e -> before = view; view = e.view; editing = e; focusOpen = true } }
    fun saveLive(name: String) {
        val c = client() ?: return
        val e = editing
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.saveLivePlaylist(LiveSaves.body(name, view, e)) } }.fold({ j ->
                toast(LiveSaves.said(name, j)); focusOpen = false
                if (e != null) { editing = null; onView(view.query()) }
            }, { toast(com.musicd.mallard.client.Failure.said(it, "Couldn't save it")) })
        }
    }
    // Back: a sheet, the field or the selection closes first; then the screen.
    BackHandler { when { focusOpen -> focusOpen = false; sortOpen -> sortOpen = false; filtering -> { filtering = false; typed = ""; change(view.copy(prefix = "")) }; selecting -> { selecting = false; picks = emptyList() }; else -> onBack() } }
    fun more() { val c = client() ?: return; scope.launch { wall.more(c) } }
    LaunchedEffect(wall) { if (wall.needsFirst) more() }
    // A page that failed asked again: at once when the server answers again, else in a while (item 157).
    AskAgain(wall.failed) { more() }
    LaunchedEffect(focusOpen) { if (focusOpen) { val c = client() ?: return@LaunchedEffect; facets = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.facets() }.getOrNull() } ?: facets } }
    // The letters typed narrow the wall once they have settled (the page asks on each keystroke).
    LaunchedEffect(typed) { delay(250); if (filtering && typed.trim() != view.prefix) change(view.copy(prefix = typed.trim())) }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    // The next page as the end comes near: the last tile shown within four rows of the last loaded (the heading is the first item).
    val near by remember(prefs.list, cols) {
        derivedStateOf {
            val last = if (prefs.list) list.layoutInfo.visibleItemsInfo.lastOrNull()?.index else grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            last != null && last >= wall.albums.size + 1 - cols * 4
        }
    }
    LaunchedEffect(near, wall.albums.size) { if (near && wall.loaded) more() }
    // The server's albums moved (item 232, LibraryLive): the wall read again in place while it is near its top and nothing
    // is picked, the new albums first under Recently added; further down it stays put until scrolled back up.
    val atTop by remember(prefs.list, cols) { derivedStateOf { if (prefs.list) list.firstVisibleItemIndex < 8 else grid.firstVisibleItemIndex < cols * 4 } }
    LaunchedEffect(wall, wall.behind, atTop, selecting) {
        if (!wall.behind || !atTop || selecting || !wall.loaded || offline) return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        val head = if (prefs.list) list.firstVisibleItemIndex == 0 else grid.firstVisibleItemIndex == 0
        wall.again(c)
        if (head) { if (prefs.list) list.scrollToItem(0) else grid.scrollToItem(0) }
    }
    fun pick(i: Int) { val a = wall.albums[i]; picks = if (picks.any { it.offset == a.offset }) picks.filter { it.offset != a.offset } else picks + a }
    fun open(i: Int) { if (selecting) { pick(i); return }; val a = wall.albums[i]; onAlbum(a.offset, zone, NavList(wall.albums.map { NavItem(it.offset, it.title) }, i)) }
    fun act(what: String) {
        val chosen = picks
        when (what) {
            "clear" -> { selecting = false; picks = emptyList() }
            else -> {
                val c = client() ?: return
                if (what !in NoRoomActs && zone.isEmpty()) { toast("Pick a zone first"); return }
                val made = ServerWrites.write { selectAct(c, chosen, zone, room.zoneName, what) }   // Listen later before Home reads (item 85)
                scope.launch { toast(ServerWrites.await(made).getOrElse { com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server") }); selecting = false; picks = emptyList() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AlbumGrid(listOf(GridBlock(null, wall.albums)), prefs, cols, coverPx, client, wall.loaded, picks.map { it.offset }.toSet(), grid, list,
            onOpen = ::open, onLongPress = { i -> if (!selecting) { selecting = true; pick(i) } }) {
            // The heading: "Library · 1,234 albums", "matching" with a focus on; nothing of the count until the first page.
            val count = NumberFormat.getIntegerInstance().format(wall.total)
            WallHead(if (!wall.loaded) "Library" else "Library · $count" + (if (view.focused) " matching" else "") + (if (wall.total == 1) " album" else " albums"), prefs, wall.banner)
        }
        WallBack(onBack)
        // Top right: the ⋯ while albums are picked; the filter's field while filtering; else Focus and Sort.
        if (picks.isNotEmpty()) AlbumSelectMenu(picks.size, ::act, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), picks)
        else if (filtering) FilterField(typed, { typed = it }, onClear = { if (typed.isEmpty()) { filtering = false; change(view.copy(prefix = "")) } else typed = "" })
        else LibraryPills(view.focusCount, onFocus = { focusOpen = true }, onSort = { sortOpen = true })
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            if (selecting && picks.isEmpty()) SelectBar(onCancel = { selecting = false })
            // Library, tapped where it is already: back to the top, as the page's row does; Search: the wall's own filter, as on the page.
            else TouchNav("library", onTap = { what ->
                when (what) {
                    "library" -> scope.launch { if (prefs.list) list.animateScrollToItem(0) else grid.animateScrollToItem(0) }
                    "search" -> filtering = true
                    else -> onNav(what)
                }
            })
        }
        if (focusOpen) FocusSheet(view, facets, onChange = { v -> if (editing != null) view = v else change(v) }, edit = editing, onEdit = { editing = it },
            onShow = { focusOpen = false; if (editing != null) { editing = null; onView(view.query()) } },
            onClose = { focusOpen = false; if (editing != null) { editing = null; before?.let { view = it } } },
            onSave = { naming = true })
        if (naming) NameDialog(if (editing != null) "Rename this Dynamic Playlist" else "Name this Dynamic Playlist", editing?.name ?: LiveSaves.suggest(view),
            onCancel = { naming = false }) { name -> naming = false; if (name.isBlank()) toast("Give it a name first") else saveLive(name) }
        // A sort chosen closes the sheet; the one on, tapped again, reverses in place (Random reshuffles and closes), as the page's.
        if (sortOpen) SortSheet(view, onChoose = { id -> val same = id == view.sort; val v = view.choose(id); change(v); if (!same || !v.sortOption.hasDir) sortOpen = false }, onClose = { sortOpen = false })
    }
}

/** The name filter's field across the bar (the page's .lib-filter-box): the glass, the letters typed, and × (clears them, or closes the field when there are none). */
@Composable
private fun BoxScope.FilterField(typed: String, onTyped: (String) -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The field is the one control here (its glass and its placeholder are its own): keyed as a whole.
    Row(Modifier.parity("library.filter-input").align(Alignment.TopStart).padding(start = 64.dp, end = 14.dp, top = 12.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(999.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.tn_search), contentDescription = null, tint = paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (typed.isEmpty()) Text("Starts with…", color = paletteColor(Palette.TEXT_FAINT), fontSize = 16.sp, lineHeight = 19.sp)
            BasicTextField(typed, onTyped, Modifier.parity("library.filter-input", Contract.label("library.filter-input")).fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
        }
        Box(Modifier.parity("library.filter-clear", Contract.label("library.filter-clear")).size(28.dp).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
