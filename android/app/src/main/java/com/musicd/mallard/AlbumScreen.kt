package com.musicd.mallard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.AlbumCredits
import com.musicd.mallard.client.AlbumVersion
import com.musicd.mallard.client.AlbumExtras
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.Booklet
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PhoneShelf
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An album's page, natively (stage N3 of old/plan-android-native.md, a
 * preview: Settings → Setup → UI Settings), drawn as the page's sleeve
 * (docs/specs/screens.json, album; style.css, the album view in the hifi
 * palette): the brass corner discs (previous and next beside Share when it
 * was opened from a list, [list]: a tap opens that album in this one's
 * place, [onStepTo]), the cover's glow behind the top, the 160px cover (the
 * service's mark on it, and its book when it has a booklet: [onBooklet]
 * opens one) with the title, the artists, the date and its label
 * and the rate and length beside it and the notes under them; the heart,
 * Play Now, Queue and ⋯; the tracks,
 * the one playing marked; About this album and the rows under it (another
 * album of the library's opens here, [onAlbum]); the mini player and the
 * touch layout's row at the foot ([lit], the row's button lit under it; a
 * tap is the page's, [onNav]).
 * Its buttons play in
 * [zoneId], the room the page had chosen, or the one chosen since on the mini
 * player's Play on ([onRoom] tells the page); a tap on the mini player opens
 * Now playing ([onNowPlaying], with the room). What the page does with its
 * own sheets (Share card) and screens (an artist's, a
 * label's) it is asked for ([onPage]); Edit album is its own (item 74).
 * One of the phone's own albums ([phone], its key; item 108) is read from the phone and played on it as the page's
 * invokePhone plays one, picked tracks too; the rest of its buttons are the page's, as the page has them for one (Download
 * not offered).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumScreen(offset: Long, zoneId: String, lit: String, client: () -> ServerClient?, list: NavList?, onStepTo: (Long, String, NavList) -> Unit,
                         onBack: () -> Unit, onPage: (String, String?) -> Unit, onBooklet: (Long, Booklet) -> Unit,
                         onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onAlbum: (Long, String) -> Unit, phone: String? = null,
                         chrome: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember(offset) { mutableStateOf(zoneId) }
    // The version shown (item 349): the one opened, or one chosen on its Versions tab, shown in its place; the tab open.
    var shown by remember(offset) { mutableLongStateOf(offset) }
    var tab by remember(offset) { mutableStateOf("tracks") }
    var refresh by remember(offset) { mutableIntStateOf(0) }
    var album by remember(offset) { mutableStateOf<Album?>(null) }
    var extras by remember(offset) { mutableStateOf<AlbumExtras?>(null) }
    var more by remember(offset) { mutableStateOf<AlbumMore?>(null) }
    var credits by remember(offset) { mutableStateOf<AlbumCredits?>(null) }   // who played, wrote and made it (item 132)
    var booklets by remember(offset) { mutableStateOf<List<Booklet>>(emptyList()) }
    var picks by remember(offset) { mutableStateOf<List<Int>?>(null) }   // selecting tracks: their places, in order of picking
    // Whether the album is on this phone, following the downloads as they go (the page's ⋯ reads the same store through its bridge).
    // Read off the main thread (item 158: the phone's storage and the album's file, read as the page was drawn).
    var download by remember(offset) { mutableStateOf(JSONObject().put("state", "none")) }
    LaunchedEffect(offset) { download = withContext(Dispatchers.IO) { DownloadStore.status(context, offset.toInt()) } }
    DisposableEffect(offset) {
        val l = { download = DownloadStore.status(context, offset.toInt()) }
        DownloadStore.listen(l)
        onDispose { DownloadStore.unlisten(l) }
    }
    var failed by remember(offset) { mutableStateOf<String?>(null) }
    var favourite by remember(offset) { mutableStateOf(false) }
    var later by remember(offset) { mutableStateOf(false) }
    var serviceFav by remember(offset) { mutableStateOf<Boolean?>(null) }   // a streamed album's favourite on its service, once known
    var editing by remember(offset) { mutableStateOf(false) }   // its editor open (item 74)
    var sharing by remember(offset) { mutableStateOf(false) }   // its Share card open (item 75)
    var loads by remember(offset) { mutableIntStateOf(0) }   // read again once the editor changed it
    // Not reached, or the server's own trouble: asked again at once when it answers again, else in a while (items 127, 157).
    var again by remember(offset) { mutableStateOf(false) }
    val room = followRoom(zone, client)
    BackHandler(onBack = onBack)

    AskAgain(again) { loads++ }
    LaunchedEffect(offset, loads) {
        if (phone != null) {
            album = withContext(Dispatchers.IO) { runCatching { LocalMusic.load(context); LocalMusic.album(phone)?.let { PhoneShelf.phoneView(PhoneData.phone(it)) } }.getOrNull() }
            val a = album ?: run { failed = "That album isn't on this phone any more"; return@LaunchedEffect }
            val lib = client()?.let { c -> LibraryClient { c.getJson(it) } } ?: return@LaunchedEffect
            extras = withContext(Dispatchers.IO) { runCatching { lib.albumExtras(a.album.title, a.album.subtitle) }.getOrNull() }
            more = withContext(Dispatchers.IO) { runCatching { loadMore(lib, a) }.getOrNull() }
            return@LaunchedEffect
        }
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        val got = withContext(Dispatchers.IO) { runCatching { lib.album(shown) } }
            .onSuccess { album = it; favourite = it.album.favourite; later = it.album.later; serviceFav = it.album.serviceFavourite; LibraryWall.coverIs(offset, it.album.imageKey) }
            .onFailure { failed = Failure.said(it, "Couldn't load the album") + if (Failure.worthAgain(it)) ". Trying again…" else "" }
        again = album == null && got.exceptionOrNull()?.let(Failure::worthAgain) == true
        val a = album ?: return@LaunchedEffect
        // Its booklets, as the page asks: an album of your own files only.
        if (a.album.source == null || a.album.source == "local") booklets = withContext(Dispatchers.IO) { runCatching { lib.booklets(offset) }.getOrNull().orEmpty() }
        extras = withContext(Dispatchers.IO) { runCatching { lib.albumExtras(a.album.title, a.album.subtitle) }.getOrNull() }
        // Its credits (item 132): the server may find them there and then, for a few seconds; the rows under them meanwhile.
        coroutineScope {
            launch { credits = withContext(Dispatchers.IO) { runCatching { lib.albumCredits(offset) }.getOrNull() } }
            launch { more = withContext(Dispatchers.IO) { runCatching { loadMore(lib, a) }.getOrNull() } }
        }
    }
    // A write-up's page: the phone's browser.
    fun openUrl(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val cover = if (phone != null) rememberTileCover(album?.album?.imageKey, 640, client) else rememberCover(album?.album?.imageKey, 640, client)
    val glow by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, cover) { value = cover?.let { albumGlowOf(it) } }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // Another version chosen (item 349), or its versions changed: it alone read, the rest of the page as it was.
    VersionSwitch(album, shown, refresh, phone != null, client, onShown = { album = it; favourite = it.album.favourite; later = it.album.later; serviceFav = it.album.serviceFavourite; picks = null }) { said, back -> toast(said); shown = back }
    // Make this the main version (item 349: in ⋯, and on its row): said as it goes, its versions read again.
    fun makeMain(at: Long) {
        val c = client() ?: return
        toast(AlbumVersion.SAVING)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { c.mainVersion(at) }.isSuccess }
            toast(if (ok) AlbumVersion.IS_MAIN else AlbumVersion.FAILED)
            if (ok) refresh++
        }
    }
    // The phone's own album, track [index] (-1, the whole album) played on this phone, said as the page's invokePhone says it.
    var starting by remember(offset) { mutableStateOf(false) }   // a play sent and not yet answered (item 490)
    fun playPhone(index: Int, kind: String) {
        val key = phone ?: return
        DownloadsBridge(context as? Activity ?: return).playLocal(key, index, kind)
        toast((if (kind == "queue") "Queued" else if (kind == "play_next" || kind == "add_next") "Next" else "Playing") + " → This phone")
    }
    // To the room: what came of it said as the page says it; nothing when [said] is empty (Play Now: the mini player shows what
    // plays, and a toast over it said the same, bug list item 58), unless it failed. One at a time, the buttons dimmed till it
    // is answered (item 490: an album new to the library waits on its rates, and each tap again started it afresh).
    fun play(said: String, block: (ServerClient) -> JSONObject?) {
        val c = client() ?: return
        if (zone.isEmpty()) { toast("Pick a zone first"); return }
        if (starting) return else starting = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
                val err = r.exceptionOrNull()?.message
                if (err != null) toast(err) else if (said.isNotEmpty()) toast(said)
            } finally { starting = false }
        }
    }
    // Made on the app's lane of changes to what Home shows (item 85): to the end if this page closes, and before Home reads.
    fun mark(block: (ServerClient) -> Unit) {
        val c = client() ?: return
        val made = ServerWrites.write { block(c) }
        scope.launch { ServerWrites.await(made).exceptionOrNull()?.let { toast(com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server")) } }
    }

    val bg = MaterialTheme.colorScheme.background
    val white = paletteColor(Palette.MODAL_TEXT)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val brass = MaterialTheme.colorScheme.primary
    val disc = IconButtonDefaults.filledIconButtonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = brass, disabledContentColor = MaterialTheme.colorScheme.onPrimary)
    Surface(Modifier.fillMaxSize(), color = bg) {
        Box(Modifier.fillMaxSize()) {
            // The cover's colours washing the top of the page, as the page's glow does: blurred once, small (item 471), faint, fading out downwards.
            cover?.let {
                Box(Modifier.parity("album.glow").fillMaxWidth().height(420.dp)) {
                    glow?.let { g -> Image(g, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low, modifier = Modifier.fillMaxSize().alpha(0.26f)) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to bg.copy(alpha = 0.4f), 1f to bg)))
                }
            }
            // The head and its buttons, and the tracks: the one column's, or the left and right columns' on a tablet held sideways.
            // A streamed album's favourite on its service, from + or ⋯: said, kept; removed, the album leaves the library and its page closes.
            fun setService(a: Album, on: Boolean) {
                val c = client() ?: return
                scope.launch {
                    val (said, ok) = withContext(Dispatchers.IO) { setServiceFavourite(c, a, on) } ?: return@launch
                    toast(said)
                    if (ok) serviceFav = on
                    // Added: the note under the artists says so from now (item 540), as the + and ⋯ do.
                    if (ok && on) album = album?.let { al -> al.copy(serviceWhy = al.serviceWhy?.added(System.currentTimeMillis())) }
                    if (ok && !on) onPage("library-changed", offset.toString())
                }
            }
            fun LazyListScope.sleeveItems(a: Album) {
                // Centred under the sleeve, as a phone's (item 539: even on a tablet too), About's genres and score under it.
                item { BoxWithConstraints { PhoneHead(a, cover, minOf(maxWidth, 300.dp), extras, onPage, badges = {
                    SourceBadge(a.album.source, Modifier.align(Alignment.BottomStart))
                    if (booklets.isNotEmpty()) BookletButton(booklets, disc, Modifier.align(Alignment.BottomEnd)) { onBooklet(offset, it) }
                }) { discs -> onStepTo(discs.items[discs.index].offset, zone, discs) } } }
                aboutHead(a, extras, onPage)
            }
            fun LazyListScope.actionItem(a: Album) {
                item {
                    // The heart (or a streamed album's +), Play Now and Queue in brass, and ⋯ (item 539: even, ring to ring), 42 tall (item 54).
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AlbumHeartButton(a.album.source, serviceFav, favourite, onLike = { favourite = !favourite; val on = favourite; mark { it.favourite(shown, on) } }, onAdd = { setService(a, true) })
                        val pill = ButtonDefaults.buttonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary)
                        Button(onClick = { if (phone != null) playPhone(-1, "play_now") else play("") { it.playAlbum(shown, a.album.title, a.album.subtitle, zone, "play_now") } }, enabled = !starting, colors = pill, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.parity("album.play-now", Contract.label("album.play-now")).weight(1f).height(42.dp)) { Text(Contract.label("album.play-now"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        Button(onClick = { if (phone != null) playPhone(-1, "queue") else play("Queued") { it.playAlbum(shown, a.album.title, a.album.subtitle, zone, "queue") } }, enabled = !starting, colors = pill, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.parity("album.queue", Contract.label("album.queue")).weight(1f).height(42.dp)) { Text(Contract.label("album.queue"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        val dlState = download.optString("state", "none")
                        val dlLabel = downloadWords(download, phone == null && (a.album.source == null || a.album.source == "local"))
                        val bridge = (context as? Activity)?.let { DownloadsBridge(it) }
                        MoreMenu(a, later, onLater = { later = !later; val on = later; mark { it.listenLater(shown, on) } },
                            onMain = a.versions.firstOrNull { it.current }?.takeIf { !it.main && phone == null }?.let { v -> { makeMain(v.album.offset) } },
                            onPlay = { kind, said -> if (phone != null) playPhone(-1, kind) else play(said) { it.playAlbum(shown, a.album.title, a.album.subtitle, zone, kind) } }, onPage = { what, arg -> if (what == "album-edit") editing = true else if (what == "share") sharing = true else onPage(what, arg) },
                            download = dlLabel?.takeIf { bridge != null },
                            onDownload = {
                                val b = bridge ?: return@MoreMenu
                                when (dlState) { "done" -> b.remove(shown.toInt()); "downloading", "queued", "waiting" -> b.open(); else -> b.download(shown.toInt(), a.album.title, a.album.subtitle) }
                            },
                            serviceFavourite = serviceFav, onServiceFavourite = { on -> setService(a, on) })
                    }
                }
            }
            fun LazyListScope.headItems(a: Album) {
                sleeveItems(a)
                actionItem(a)
                // A tablet's: its versions under the head, as a phone's Versions tab lists them (item 349).
                if (a.versions.size > 1) albumVersions(a, client, onOpen = { v -> shown = v.album.offset },
                    onPlay = { v -> play("") { it.playAlbum(v.album.offset, v.album.title, v.album.subtitle, zone, "play_now") } }, onMain = { v -> makeMain(v.album.offset) })
            }
            fun LazyListScope.trackItems(a: Album, head: Boolean = true) {
                val playingId = room.view?.takeIf { it.album == a.album.title }?.trackId
                val onPick = { i: Int -> val p = picks; picks = if (p == null) listOf(i) else if (i in p) (p - i).ifEmpty { null } else p + i }
                val onPlay = { i: Int, kind: String, said: String -> if (phone != null) playPhone(i, kind) else play("$said: ${a.tracks[i].title}") { it.playTrack(shown, i, a.tracks[i].title, a.album.title, a.album.subtitle, zone, kind) } }
                // A classical album's works and movements (item 49), else the plain list; a work plays as a selection of its tracks would (Play next last to first).
                if (a.classical) albumWorks(a, playingId, room.view?.playing == true, picks, onPick, onPlay, onPlayWork = { w, kind, said ->
                    play("$said: ${w.title}") { c -> playWork(c, a, shown, w, kind, zone) }
                })
                else albumTracks(a, playingId, room.view?.playing == true, picks, onPick, onPlay, head)
            }
            val a = album
            // The foot keeps room for the mini player and the row, as the page's does (in the Shelf its bar, under it); the head starts below the corner discs.
            val foot = if (chrome) 176.dp else 32.dp
            val pad = PaddingValues(top = 64.dp, bottom = foot)
            val credited = { lazy: LazyListScope -> lazy.albumCredits(credits, client, onArtist = { onPage("artist", it) }) }
            if (a == null) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = pad) {
                item { Text(failed ?: "Loading…", Modifier.parity("album.notice").padding(vertical = 24.dp), color = dim) }
            }
            // A tablet held sideways (item 218): three columns, each scrolling on its own, as the page's album view.
            else if (tabletSideways()) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LazyColumn(Modifier.weight(0.3f).fillMaxHeight(), contentPadding = pad) {
                    headItems(a); credited(this)
                    albumAbout(null, more, client, onAlbum = { id -> onAlbum(id, zone) }, onArtist = { onPage("artist", it) }, onUrl = ::openUrl)
                }
                AlbumWordsColumn(a, extras?.review, client, ::openUrl, Modifier.weight(0.33f).fillMaxHeight().padding(top = 64.dp, bottom = foot))
                LazyColumn(Modifier.weight(0.37f).fillMaxHeight(), contentPadding = pad) { trackItems(a) }
            }
            // A phone held upright (item 349): the head, then the tabs, held at the top as the page scrolls, and the one open.
            else BoxWithConstraints(Modifier.fillMaxSize()) {
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                val tabsScroll = androidx.compose.foundation.rememberScrollState()
                val held = rememberHeldTabs(list)
                LazyColumn(Modifier.fillMaxSize().clipUnderTabs(held).padding(horizontal = 18.dp), state = list, contentPadding = pad) {
                    item { PhoneHead(a, cover, minOf(maxWidth * 0.67f, 300.dp), extras, onPage, badges = {
                        SourceBadge(a.album.source, Modifier.align(Alignment.BottomStart))
                        if (booklets.isNotEmpty()) BookletButton(booklets, disc, Modifier.align(Alignment.BottomEnd)) { onBooklet(offset, it) }
                    }) { discs -> onStepTo(discs.items[discs.index].offset, zone, discs) } }
                    actionItem(a)
                    phoneTabs(tab, a.versions.size, tabsScroll) { tab = it }
                    when (tab) {
                        "credits" -> { albumCredits(credits, client, onArtist = { onPage("artist", it) }, head = false); if (credits?.credits.isNullOrEmpty()) item { Text(AlbumVersion.NO_CREDITS, Modifier.parity("album.tab-empty").padding(vertical = 24.dp), color = dim) } }
                        "about" -> { aboutHead(a, extras, onPage); albumAbout(extras?.review, more, client, onAlbum = { id -> onAlbum(id, zone) }, onArtist = { onPage("artist", it) }, onUrl = ::openUrl) }
                        "versions" -> albumVersions(a, client, onOpen = { v -> shown = v.album.offset; tab = "tracks" },
                            onPlay = { v -> play("") { it.playAlbum(v.album.offset, v.album.title, v.album.subtitle, zone, "play_now") } }, onMain = { v -> makeMain(v.album.offset) })
                        else -> trackItems(a, head = false)
                    }
                }
                HeldTabs(held, tab, a.versions.size, tabsScroll) { tab = it }
            }
            // Back at the corner, over the glow (item 373: Share is in the ⋯, and no previous and next of its own).
            FilledIconButton(onClick = onBack, colors = disc, modifier = Modifier.parity("album.back", Contract.label("album.back")).padding(start = 12.dp, top = 12.dp).size(40.dp).align(Alignment.TopStart)) {
                Icon(painterResource(R.drawable.np_back), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Selecting tracks, their menu at the corner, as on the page.
                val p = picks
                val a = album
                if (p != null && a != null) SelectMenu(p, onAct = { act ->
                    when (act) {
                        "clear" -> picks = null
                        "later" -> { later = true; mark { it.listenLater(shown, true) }; toast("Added to Listen later"); picks = null }
                        // The phone's own: each to its player, in order (Play next last to first), as the page's playTracks.
                        else -> if (phone != null) {
                            val order = p.sorted().let { if (act == "play_next") it.reversed() else it }
                            order.forEachIndexed { n, i -> playPhone(i, if (act == "play_next" || n == 0) act else "queue") }
                            picks = null
                        } else {
                            val c = client()
                            if (c == null || zone.isEmpty()) toast("Pick a zone first")
                            else scope.launch { toast(withContext(Dispatchers.IO) { playPicks(c, a, p, zone, act) }); picks = null }
                        }
                    }
                })
            }
            if (chrome) Column(Modifier.align(Alignment.BottomCenter)) {
                MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
                TouchNav(lit, onTap = onNav)
            }
            // Its editor (item 74), over the page; the page read again, and the walls told, once it changed the album.
            // Its Share card (item 75), centred above the mini player and the row, as the page keeps room for them.
            if (sharing) album?.let { ShareSheet(it.album.title, it.album.subtitle, it.album.imageKey, 144.dp, client) { sharing = false } }
            if (editing) album?.let { AlbumEditSheet(shown, it.album.title, it.album.subtitle, client, onClose = { editing = false }, onChanged = { loads++ }) }
        }
    }
}
