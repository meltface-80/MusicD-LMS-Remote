package com.musicd.mallard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfCase
import com.musicd.mallard.client.ShelfChoices
import com.musicd.mallard.client.ShelfCorners
import com.musicd.mallard.client.ShelfMotion
import com.musicd.mallard.client.ShelfPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The Shelf natively (bug list item 207; docs/specs/screens.json, shelf), the side menu's Shelf: a record shop's shelf of
 * the whole library on a phone, as the page's /shelf draws it there — the spines filling the screen down to the bar, the
 * case in front a tall card; the way back in a faint pill at the top left; the choices' tab hanging from the top (they
 * open over the whole screen, ShelfPick.kt) and the queue's at the right edge (it slides in, ShelfQueue.kt); the bar at
 * the foot (ShelfBar.kt) with the zone list and the volume over it (ShelfSheets.kt); the album view over the screen above
 * the bar once the case in front is turned over (the library's album page, AlbumScreen.kt; item 218); a word said for a moment at the top; and the gestures
 * said once. It plays in the room the app's screens play in.
 */
@Composable
internal fun ShelfScreen(zoneArg: String, client: () -> ServerClient?, onBack: () -> Unit, onPage: (String, String?) -> Unit,
                         onNowPlaying: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelf = remember { ShelfControl(context.applicationContext, client, scope) }
    val play = remember { ShelfPlay(shelf, client, scope) }
    val art = remember { ShelfArt(context.applicationContext, client, scope) }
    var pickOpen by remember { mutableStateOf(false) }   // a phone opens on the shelf, as the page's does
    var zonesOpen by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableStateOf(0L) }
    var firstPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        shelf.load()
        play.start(zoneArg.ifEmpty { NativeShell.zone })
        help = helpDue(context, client)
    }
    // Kept up with while on screen: the library asked again with its signature, every 30 s, as the page's checkLive.
    val onScreen by NativeShell.onScreen
    LaunchedEffect(onScreen) { while (onScreen) { delay(30_000); shelf.check(); if (play.queueOpen) play.readQueue() } }
    val room = followRoom(play.zone, client)
    val v = room.view
    // Only on opening: the record playing put in front, unless the shelf has been touched or a while has gone.
    LaunchedEffect(shelf.lib != null, v?.album, v?.albumId) {
        if (!firstPlaying || shelf.lib == null || v == null) return@LaunchedEffect
        firstPlaying = false
        if (touched > shelf.readyAt || System.currentTimeMillis() - shelf.readyAt > 15_000 || v.album.isEmpty()) return@LaunchedEffect
        val i = ShelfPlaying.index(shelf.list, v.albumId, v.album, v.artist)
        if (i >= 0 && shelf.mode == "idle") shelf.bringTo(i)
    }
    // The queue read again when the room's track moves, while it is open.
    LaunchedEffect(play.queueOpen, play.zone, v?.title, v?.album) { if (play.queueOpen) play.readQueue() }
    val opened = shelf.opened()
    // The album shown: the one turned over, or one of its rows' (More by, similar) opened in its place.
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(opened?.offset) { if (opened != null) shown = opened.offset }
    BackHandler {
        when {
            zonesOpen || volumeOpen -> { zonesOpen = false; volumeOpen = false }
            pickOpen -> pickOpen = false
            shelf.flipped != null -> shelf.flipped = null
            play.queueOpen -> play.queueOpen = false
            else -> onBack()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(paletteColor(Palette.BG))) {
        val rem = (maxWidth.value * 0.0055f + 8.5f).coerceIn(14f, 20f).dp
        val drawer = minOf(270.dp, maxWidth * 0.86f)
        val openZones = { zonesOpen = true; volumeOpen = false; play.readZones() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val c = shelf.centre()
                val said = if (c == null) arrayOf("", "", "0", "0")
                    else arrayOf(c.title, c.artist, ShelfChoices.fmt(ShelfMotion.mod(shelf.p.roundToInt(), shelf.n) + 1), ShelfChoices.fmt(shelf.n))
                ShelfStage(shelf, art, said, { a -> shelf.lib?.let { shelf.choices.lineOf(it, a) }.orEmpty() },
                    onTouch = { touched = System.currentTimeMillis(); zonesOpen = false; volumeOpen = false },
                    modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp + rem * 3f))
                shelf.note?.let { (title, why, acts) -> Note(shelf, title, why, acts, rem, Modifier.align(Alignment.Center)) }
                // the queue, drawn in from the right down to the bar, its tab going with it (AnimatedVisibility named in full:
                // inside the Column the bare name is ColumnScope's)
                androidx.compose.animation.AnimatedVisibility(play.queueOpen, Modifier.align(Alignment.CenterEnd), enter = slideInHorizontally(tween(320, easing = SHELF_EASE)) { it },
                    exit = slideOutHorizontally(tween(320, easing = SHELF_EASE)) { it }) {
                    ShelfQueue(play, room.zoneName.orEmpty().takeIf { play.zone.isNotEmpty() }.orEmpty(), v != null, rem, client, onNow = { play.toPlaying(v) }, Modifier.width(drawer))
                }
                EdgeTab(Modifier.align(Alignment.CenterEnd).offset(x = if (play.queueOpen) -drawer else 0.dp).parity("shelf.queue-tab", Contract.label("shelf.queue-tab", if (play.queueOpen) 1 else 0))
                    .clip(RoundedCornerShape(topStart = rem * 0.8f, bottomStart = rem * 0.8f)).clickable { play.queueOpen = !play.queueOpen; if (!play.queueOpen) play.armed = null },
                    rem, if (play.queueOpen) 180f else 0f, rem * 1.55f, rem * 4.4f)
                // the album view, out of the case in front, over the screen above the bar
                androidx.compose.animation.AnimatedVisibility(opened != null, enter = fadeIn(tween(300, delayMillis = 220)) + scaleIn(tween(500, delayMillis = 220, easing = SHELF_EASE), 0.38f, TransformOrigin(0.5f, 0.45f)),
                    exit = fadeOut(tween(220)) + scaleOut(tween(300), 0.38f, TransformOrigin(0.5f, 0.45f))) {
                    // The library's own album page (item 218, the owner: the same screen), in the Shelf's room, its bar under it.
                    shown?.let { id -> Box(Modifier.parity("shelf.album")) {
                        AlbumScreen(id, play.zone, "", client, list = null, onStepTo = { _, _, _ -> }, onBack = { shelf.flipped = null }, onPage = onPage,
                            onNowPlaying = onNowPlaying, onRoom = { z -> play.choose(z) }, onNav = { },
                            onAlbum = { other, _ -> shown = other }, chrome = false)
                    } }
                }
                if (zonesOpen) ShelfZones(play, rem, onClose = { zonesOpen = false }, Modifier.align(Alignment.BottomEnd).padding(end = rem * 1.25f, bottom = rem * 0.5f))
                if (volumeOpen) ShelfVolume(play, room.volume, rem, Modifier.align(Alignment.BottomCenter).padding(start = rem, end = rem, bottom = rem * 0.6f))
            }
            ShelfBar(shelf, play, room, rem, client, zonesOpen, volumeOpen, onZones = { if (zonesOpen) zonesOpen = false else openZones() }, onVolume = { volumeOpen = !volumeOpen; zonesOpen = false })
        }
        // the choices over the whole screen, down from the top; Show N albums takes them away
        AnimatedVisibility(pickOpen, enter = slideInVertically(tween(320, easing = SHELF_EASE)) { -it }, exit = slideOutVertically(tween(320, easing = SHELF_EASE)) { -it }) {
            ShelfPick(shelf, rem, client, onDone = { pickOpen = false })
        }
        // the way back, in the wall display's faint pill; the choices' tab hanging from the top while they are away; neither
        // over an open album, whose own Back takes their place (item 219, core's ShelfCorners)
        if (ShelfCorners.remote(opened != null)) Row(Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp).parity("shelf.back", Contract.label("shelf.back")).height(rem * 2.6f).clip(CircleShape)
            .border(1.dp, Color(0x42ECE8DF), CircleShape).clickable(onClick = onBack).padding(start = rem * 0.6f, end = rem), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rem * 0.2f)) {
            Icon(painterResource(R.drawable.np_back), contentDescription = null, tint = Color(0x9EECE8DF), modifier = Modifier.size(rem * 1.15f))
            Text("Remote", color = Color(0x9EECE8DF), fontSize = (rem.value * 0.85f).sp, fontWeight = FontWeight.W600)
        }
        if (ShelfCorners.pickTab(opened != null, pickOpen)) EdgeTab(Modifier.align(Alignment.TopCenter).statusBarsPadding().parity("shelf.pick-tab", Contract.label("shelf.pick-tab", 0))
            .clip(RoundedCornerShape(bottomStart = rem * 0.8f, bottomEnd = rem * 0.8f)).clickable { pickOpen = true; zonesOpen = false; volumeOpen = false }, rem, -90f, rem * 4.4f, rem * 1.55f)
        shelf.toast?.let { (words, at) ->
            var on by remember(at) { mutableStateOf(true) }
            LaunchedEffect(at) { delay(2400); on = false }
            AnimatedVisibility(on, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp + rem * 6.4f), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                Text(words, Modifier.parity("shelf.toast").widthIn(max = maxWidth - rem * 2).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), CircleShape)
                    .padding(horizontal = rem * 1.15f, vertical = rem * 0.65f), color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.9f).sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        help?.let { version -> ShelfHelp(rem) { never -> Store.setShelf(context, "help", JSONObject().put("seen", version).put("never", never).toString()); help = null } }
    }
}

