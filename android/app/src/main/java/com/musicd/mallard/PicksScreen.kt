package com.musicd.mallard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Pick
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart Picks, natively (docs/specs/screens.json, picks; the page's
 * showSmartPicks and smartPickCard, style.css .pick-card-full): the heading
 * and the bar's Back, then each pick as a card: its cover beside the artist,
 * the album and why it was chosen, and under them what can be done with it:
 * Open where the library has it, ＋ Add to your streaming favourites where it
 * doesn't (✓ Added once done, until it is imported), and Not for me, which
 * keeps the artist out for good; the banner while there are none; the mini
 * player and the row along the foot.
 */
@Composable
internal fun PicksScreen(prefs: WallPrefs, zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long, String) -> Unit,
                         onNowPlaying: (String) -> Unit, onRoom: (String) -> Unit, onNav: (String) -> Unit, onPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf(zoneId) }
    val room = followRoom(zone, client)
    var picks by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var banner by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    BackHandler(onBack = onBack)
    // Failed, asked again when the server answers again or in a while (item 157).
    var asks by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    AskAgain(failed) { asks++ }
    LaunchedEffect(asks) {
        val c = client() ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.smartPicks() } }
        failed = r.exceptionOrNull()?.let { com.musicd.mallard.client.Failure.worthAgain(it) } == true
        r.onSuccess { sp ->
            picks = sp.picks
            // The page's words: the server's own reason when it has one, else that it didn't answer; building, or none yet.
            banner = when {
                sp.error != null -> "Couldn't load Smart Picks — " + sp.error to true
                sp.picks.isEmpty() -> (if (sp.serviceReady) "Building today's picks — this takes a minute the first time. Come back shortly." else "No picks today yet — come back shortly.") to false
                else -> null
            }
        }.onFailure { e -> banner = ("Couldn't load Smart Picks — " + (e.message?.takeIf { !it.startsWith("HTTP") } ?: "the extension didn't answer. Try again.")) to true }
        loaded = true
    }
    // Never this artist again: the card goes, as the page's does.
    fun block(p: Pick) {
        val c = client() ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.blockSmartPick(p.artist) } }
            r.onSuccess { picks = picks.filter { it !== p }; toast("Won't suggest " + p.artist + " again") }.onFailure { toast(com.musicd.mallard.client.Failure.said(it, "Couldn't save that")) }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = WALL_PADDING) {
            // The heading, with the page's 14 under it (its list's), then the cards 14 apart.
            item { WallHead("Smart Picks", prefs.copy(layout = "list"), if (loaded) banner else null) }
            items(picks.size, key = { picks[it].artist + "|" + picks[it].album }) { i ->
                PickCardFull(picks[i], zone, client, onOpen = { offset -> onAlbum(offset, zone) }, onBlock = { block(picks[i]) }, onSaid = ::toast)
            }
        }
        WallBack(onBack)
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { onNowPlaying(zone) }, onRoom = { id -> zone = id; onRoom(id) }, onPage = onPage)
            TouchNav("", onTap = onNav)
        }
    }
}

/** One pick as a card (style.css .pick-card-full): the cover 96 beside the words, the two buttons under them. */
@Composable
private fun PickCardFull(p: Pick, zone: String, client: () -> ServerClient?, onOpen: (Long) -> Unit, onBlock: () -> Unit, onSaid: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // The pick's cover: the library's own where it has it, else the service's picture.
    val cover = if (p.imageKey != null) rememberTileCover(p.imageKey, 400, client) else rememberImage(p.image, client)
    // Added: a one-way action; the button latches to ✓ Added rather than toggling back (the page's addSmartPick).
    var adding by remember(p) { mutableStateOf(0) }   // 0: ＋ Add, 1: on its way, 2: ✓ Added
    val card = RoundedCornerShape(12.dp)
    val faint = paletteColor(Palette.TEXT_FAINT)
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(card).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, card).padding(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.parity("picks.pick-art").size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))) {
                cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.artist, Modifier.parity("picks.pick-artist"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                Text(p.album, Modifier.parity("picks.pick-album"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                p.reason?.takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.parity("picks.pick-reason").padding(top = 4.dp), color = faint, fontSize = 12.sp, lineHeight = 17.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pill = RoundedCornerShape(999.dp)
            val offset = p.offset
            if (offset != null) {
                // The library has it: an ordinary open, in brass (the page's .pick-play).
                Box(Modifier.parity("picks.open", Contract.label("picks.open")).weight(1f).height(36.dp).clip(pill).background(MaterialTheme.colorScheme.primary).clickable { onOpen(offset) }, contentAlignment = Alignment.Center) {
                    Text(Contract.label("picks.open"), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.01.em)
                }
            } else {
                // Not in the library: played straight from the streaming service, found again by search at the tap
                // (LMS's Qobuz plugin hands out no lasting album ids). Nothing is added to play it.
                Box(Modifier.parity("picks.play").weight(1f).height(36.dp).clip(pill).background(MaterialTheme.colorScheme.primary).clickable {
                    val c = client() ?: return@clickable
                    if (zone.isEmpty()) { onSaid("Pick a player first"); return@clickable }
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { c.playSmartPick(p.artist, p.album, zone, "play_now") } }
                        r.exceptionOrNull()?.let { onSaid(it.message ?: "Couldn't play that album") }
                    }
                }, contentAlignment = Alignment.Center) {
                    Text("Play", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.01.em)
                }
            if (p.added || adding == 2) {
                // In your streaming favourites, not imported yet: nothing to press.
                Box(Modifier.parity("picks.added", Contract.label("picks.added")).weight(1f).height(36.dp).alpha(0.6f).clip(pill).background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, pill).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Text(Contract.label("picks.added"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600)
                }
            } else {
                Box(Modifier.parity("picks.add", Contract.label("picks.add", adding)).weight(1f).height(36.dp).alpha(if (adding == 1) 0.6f else 1f).clip(pill).background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.primary, pill)
                    .clickable(enabled = adding == 0) {
                        val c = client() ?: return@clickable
                        adding = 1
                        scope.launch {
                            // Kept: favourited in the streaming account (LMS's Qobuz plugin), so a later scan brings it in.
                            val r = withContext(Dispatchers.IO) { runCatching { c.addSmartPick(p.artist, p.album) } }
                            val ok = r.getOrNull()?.optBoolean("ok", false) == true
                            if (ok) { adding = 2; onSaid("Added to your Qobuz favourites") }
                            else { adding = 0; onSaid(r.exceptionOrNull()?.message ?: r.getOrNull()?.optString("error")?.ifEmpty { null } ?: "Couldn't add that album") }
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text(Contract.label("picks.add", adding), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600)
                }
            }
            }
            Box(Modifier.parity("picks.block", Contract.label("picks.block")).weight(1f).height(36.dp).clip(pill).background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, pill).clickable(onClick = onBlock), contentAlignment = Alignment.Center) {
                Text(Contract.label("picks.block"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600)
            }
        }
    }
}
