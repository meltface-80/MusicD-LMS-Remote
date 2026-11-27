package com.musicd.mallard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The room under each of Home's rows, and under its strip of Random Album and today's album, before the next row's
 * heading (item 536: 50, the genres' 32; a row now keeps room for four lines of words under its covers, AlbumTile's
 * fourLines, so it is one height whichever tiles are on screen).
 */
private val ROW_GAP = 20.dp

/**
 * Home, natively (stage N5 of old/plan-android-native.md, bug list
 * item 57; a preview: Settings → Setup → UI Settings), drawn as the page's
 * (docs/specs/screens.json, home; style.css in the hifi palette): the
 * greeting with the date over it, the strip under it (Random Album with its
 * turning disc, today's album with ★ Today), then the rows in the owner's
 * order and visibility (/api/settings/home-rows), each a small brass heading
 * that opens its wall over its tiles scrolling sideways, Smart Picks' cards,
 * the label of the week, the genre cards two to a row, and the app's own
 * two rows (Downloaded albums, Music on device) from the phone; the mini
 * player and the row along the foot. The side menu opens from the top left, the side it opens from (item 210: it was the row's first button). Its rows are kept (HomeKept) and asked
 * for again on each visit, and Back from a wall or an album finds it as it was.
 */
@Composable
internal fun HomeScreen(prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onAlbum: (Long, String, NavList) -> Unit, onWall: (String, String) -> Unit, onLibrary: () -> Unit, onPicks: () -> Unit,
                        onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember(zoneId) { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    val coverPx = homeCoverPx(prefs)
    val spin = remember { DiscSpin() }
    var picked by remember { mutableStateOf<AlbumSummary?>(null) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    LaunchedEffect(spin) { spin.run() }
    // Each time Home comes on screen, its rows as the page loads them on a visit; today's album asked for every minute while it stays.
    // Read again when the app goes offline or comes back (item 109): the phone's answers, or the server's.
    val offline: String? = null
    LaunchedEffect(offline) { HomeKept.refresh(scope, context, client(), NativeShell.libraryView) }
    // Whether the row along the foot offers Shelf (item 210), as the server says it now.
    // A row that failed asked again: at once when the server answers again, else in a while (item 157).
    AskAgain(HomeKept.failed) { HomeKept.refresh(scope, context, client(), NativeShell.libraryView) }
    // Today's album while the app is on screen (item 120: every minute all night too).
    val onScreen by NativeShell.onScreen
    LaunchedEffect(offline, onScreen, prefs.aotd) {
        if (!onScreen || !prefs.aotd) return@LaunchedEffect   // switched off (item 236): not asked for
        while (true) { delay(60_000); client()?.let { HomeKept.refreshAotd(it) } }
    }
    // The server's albums moved while Home is on screen (item 232, LibraryLive): the Library row read again at once.
    val movesSeen = remember { LibraryLive.moves }
    LaunchedEffect(LibraryLive.moves) { if (LibraryLive.moves != movesSeen && offline == null) HomeKept.libraryRow(scope, client(), NativeShell.libraryView) }
    LaunchedEffect(HomeKept.toTop) { if (HomeKept.toTop) { HomeKept.list.scrollToItem(0); HomeKept.toTop = false } }
    // Random Album (the page's playUnheard): the disc turns fast for two seconds, then the album chosen is offered.
    fun randomAlbum() {
        if (spin.finding) return
        val c = client() ?: return
        if (zone.isEmpty()) { toast("Select a zone first"); return }
        spin.start()
        scope.launch {
            delay(2000)
            val r = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.pickUnheard().album } }
            spin.stop()
            r.onSuccess { picked = it }.onFailure { toast(com.musicd.mallard.client.Failure.said(it, "Could not choose an album")) }
        }
    }
    // A tile tapped: the album's page over Home with its row as the list; one of the phone's own, the page's own page of it; a download the library no longer has, played from the phone.
    fun open(albums: List<AlbumSummary>, i: Int) {
        val a = albums[i]
        HomeKept.phoneKeys[a.offset]?.let { onPage("phone-album", it); return }
        HomeKept.phoneDownload(a.offset)?.let { d ->
            if (d.state == "away") toast("On a card that isn’t in right now")
            else { DownloadTiles.play(context, d.id); toast("Not in the library right now — playing from this phone") }
            return
        }
        val items = albums.filter { it.offset >= 0 }
        onAlbum(a.offset, zone, NavList(items.map { NavItem(it.offset, it.title) }, items.indexOfFirst { it.offset == a.offset }))
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = HomeKept.list, contentPadding = PaddingValues(top = 14.dp, bottom = 164.dp)) {
            // The notices (the server's, the update offer; items 112 and 111), at Home's head as on the page's.
            item("notices") { NoticeBanners(prefs.menu, client) { what, arg -> handOn(what, arg) } }
            // The greeting at the top; the side menu is the row's first along the foot (item 296: it was here from item 210).
            if (prefs.greeting) item("greeting") { Greeting() }
            // The strip under the greeting: Random Album, then today's album; gone with both.
            val aotd = HomeKept.aotd.takeIf { prefs.aotd }
            if (prefs.random || aotd != null) item("today") {
                Column {
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (prefs.random) item("random") { RandomAlbumTile(prefs, spin, ::randomAlbum) }
                        if (aotd != null) item("aotd") { Box(Modifier.width((144 * prefs.tile).dp)) { AlbumTile(aotd, prefs, coverPx, client, badge = "★ Today", fourLines = true) { open(listOf(aotd), 0) } } }
                    }
                    Spacer(Modifier.height(ROW_GAP))
                }
            }
            for (row in HomeKept.layout) {
                if (!HomeKept.on(row)) continue
                when (row.id) {
                    "picks" -> if (HomeKept.picks.isNotEmpty()) item(row.id) {
                        Column {
                            SectionTitle(Modifier.parity("home.picks-title", Contract.label("home.picks-title")).clickable(onClick = onPicks), "Smart Picks", true)
                            // A pick the library has opens as any album; one it doesn't opens Smart Picks, where Add and the reason live.
                            Carousel(HomeKept.picks, key = { it.artist + "|" + it.album }) { _, p ->
                                PickCardHome(p, prefs, client) { p.inLibrary?.let { open(listOf(it), 0) } ?: onPicks() }
                            }
                            Spacer(Modifier.height(ROW_GAP))
                        }
                    }
                    "genres" -> item(row.id) {
                        Column {
                            SectionTitle(Modifier.parity("home.genres-title"), "Browse by genre", false)
                            if (HomeKept.genres.isEmpty()) HomeKept.genresNote?.let { RowNote(it) }
                            else GenreGrid(HomeKept.genres) { card -> val f = card.filter(); onWall("genre", com.musicd.mallard.client.WallFilter.routeQuery(f)) }
                            Spacer(Modifier.height(ROW_GAP))
                        }
                    }
                    else -> {
                        val kept = HomeKept.rows[row.id] ?: continue
                        if (HomeKept.hidesWhenEmpty(row.id) && kept.albums.isEmpty()) continue
                        item(row.id) {
                            Column {
                                when (row.id) {
                                    "unplayed" -> SectionTitle(Modifier.parity("home.unplayed-title", Contract.label("home.unplayed-title")).clickable { onWall("unplayed", "") }, "Not played in 6 months", true)
                                    "later" -> SectionTitle(Modifier.parity("home.later-title", Contract.label("home.later-title")).clickable { onWall("later", "") }, "Listen later", true)
                                    "favourites" -> SectionTitle(Modifier.parity("home.favourites-title", Contract.label("home.favourites-title")).clickable { onWall("favourites", "") }, "Favourites", true)
                                    "history" -> SectionTitle(Modifier.parity("home.history-title", Contract.label("home.history-title")).clickable { onWall("history", "") }, "Recently played", true)
                                    "random" -> SectionTitle(Modifier.parity("home.random-title", Contract.label("home.random-title")).clickable { onWall("random", "") }, "Random albums", true)
                                    "library" -> SectionTitle(Modifier.parity("home.library-title", Contract.label("home.library-title")).clickable(onClick = onLibrary), "Library", true)
                                    "downloads" -> SectionTitle(Modifier.parity("home.downloads-title", Contract.label("home.downloads-title")).clickable { onWall("downloads", "") }, "Downloaded albums", true)
                                    "phone" -> SectionTitle(Modifier.parity("home.phone-title", Contract.label("home.phone-title")).clickable { onWall("phone", "") }, "Music on device", true)
                                }
                                AlbumRowItems(kept, prefs, coverPx, client, badges = row.id == "downloads", onOpen = { list, i -> open(list, i) })
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            // Home, tapped where it is already: its top, as the page's row does.
            TouchNav("home", onTap = { what -> if (what == "home") scope.launch { HomeKept.list.animateScrollToItem(0) } else onNav(what) })
        }
        picked?.let { RandomPickDialog(it, zone, client, onClose = { picked = null }) }
    }
}

/** The greeting (the page's paintGreeting; style.css .home-greeting in the hifi palette): the date in small brass capitals, the time of day in the serif under it. */
@Composable
private fun Greeting() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Date() } }
    val hour = Calendar.getInstance().also { it.time = now }.get(Calendar.HOUR_OF_DAY)
    val words = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    // The weekday, the day and the month in the phone's own order ("Monday, October 19"; "Monday 19 October"), as the page's toLocaleDateString.
    val locale = Locale.getDefault()
    val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(now)
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 26.dp)) {
        Text(date.uppercase(locale), Modifier.parity("home.greeting-date"), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.W700, letterSpacing = 0.18.em)
        Text(words, Modifier.parity("home.greeting").padding(top = 6.dp).lineBox(34.sp, 37.4.sp, Face.YOUNG_SERIF), color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontWeight = FontWeight.W400,
            fontSize = 34.sp, lineHeight = 37.4.sp, letterSpacing = 0.01.em)
    }
}

