package com.musicd.mallard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.Retry
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.NavItem
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An artist's page, natively (plan L4, bug list item 52; rebuilt in item 131, now in tabs; the page's
 * artist-view.js, artist-about.js and artist-tabs.js): the count and the name as the heading once the albums come; who
 * they are (ArtistHero: their picture, what they are, their genres, Play and Shuffle; no links); then the tabs
 * (ArtistTabs), every artist's page the same. Overview: their albums in a row, See all opening Discography, then similar
 * artists and albums (ArtistFoot, item 140). Discography: each under its own small heading, their albums, those they
 * appear on, their bands' and those they are credited on, the default service's after their own (item 522, serviceShelf);
 * a tile opens the album's page over it with the page's albums as its list, a long press starts selecting. About: the
 * biography, asked for only when About is opened or for a portrait the server hasn't (the page drawn without waiting on
 * other services), and a band's members or a person's bands. The mini player and the row along the foot. A page opened
 * again is drawn at once from what it last had (ArtistPages), and asked for afresh once that is old.
 */
@Composable
internal fun ArtistScreen(asked: String, prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long, String, NavList) -> Unit,
                          onArtist: (String) -> Unit, onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    val name = ArtistAct.parse(asked).first   // one act of a name others share, its own page (item 330)
    val key = ArtistPages.key(asked)
    val kept = remember(asked) { ArtistPages.kept.get(key) }
    var albums by remember(asked) { mutableStateOf(kept?.albums) }
    var bio by remember(asked) { mutableStateOf(kept?.bio) }
    var about by remember(asked) { mutableStateOf(kept?.about) }
    var similar by remember(asked) { mutableStateOf(kept?.similar) }
    var similarAlbums by remember(asked) { mutableStateOf(kept?.similarAlbums.orEmpty()) }
    var tab by rememberSaveable(asked) { mutableStateOf("overview") }   // every page opens on Overview
    var aboutDone by remember(asked) { mutableStateOf(kept?.about != null) }
    var bioDone by remember(asked) { mutableStateOf(kept?.bio != null) }
    fun keep(change: (ArtistPage) -> ArtistPage) = ArtistPages.kept.update(key, ArtistPage(), change)
    var failed by remember(asked) { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    val (cols, coverPx) = wallColumns(prefs)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    BackHandler { if (selecting) { selecting = false; picks = emptyList() } else onBack() }
    // Still failed after the few tries below, asked again when the server answers again or in a while (item 157).
    var asks by remember { mutableStateOf(0) }
    AskAgain(failed != null && albums == null) { asks++ }
    LaunchedEffect(asked, asks) {
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        // Who they are, asked at every visit (item 341): the server may have found since that they are another act than
        // it said; asked again in a few seconds while it is still looking.
        suspend fun whoTheyAre() {
            try {
                repeat(5) { n ->
                    if (n > 0) delay(3000)
                    val got = withContext(Dispatchers.IO) { runCatching { lib.artistAbout(asked) }.getOrNull() } ?: return
                    about = got; keep { it.copy(about = got) }   // still looking and not known: not the other act's meanwhile
                    if (!got.pending) return
                }
            } finally { aboutDone = true }
        }
        if (kept?.albums != null && ArtistPages.kept.fresh(key)) { whoTheyAre(); return@LaunchedEffect }   // the rest as it was, and recent
        // Asked again a few times when the server isn't reached (a dropped connection, the server restarting; item 133).
        var got = withContext(Dispatchers.IO) { runCatching { lib.artistAlbums(asked) } }
        var tries = 0
        while (got.isFailure && tries < 3 && got.exceptionOrNull()?.let(Failure::unreachable) == true) {
            delay(Retry.ms(++tries))
            got = withContext(Dispatchers.IO) { runCatching { lib.artistAlbums(asked) } }
        }
        // Asked afresh: kept from now, the rest of what was kept drawn until its answer replaces it.
        got.onSuccess { albums = it; failed = null; ArtistPages.kept.put(key, (ArtistPages.kept.get(key) ?: ArtistPage()).copy(albums = it)) }
            .onFailure { if (albums == null) failed = Failure.said(it, "no answer") }
        // Who they are (item 131) after the albums, so the grid never waits on another service: the server may look them
        // up there and then, for a few seconds. The biography is About's (below).
        coroutineScope {
            launch { whoTheyAre() }
            launch { withContext(Dispatchers.IO) { runCatching { lib.artistSimilar(name) }.getOrNull() }?.let { got -> similar = got; keep { it.copy(similar = got) } } }   // item 137
            launch {   // item 140
                withContext(Dispatchers.IO) { runCatching { lib.artistSimilarAlbums(name) }.getOrNull() }?.takeIf { it.error == null }?.albums?.let { got -> similarAlbums = got; keep { it.copy(similarAlbums = got) } }
            }
        }
    }
    // The biography: asked for only when About is opened, or for its portrait when the server has no picture of
    // their own; one of their own albums pins the lookup.
    val wantBio = tab == "about" || (aboutDone && about?.photoKey == null)
    LaunchedEffect(asked, wantBio, albums != null) {
        if (!wantBio || bio != null || albums == null) return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        val a = albums
        val pin = (a?.primary?.firstOrNull() ?: a?.featured?.firstOrNull())?.title ?: ""
        withContext(Dispatchers.IO) { runCatching { lib.artistBio(name, pin) }.getOrNull() }?.let { got -> bio = got; keep { it.copy(bio = got) } }
        bioDone = true
    }
    // The library changed while the page is open (item 357, LibraryLive): its albums and who they are asked again in
    // place, an album deleted from the folders gone from the page, one added there.
    val movesSeen = remember(asked) { LibraryLive.moves }
    LaunchedEffect(asked, LibraryLive.moves) {
        if (LibraryLive.moves == movesSeen) return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        withContext(Dispatchers.IO) { runCatching { lib.artistAlbums(asked) }.getOrNull() }
            ?.let { got -> albums = got; ArtistPages.kept.put(key, (ArtistPages.kept.get(key) ?: ArtistPage()).copy(albums = got)) }
        withContext(Dispatchers.IO) { runCatching { lib.artistAbout(asked) }.getOrNull() }?.let { got -> about = got; keep { it.copy(about = got) } }
    }
    // Their bands' albums from their years in them (item 133), then those they are credited on beyond all of these,
    // each saying as what (item 131).
    val bands = about?.bandAlbums.orEmpty()
    val shown = ((albums?.primary ?: emptyList()) + (albums?.featured ?: emptyList()) + bands.flatMap { it.albums }).map { it.offset }.toSet()
    val credited = about?.creditedBeyond(shown)?.map { c -> c.album.copy(subtitle = listOf(c.album.subtitle, c.roles.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")) } ?: emptyList()
    val all = (albums?.primary ?: emptyList()) + (albums?.featured ?: emptyList()) + bands.flatMap { it.albums } + credited
    // What Play and Shuffle play (item 138): their own albums and their bands', else those they appear on.
    val playable = ((albums?.primary ?: emptyList()) + bands.flatMap { it.albums }).ifEmpty { albums?.featured ?: emptyList() }
    fun playArtist(kind: String) {
        val c = client() ?: return
        if (zone.isEmpty()) { toast("Pick a zone first"); return }
        val made = ServerWrites.write { selectAct(c, playable, zone, room.zoneName, kind) }
        scope.launch { toast(ServerWrites.await(made).getOrElse { com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server") }) }
    }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    fun pick(i: Int) { val a = all[i]; picks = if (picks.any { it.offset == a.offset }) picks.filter { it.offset != a.offset } else picks + a }
    fun open(i: Int) { if (selecting) { pick(i); return }; val a = all[i]; onAlbum(a.offset, zone, NavList(all.map { NavItem(it.offset, it.title) }, i)) }
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
    val a = albums
    // Discography's: the grid shows on that tab alone.
    val blocks = if (a == null || tab != "discography") emptyList() else listOfNotNull(
        if (a.primary.isNotEmpty()) GridBlock("Albums", a.primary) else null,
        if (a.featured.isNotEmpty()) GridBlock("Appears on", a.featured) else null
    ) + bands.map { GridBlock("With ${it.band}", it.albums) } + listOfNotNull(if (credited.isNotEmpty()) GridBlock("Credited on", credited) else null)
    // The default service's albums of theirs the library hasn't (item 522), right after its own: ahead of what follows "Albums", else first.
    val shelf = serviceShelf(asked, albums != null, blocks.indexOfFirst { it.title == "Albums" } + 1, prefs, client) { id -> onAlbum(id, zone, NavList(listOf(NavItem(id, "")), 0)) }

    // A similar album (the library's alone) opens with the others as its list.
    fun openSimilar(i: Int) {
        val al = similarAlbums.getOrNull(i)?.album ?: return
        val own = similarAlbums.mapNotNull { it.album }
        onAlbum(al.offset, zone, NavList(own.map { NavItem(it.offset, it.title) }, own.indexOfFirst { it.offset == al.offset }))
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AlbumGrid(blocks, prefs, cols, coverPx, client, loaded = albums != null || failed != null, picks.map { it.offset }.toSet(), grid, list,
            onOpen = ::open, onLongPress = { i -> if (!selecting) { selecting = true; pick(i) } },
            foot = { if (tab == "overview") ArtistFoot(similar, similarAlbums, prefs, coverPx, client, onArtist, onAlbum = ::openSimilar) }, extra = if (tab == "discography") shelf else null) {
            // The name at once; the count when the albums come. An error as the page has it, in red under the heading.
            val n = (a?.primary?.size ?: 0) + (a?.featured?.size ?: 0) + bands.sumOf { it.albums.size }
            WallHead(if (a == null) name else "$n album" + (if (n == 1) "" else "s") + " · $name" + (a.note?.let { " ($it)" } ?: ""), prefs, failed?.let { "Error: $it" to true })
            ArtistHero(about, bio, client, onPlay = if (playable.isNotEmpty()) ::playArtist else null, onGenre = { onPage("genre", it) },
                kept = kept?.portrait, onPortrait = { p -> keep { it.copy(portrait = p) } })
            ArtistTabRow(tab) { tab = it }
            when (tab) {
                "overview" -> OverviewAlbums(playable, prefs, coverPx, client, onOpen = { i -> onAlbum(playable[i].offset, zone, NavList(playable.map { NavItem(it.offset, it.title) }, i)) },
                    onSeeAll = { tab = "discography" })
                "about" -> ArtistAboutPane(about, bio, bioDone, client, onArtist)
            }
            if (tab != "about" && a != null && n == 0 && credited.isEmpty() && failed == null) Text("No albums found for \"$name\"", Modifier.parity("artist.empty").fillMaxWidth().padding(vertical = 48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 20.5.sp, textAlign = TextAlign.Center)
        }
        WallBack(onBack)
        if (picks.isNotEmpty()) AlbumSelectMenu(picks.size, ::act, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), picks)
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            if (selecting && picks.isEmpty()) SelectBar(onCancel = { selecting = false })
            else TouchNav("", onTap = onNav)
        }
    }
}