/** A tab hanging off an edge: its box and its chevron, turned [turn] degrees from pointing left; its tap in [modifier]. */
@Composable
private fun EdgeTab(modifier: Modifier, rem: Dp, turn: Float, w: Dp, h: Dp) {
    Box(modifier.size(w, h).background(paletteColor(Palette.BG_ELEV)).border(1.dp, paletteColor(Palette.BORDER)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.np_back), contentDescription = null, tint = paletteColor(Palette.TEXT_DIM), modifier = Modifier.size(rem).rotate(turn))
    }
}

/** What the stage says with nothing on it: its title and why, and Undo and Reset where the choices left nothing. */
@Composable
private fun Note(shelf: ShelfControl, title: String, why: String, acts: Boolean, rem: Dp, modifier: Modifier) {
    Column(modifier.widthIn(max = rem * 27f).padding(horizontal = rem), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(rem * 0.65f)) {
        Text(title, Modifier.parity("shelf.note-title"), color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontSize = (rem.value * 1.6f).sp, textAlign = TextAlign.Center)
        Text(why, Modifier.parity("shelf.note-why"), color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value).sp, textAlign = TextAlign.Center)
        if (acts) Row(Modifier.padding(top = rem * 0.4f), horizontalArrangement = Arrangement.spacedBy(rem * 0.6f)) {
            Box(Modifier.parity("shelf.undo", Contract.label("shelf.undo")).height(rem * 2.9f).clip(CircleShape).background(paletteColor(Palette.BG_ELEV)).border(1.dp, paletteColor(Palette.BORDER), CircleShape)
                .clickable { shelf.choose { undo(); null } }.padding(horizontal = rem), contentAlignment = Alignment.Center) {
                Text("Undo last choice", color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.92f).sp, fontWeight = FontWeight.W700)
            }
            Box(Modifier.parity("shelf.note-reset", Contract.label("shelf.note-reset")).height(rem * 2.9f).clip(CircleShape).border(1.dp, paletteColor(Palette.BORDER), CircleShape)
                .clickable { shelf.choose { clearAll() } }.padding(horizontal = rem), contentAlignment = Alignment.Center) {
                Text("Reset shelf", color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.92f).sp, fontWeight = FontWeight.W700)
            }
        }
    }
}

/**
 * The gestures' help is due (the page's maybeShowHelp): never shown here, or shown at another version of Mandarin than
 * this one, and not ticked Don't show again. The version it is due at, or null.
 */
private suspend fun helpDue(context: android.content.Context, client: () -> ServerClient?): String? {
    val saved = runCatching { JSONObject(Store.shelf(context, "help") ?: "{}") }.getOrDefault(JSONObject())
    if (saved.optBoolean("never", false)) return null
    val c = client() ?: return null
    val version = withContext(Dispatchers.IO) { runCatching { c.updateStatus().optString("current", "") }.getOrDefault("") }
    if (saved.has("seen") && (saved.optString("seen") == version || version.isEmpty())) return null
    return version
}