/**
 * A row's heading (the hifi .home-section-title): small brass capitals, with "›" at its end where it opens a wall
 * ([link]), 14 under it.
 */
@Composable
private fun SectionTitle(modifier: Modifier, text: String, link: Boolean) {
    Row(modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).height(if (link) 20.dp else 17.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.W700, letterSpacing = 0.18.em)
        if (link) { Spacer(Modifier.weight(1f)); Text("›", Modifier.lineBox(20.sp, 20.sp), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, lineHeight = 20.sp) }
    }
}

/**
 * A row's tiles (an album each, with a download's badge on the app's own row alone, [badges]), or its one line where it has
 * none, [ROW_GAP] under it, each tile keeping room for four lines of words (item 536). A tile tapped opens with the row
 * as it was drawn ([onOpen]).
 */
@Composable
private fun AlbumRowItems(row: RowKept, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, badges: Boolean = false, onOpen: (List<AlbumSummary>, Int) -> Unit) {
    val albums = row.albums.toList()
    if (albums.isEmpty()) row.note?.let { RowNote(it) }
    else Carousel(albums, key = { it.offset }) { i, a ->
        Box(Modifier.width((144 * prefs.tile).dp)) { AlbumTile(a, prefs, coverPx, client, dl = if (badges) HomeKept.downloads[a.offset]?.badge else null, fourLines = true) { onOpen(albums, i) } }
    }
    Spacer(Modifier.height(ROW_GAP))
}
