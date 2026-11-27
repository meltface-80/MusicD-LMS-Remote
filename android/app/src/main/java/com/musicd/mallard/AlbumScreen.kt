package com.musicd.mallard

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.musicd.mallard.client.AlbumExtras
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.FavouriteKey
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An album's page, natively, drawn as Mandarin's sleeve: the cover's glow behind the top, the cover with the title, the
 * artists (each a link), the date, rate and length; the heart, Play Now, Queue and ⋯; Tracks and About; the mini player
 * and the row along the foot ([lit], the row's button lit under it).
 *
 * Its buttons play in [zoneId], or the room chosen since on the mini player's Play on ([onRoom]). A track is the album's
 * offset and its place in the list — MusicD LMS never hands out track ids — so a page whose offset has moved on (the
 * library was re-indexed) says so and is closed by Back rather than playing the wrong album.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumScreen(offset: Long, zoneId: String, lit: String, client: () -> ServerClient?, list: NavList?, onStepTo: (Long, String, NavList) -> Unit,
                         onBack: () -> Unit, onPage: (String, String?) -> Unit,
                         onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onAlbum: (Long, String) -> Unit,
                         chrome: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember(offset) { mutableStateOf(zoneId) }
    var tab by remember(offset) { mutableStateOf("tracks") }
    var album by remember(offset) { mutableStateOf<Album?>(null) }
    var extras by remember(offset) { mutableStateOf<AlbumExtras?>(null) }
    var more by remember(offset) { mutableStateOf<AlbumMore?>(null) }
    var picks by remember(offset) { mutableStateOf<List<Int>?>(null) }   // selecting tracks: their places, in order of picking
    var failed by remember(offset) { mutableStateOf<String?>(null) }
    var favourite by remember(offset) { mutableStateOf(false) }
    var sharing by remember(offset) { mutableStateOf(false) }   // its Share card open
    var loads by remember(offset) { mutableIntStateOf(0) }
    // Not reached, or the server's own trouble: asked again at once when it answers again, else in a while.
    var again by remember(offset) { mutableStateOf(false) }
    val room = followRoom(zone, client)
    BackHandler(onBack = onBack)

    AskAgain(again) { loads++ }
    LaunchedEffect(offset, loads) {
        val c = client() ?: return@LaunchedEffect
        val lib = LibraryClient { c.getJson(it) }
        val got = withContext(Dispatchers.IO) { runCatching { lib.album(offset) } }
            .onSuccess { album = it; LibraryWall.coverIs(offset, it.album.imageKey) }
            .onFailure { failed = if (it is ServerClient.ServerException && it.stale) "That album has moved — the library was rescanned. Go back and open it again."
                                  else Failure.said(it, "Couldn't load the album") + if (Failure.worthAgain(it)) ". Trying again…" else "" }
        again = album == null && got.exceptionOrNull()?.let(Failure::worthAgain) == true
        val a = album ?: return@LaunchedEffect
        // The heart, as the server's favourites have it (keyed on title and artist, so it survives a rescan).
        withContext(Dispatchers.IO) { runCatching { c.favouriteKeys() }.getOrNull() }?.let { keys -> favourite = FavouriteKey.of(a.album.title, a.album.subtitle) in keys }
        extras = withContext(Dispatchers.IO) { runCatching { lib.albumExtras(a.album.title, a.album.subtitle) }.getOrNull() }
        more = withContext(Dispatchers.IO) { runCatching { loadMore(lib, a) }.getOrNull() }
    }
    // A write-up's page: the phone's browser.
    fun openUrl(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val cover = rememberCover(album?.album?.imageKey, 640, client)
    val glow by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, cover) { value = cover?.let { albumGlowOf(it) } }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // To the room: what came of it said; nothing when [said] is empty (Play Now: the mini player shows what plays),
    // unless it failed. One at a time, the buttons dimmed till it is answered.
    var starting by remember(offset) { mutableStateOf(false) }
    fun play(said: String, block: (ServerClient) -> JSONObject?) {
        val c = client() ?: return
        if (zone.isEmpty()) { toast("Pick a player first"); return }
        if (starting) return else starting = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
                val err = r.exceptionOrNull()?.message
                if (err != null) toast(err) else if (said.isNotEmpty()) toast(said)
            } finally { starting = false }
        }
    }
    // The heart: set on the server (and mirrored into LMS's own Favorites, best-effort); what LMS made of it said only
    // when it is worth saying.
    fun setFavourite(a: Album, on: Boolean) {
        val c = client() ?: return
        favourite = on
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.favourite(a.album, on) } }
            r.onFailure { favourite = !on; toast(Failure.said(it, "Couldn't reach the server")) }
            r.onSuccess { j -> favourite = j.optBoolean("favourite", on); toast(FavouriteKey.said(favourite, j.optString("lms_reason", ""))) }
        }
    }

    val bg = MaterialTheme.colorScheme.background
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val brass = MaterialTheme.colorScheme.primary
    val disc = IconButtonDefaults.filledIconButtonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = brass, disabledContentColor = MaterialTheme.colorScheme.onPrimary)
    Surface(Modifier.fillMaxSize(), color = bg) {
        Box(Modifier.fillMaxSize()) {
            // The cover's colours washing the top of the page: blurred once, small, faint, fading out downwards.
            cover?.let {
                Box(Modifier.parity("album.glow").fillMaxWidth().height(420.dp)) {
                    glow?.let { g -> Image(g, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low, modifier = Modifier.fillMaxSize().alpha(0.26f)) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to bg.copy(alpha = 0.4f), 1f to bg)))
                }
            }
            fun LazyListScope.actionItem(a: Album) {
                item {
                    // The heart, Play Now and Queue in brass, and ⋯, 42 tall.
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AlbumHeartButton(null, null, favourite, onLike = { setFavourite(a, !favourite) }, onAdd = {})
                        val pill = ButtonDefaults.buttonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary)
                        Button(onClick = { play("") { it.playAlbum(offset, zone, "play_now") } }, enabled = !starting, colors = pill, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.parity("album.play-now", Contract.label("album.play-now")).weight(1f).height(42.dp)) { Text(Contract.label("album.play-now"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        Button(onClick = { play("Queued") { it.playAlbum(offset, zone, "queue") } }, enabled = !starting, colors = pill, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.parity("album.queue", Contract.label("album.queue")).weight(1f).height(42.dp)) { Text(Contract.label("album.queue"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        MoreMenu(a, onPlay = { kind, said -> play(said) { it.playAlbum(offset, zone, kind) } }, onPage = onPage)
                    }
                }
            }
            fun LazyListScope.trackItems(a: Album, head: Boolean = true) {
                // The track playing, found by its title on this album (MusicD LMS says no track id for what plays).
                val v = room.view
                val playingId = if (v != null && v.album == a.album.title) a.tracks.indexOfFirst { it.title == v.title }.takeIf { it >= 0 }?.toLong() else null
                val onPick = { i: Int -> val p = picks; picks = if (p == null) listOf(i) else if (i in p) (p - i).ifEmpty { null } else p + i }
                val onPlay = { i: Int, kind: String, said: String -> play("$said: ${a.tracks[i].title}") { it.playTrack(offset, i, zone, kind) } }
                albumTracks(a, playingId, room.view?.playing == true, picks, onPick, onPlay, head)
            }
            val a = album
            // The foot keeps room for the mini player and the row; the head starts below the corner discs.
            val foot = if (chrome) 176.dp else 32.dp
            val pad = PaddingValues(top = 64.dp, bottom = foot)
            if (a == null) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = pad) {
                item { Text(failed ?: "Loading…", Modifier.parity("album.notice").padding(vertical = 24.dp), color = dim) }
            }
            // A tablet held sideways: three columns, each scrolling on its own.
            else if (tabletSideways()) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LazyColumn(Modifier.weight(0.3f).fillMaxHeight(), contentPadding = pad) {
                    item { BoxWithConstraints { PhoneHead(a, cover, minOf(maxWidth, 300.dp), extras, onPage, badges = {
                        SourceBadge(a.album.source, Modifier.align(Alignment.BottomStart))
                    }) { discs -> onStepTo(discs.items[discs.index].offset, zone, discs) } } }
                    aboutHead(a, extras, onPage)
                    actionItem(a)
                    albumAbout(null, more, client, onAlbum = { id -> onAlbum(id, zone) }, onArtist = { onPage("artist", it) }, onUrl = ::openUrl)
                }
                AlbumWordsColumn(a, extras?.review, client, ::openUrl, Modifier.weight(0.33f).fillMaxHeight().padding(top = 64.dp, bottom = foot))
                LazyColumn(Modifier.weight(0.37f).fillMaxHeight(), contentPadding = pad) { trackItems(a) }
            }
            // A phone held upright: the head, then the tabs, held at the top as the page scrolls, and the one open.
            else BoxWithConstraints(Modifier.fillMaxSize()) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val tabsScroll = androidx.compose.foundation.rememberScrollState()
                val held = rememberHeldTabs(listState)
                LazyColumn(Modifier.fillMaxSize().clipUnderTabs(held).padding(horizontal = 18.dp), state = listState, contentPadding = pad) {
                    item { PhoneHead(a, cover, minOf(maxWidth * 0.67f, 300.dp), extras, onPage, badges = {
                        SourceBadge(a.album.source, Modifier.align(Alignment.BottomStart))
                    }) { discs -> onStepTo(discs.items[discs.index].offset, zone, discs) } }
                    actionItem(a)
                    phoneTabs(tab, 0, tabsScroll) { tab = it }
                    when (tab) {
                        "about" -> { aboutHead(a, extras, onPage); albumAbout(extras?.review, more, client, onAlbum = { id -> onAlbum(id, zone) }, onArtist = { onPage("artist", it) }, onUrl = ::openUrl) }
                        else -> trackItems(a, head = false)
                    }
                }
                HeldTabs(held, tab, 0, tabsScroll) { tab = it }
            }
            // Back at the corner, over the glow.
            FilledIconButton(onClick = onBack, colors = disc, modifier = Modifier.parity("album.back", Contract.label("album.back")).padding(start = 12.dp, top = 12.dp).size(40.dp).align(Alignment.TopStart)) {
                Icon(painterResource(R.drawable.np_back), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Share at the corner, as the page's .modal-chrome has it: the album's Share card.
                if (album != null) FilledIconButton(onClick = { sharing = true }, colors = disc,
                    modifier = Modifier.parity("album.share", Contract.label("album.share")).size(40.dp)) {
                    Icon(painterResource(R.drawable.np_share), contentDescription = null, modifier = Modifier.size(20.dp))
                }
                // Selecting tracks, their menu at the corner.
                val p = picks
                val al = album
                if (p != null && al != null) SelectMenu(p, onAct = { act ->
                    if (act == "clear") picks = null
                    else {
                        val c = client()
                        if (c == null || zone.isEmpty()) toast("Pick a player first")
                        else scope.launch { toast(withContext(Dispatchers.IO) { playPicks(c, al, p, zone, act) }); picks = null }
                    }
                })
            }
            // The Share card, over the screen, above the mini player and the nav when they are shown.
            if (sharing) album?.let { ShareSheet(it.album.title, it.album.subtitle, it.album.imageKey, if (chrome) 144.dp else 0.dp, client) { sharing = false } }
            if (chrome) Column(Modifier.align(Alignment.BottomCenter)) {
                MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
                TouchNav(lit, onTap = onNav)
            }
        }
    }
}
