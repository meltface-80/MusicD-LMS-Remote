package com.musicd.mallard

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.LocalView
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallFilter
import com.musicd.mallard.client.WallPrefs
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The walls Home links to, natively (plan L5, bug list item 52; the page's walls.js and loadRandom): Listen
 * later, Favourites, Recently played, Not played in 6 months, and Random albums with its reshuffle; a genre's
 * albums from Browse by genre (item 57: the random wall narrowed by [filterQuery], a WallFilter, with the
 * reshuffle too); and the app's own, Downloaded albums and Music on device (the latter with the Library wall's
 * Focus and Sort over the phone's own facets), each the heading at the top and the albums under it, the banner
 * where there is nothing or the library can't be read, the mini player and the row along the foot; a tile
 * opens the album's page over it with the wall as its list (one of the phone's own, the page's own page of it),
 * a long press starts selecting. What a wall showed is kept while the app runs, so Back finds it as it was.
 */
@Composable
internal fun WallScreen(kind: String, filterQuery: String, prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long, String, NavList) -> Unit,
                        onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filter = remember(filterQuery) { WallFilter.parse(filterQuery) }
    val offline = false
    val wall = remember(kind, filterQuery, offline) { Walls.state(if (kind == "genre") "$kind:$filterQuery" else kind, kind, offline) }
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    var selecting by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    // Music on device's view (its Focus and Sort), kept on this phone as the page keeps its own.
    var local by remember { mutableStateOf(LocalView.parse(null)) }
    var focusOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    // The app's own walls: their albums aren't the server's to play as a batch, so nothing is selected on them.
    val own = kind == "phone" || kind == "downloads"
    val (cols, coverPx) = wallColumns(prefs)
    val randomCount = randomWallCount(prefs)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    BackHandler { when { focusOpen -> focusOpen = false; sortOpen -> sortOpen = false; selecting -> { selecting = false; picks = emptyList() }; else -> onBack() } }
    fun load(refresh: Boolean = false) { scope.launch { wall.load(context, client(), randomCount, filter, refresh) } }
    // Read afresh on every visit (item 157: Favourites and Listen later were read once a run, an error kept till the app
    // was closed), what was there shown meanwhile; Random albums too, the server's one set Home's row is the head of
    // (item 537); a genre's wall keeps its own until reshuffled.
    LaunchedEffect(wall) { if (!wall.loaded || kind != "genre") load() }
    AskAgain(wall.failed) { load() }
    fun changeLocal(v: LocalView) { local = v }
    // The phone's albums as its view has them; every other wall as the server gave it.
    val shown = if (kind == "phone") local.sorted(wall.albums.filter(local::passes)) else wall.albums.toList()
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    fun pick(i: Int) { val a = shown[i]; picks = if (picks.any { it.offset == a.offset }) picks.filter { it.offset != a.offset } else picks + a }
    fun open(i: Int) {
        if (selecting) { pick(i); return }
        val a = shown[i]
        // One of the phone's own: the page's own page of it; a download the library no longer has: played from the phone, as the page does.
        HomeKept.phoneKeys[a.offset]?.let { onPage("phone-album", it); return }
        HomeKept.phoneDownload(a.offset)?.let { d ->
            if (d.state == "away") toast("On a card that isn’t in right now")
            else { DownloadTiles.play(context, d.id); toast("Not in the library right now — playing from this phone") }
            return
        }
        val items = shown.filter { it.offset >= 0 }
        onAlbum(a.offset, zone, NavList(items.map { NavItem(it.offset, it.title) }, items.indexOfFirst { it.offset == a.offset }))
    }
    fun act(what: String) {
        val chosen = picks
        when (what) {
            "clear" -> { selecting = false; picks = emptyList() }
            else -> {
                val c = client() ?: return
                if (what !in NoRoomActs && zone.isEmpty()) { toast("Pick a zone first"); return }
                scope.launch { toast(withContext(Dispatchers.IO) { runCatching { selectAct(c, chosen, zone, room.zoneName, what) }.getOrElse { com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server") } }); selecting = false; picks = emptyList() }
            }
        }
    }
    // The heading: the genre's name, Music on device with its count (as the page's renderLocalWall), or the wall's own.
    val title = when (kind) {
        "genre" -> filter?.title ?: "Random albums"
        "phone" -> if (!wall.loaded) "Music on device" else "Music on device · " + java.text.NumberFormat.getIntegerInstance().format(shown.size) + (if (local.focusCount > 0) " matching" else "") + (if (shown.size == 1) " album" else " albums")
        else -> Walls.title(kind)
    }
    val banner = if (kind == "phone" && wall.loaded) when {
        wall.albums.isEmpty() -> "Nothing on this phone yet — choose a folder in Settings → Music Folders." to false
        shown.isEmpty() -> "Nothing matches this focus — try clearing a filter." to false
        else -> null
    } else wall.banner

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AlbumGrid(listOf(GridBlock(null, shown)), prefs, cols, coverPx, client, wall.loaded, picks.map { it.offset }.toSet(), grid, list,
            onOpen = ::open, onLongPress = { i -> if (!own && !selecting) { selecting = true; pick(i) } }, dl = { a -> if (kind == "downloads") HomeKept.downloads[a.offset]?.badge else null }) {
            WallHead(title, prefs, banner)
        }
        WallBack(onBack)
        // Top right: the ⋯ while albums are picked; Focus and Sort on the phone's wall; the random wall's (and a genre's) reshuffle otherwise (the bar's button).
        if (picks.isNotEmpty()) AlbumSelectMenu(picks.size, ::act, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), picks)
        else if (kind == "phone") LibraryPills(local.focusCount, onFocus = { focusOpen = true }, onSort = { sortOpen = true })
        else if (kind == "random" || kind == "genre") FilledIconButton(onClick = { wall.loaded = false; load(refresh = true) }, enabled = wall.loaded, colors = barDisc(),
            modifier = Modifier.parity("wall.reshuffle", Contract.label("wall.reshuffle")).align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp).size(40.dp)) {
            Icon(painterResource(R.drawable.tn_reshuffle), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            if (selecting && picks.isEmpty()) SelectBar(onCancel = { selecting = false })
            else TouchNav("", onTap = onNav)
        }
        if (focusOpen) LocalFocusSheet(local, wall.albums.toList(), onChange = ::changeLocal, onClose = { focusOpen = false })
        // A sort chosen closes the sheet; the one on, tapped again, reverses in place (Random reshuffles and closes), as the page's.
        if (sortOpen) LocalSortSheet(local, onChoose = { id -> val same = id == local.sort; val v = local.choose(id); changeLocal(v); if (!same || !v.sortOption.hasDir) sortOpen = false }, onClose = { sortOpen = false })
    }
}

/** A wall as loaded, kept while the app runs (random albums until reshuffled). */
internal class WallKept(val kind: String, val offline: Boolean = false) {
    val albums = androidx.compose.runtime.mutableStateListOf<AlbumSummary>()
    var loaded by mutableStateOf(false)
    /** The last load failed, and is worth asking again (item 157: [AskAgain]). */
    var failed by mutableStateOf(false)
    var banner by mutableStateOf<Pair<String, Boolean>?>(null)

    /** The wall's albums: the phone's own from the phone, the rest from [client] ([randomCount] of them for the random wall, narrowed by [filter] for a genre's), and the banner as the page says it. */
    suspend fun load(context: Context, client: ServerClient?, randomCount: Int, filter: WallFilter?, refresh: Boolean = false) {
        val c = client ?: return
        val lib = LibraryClient { c.getJson(it) }
        val r = withContext(Dispatchers.IO) { runCatching {
            when (kind) {
                "lotw" -> {
                    val j = c.getJson("/api/home/label-of-the-week")
                    val a = j.optJSONArray("albums")
                    (if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { AlbumSummary.parse(o) }.getOrNull() } }) to "No label this week."
                }
                "favourites" -> lib.favourites().albums to "No favourites yet — tap the heart on an album."
                "history" -> lib.history(60).albums to "Nothing played in the last 30 days."
                "unplayed" -> lib.unplayed(6, 96).let { u ->
                    // Six months of listening first: before that, every album is "not played".
                    val from = u.readyAt?.let { " — from " + DateFormat.getDateInstance(DateFormat.LONG).format(Date(it)) } ?: ""
                    u.albums to (if (u.noHistory) "Albums show here once there are six months of listening behind them$from." else "Nothing here yet — play some music and check back.")
                }
                "genre" -> (if (filter != null) lib.randomAlbums(randomCount, filter) else lib.randomAlbums(randomCount)).albums to "No albums were returned. Is your library indexed?"
                else -> lib.randomAlbums(randomCount, refresh).albums to "No albums were returned. Is your library indexed?"
            }
        } }
        r.onSuccess { (list, empty) -> albums.clear(); albums.addAll(list); banner = if (list.isEmpty()) empty to (kind == "random" || kind == "genre") else null; failed = false
            // A new set (Refresh) on Home's row at once, as it will read it (item 537).
            if (refresh && kind == "random" && list.isNotEmpty()) HomeKept.rows["random"]?.let { row -> row.albums.clear(); row.albums.addAll(list.take(HomeKept.RANDOM_ROW)) } }
            .onFailure { e ->
                // What was there stays (item 157); with nothing, said.
                if (albums.isEmpty()) banner = (if (e is ServerClient.ServerException && e.status == 503) (e.message?.takeIf { !it.startsWith("HTTP") } ?: "Reading your music library…") else "Couldn’t load: " + com.musicd.mallard.client.Failure.said(e, "no answer")) to true
                failed = com.musicd.mallard.client.Failure.worthAgain(e)
            }
        loaded = true
    }
}

/** The walls held: one of each kind opened (a genre's by its filter). */
internal object Walls {
    private val kept = HashMap<String, WallKept>()
    /** Wall [id]: the one held, unless it was answered the other way ([offline]: by the phone; item 157). */
    @Synchronized fun state(id: String, kind: String, offline: Boolean = false): WallKept =
        kept[id]?.takeIf { it.offline == offline } ?: WallKept(kind, offline).also { kept[id] = it }

    /** Album [offset]'s cover read afresh ([LibraryWall.coverIs], item 123): put right on every wall held. */
    @Synchronized fun coverIs(offset: Long, imageKey: String?) {
        for (w in kept.values) for ((i, a) in com.musicd.mallard.client.AlbumCovers.fixes(w.albums, offset, imageKey)) w.albums[i] = a
    }

    /** The wall's heading, as the page's. */
    fun title(kind: String): String = when (kind) {
        "later" -> "Listen later"; "favourites" -> "Favourites"; "history" -> "Recently played"; "unplayed" -> "Not played in 6 months"
        "downloads" -> "Downloaded albums"; "phone" -> "Music on device"; else -> "Random albums"
    }
}
