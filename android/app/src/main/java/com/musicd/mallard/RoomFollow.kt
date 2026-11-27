package com.musicd.mallard

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.widget.Toast
import com.musicd.mallard.client.NowPlayingView
import com.musicd.mallard.client.Retry
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.VolumeScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * A room followed, for the native screens that show it (Now playing, the mini player): MusicD LMS Remote answers
 * /api/zone-state at once (it can't hold a request until something changes), so it is asked ADAPTIVELY, as the server's
 * own page asks: every 2 s while the room plays, every 6 s when it doesn't; between answers the clock moves the track
 * on. A tap asks again a moment later ([poke]) rather than waiting out the pause. Never a fixed fast poll: every ask
 * reaches LMS live.
 */

/** What the room [id] is doing, as last answered. */
internal class RoomState(val id: String = "") {
    /** The track playing there, null while nothing is (or before the first answer). */
    var view by mutableStateOf<NowPlayingView?>(null)
    /** The room's name, once the server has answered. */
    var zoneName by mutableStateOf<String?>(null)
    /** The room's volume, from its first speaker that has one; null: fixed. */
    var volume by mutableStateOf<VolumeScale?>(null)
    var muted by mutableStateOf(false)
    /** The server has answered at least once. */
    var answered by mutableStateOf(false)
    /** The clock, moved on twice a second while the track plays. */
    var now by mutableLongStateOf(System.currentTimeMillis())
}

/**
 * Each room's state as last answered, kept while the app runs (bug list item 113): every screen draws its mini player,
 * and a screen opened started from nothing, so the bar was drawn without its cover and room line for a moment, a little
 * shorter, then grew back: moving between screens, it moved. Now a screen starts from what the last one knew.
 */
private val kept = HashMap<String, RoomState>()

/** Asked again soon: a tap's change shows in a moment, not at the next turn of the poll. */
internal object RoomPoke {
    val count = mutableStateOf(0)
    fun poke() { count.value++ }
}

/**
 * The room of the screen opened in [zoneId], followed while the app is on screen; its state the one kept for it, so a
 * new screen starts as the last left it.
 */
@Composable
internal fun followRoom(zoneId: String, client: () -> ServerClient?): RoomState {
    val id = zoneId
    val room = remember(id) { kept.getOrPut(id) { RoomState(id) } }
    val onScreen by NativeShell.onScreen
    val poked by RoomPoke.count
    LaunchedEffect(id, onScreen, poked) {
        if (id.isEmpty() || !onScreen) return@LaunchedEffect
        if (poked > 0) delay(200)   // a tap: its change asked for a moment later
        var missed = 0
        while (isActive) {
            val c = client()
            val st = if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.zoneState(id) }.getOrNull() }
            // Not answered (the server away): asked again less and less often, 2 s up to half a minute.
            if (st == null) { delay(Retry.ms(++missed)); continue }
            missed = 0
            room.zoneName = st.zone?.name
            room.volume = st.zone?.outputs?.firstNotNullOfOrNull { it.scale }
            room.muted = st.zone?.outputs?.any { it.muted } ?: false
            room.view = NowPlayingView.of(st, System.currentTimeMillis())
            room.answered = true
            delay(if (st.zone?.isPlaying == true) 2_000 else 6_000)
        }
    }
    LaunchedEffect(room.view) {
        room.now = System.currentTimeMillis()
        while (room.view?.playing == true) { delay(500); room.now = System.currentTimeMillis() }
    }
    return room
}

/**
 * A tap's request ([block]) sent to [c] off the main thread; what it does shows in the room's next answer, asked for a
 * moment later. Refused, what the server said is shown: the tap did nothing to see.
 */
internal fun CoroutineScope.sendTap(context: Context, c: ServerClient, block: (ServerClient) -> Unit) {
    launch(Dispatchers.IO) {
        val refused = runCatching { block(c) }.exceptionOrNull()
        withContext(Dispatchers.Main) {
            RoomPoke.poke()
            if (refused != null) Toast.makeText(context, refused.message ?: "Couldn't reach the server", Toast.LENGTH_SHORT).show()
        }
    }
}

/** The cover [key] at [size] pixels, once fetched; null until then, or with no key. */
@Composable
internal fun rememberCover(key: String?, size: Int, client: () -> ServerClient?): ImageBitmap? = rememberTileCover(key, size, client)

/**
 * The picture at [url] on the server, once fetched; null until then, or with no address. Kept as the tiles' covers are
 * (Covers), so a screen opened draws a picture already fetched at once, rather than a moment later (item 113).
 */
@Composable
internal fun rememberImage(url: String?, client: () -> ServerClient?): ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let { Covers.get(it) }) }
    LaunchedEffect(url) {
        val c = client() ?: return@LaunchedEffect
        if (url == null || image != null) return@LaunchedEffect
        val got = withContext(Dispatchers.IO) {
            runCatching { c.bytes(url).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
        }
        if (got != null) { Covers.put(url, got); image = got }
    }
    return image
}
