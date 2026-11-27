package com.musicd.mallard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.NowPlayingView
import com.musicd.mallard.client.QueueItem
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfPlaying
import com.musicd.mallard.client.ShelfZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Playing from the native Shelf (bug list item 207; the page's shelf-player.js and shelf-queue.js): the zone it plays
 * in (the room the app's screens play in), and the queue read while its pane is open. An album opened on the Shelf is
 * played from its album page, the library's (item 218, ShelfScreen.kt). Nothing is drawn here.
 */
internal class ShelfPlay(private val shelf: ShelfControl, private val client: () -> ServerClient?, private val scope: CoroutineScope) {
    /** The zone played in, and its name once the server has said. */
    var zone by mutableStateOf(""); private set
    var zones by mutableStateOf<List<ShelfZone>>(emptyList()); private set
    /** Mandarin answered about the zones, so "none" means none. */
    var zonesRead by mutableStateOf(false); private set

    /** The queue, while its pane is open: its tracks, what it says instead, and the one offering Play from here. */
    var queueOpen by mutableStateOf(false)
    var queue by mutableStateOf<List<QueueItem>>(emptyList()); private set
    var queueSaid by mutableStateOf(""); private set
    var armed by mutableStateOf<Long?>(null)

    /** The zone the screen was opened in ([wanted]: the app's room, or none). */
    fun start(wanted: String) { zone = wanted; readZones(wanted.ifEmpty { null }) }

    /** The zones read; with none chosen yet (or one gone), one picked as the page picks it. */
    fun readZones(wanted: String? = null, then: () -> Unit = {}) {
        scope.launch {
            val c = client() ?: return@launch
            val got = withContext(Dispatchers.IO) { runCatching { ShelfZone.list(c.getJson("/api/zones")) }.getOrNull() }
            if (got == null) { if (zones.isEmpty()) shelf.say("Mandarin didn’t answer. Try again in a moment."); return@launch }
            zones = got; zonesRead = true
            if (zone.isEmpty() || got.none { it.id == zone }) ShelfZone.pick(got, wanted ?: zone.ifEmpty { null })?.let { zone = it.id }
            then()
        }
    }

    /** A zone chosen from the list: the room the app's screens play in too. */
    fun choose(id: String) { zone = id; NativeShell.roomChosen(id); if (queueOpen) readQueue() }

    private fun send(what: String, block: suspend (ServerClient) -> Unit) {
        val c = client() ?: run { shelf.say("Mandarin didn’t answer. Try again."); return }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
            r.exceptionOrNull()?.let { e -> shelf.say((e as? ServerClient.ServerException)?.message?.takeIf { !it.startsWith("HTTP") } ?: what) }
        }
    }

    /** Play/pause, previous and next, to the zone in the bar. */
    fun control(command: String) { if (zone.isNotEmpty()) send("That didn’t work. Try again.") { it.control(zone, command) } }

    /** The zone's volume to [value] on its own scale; or, with none, a step [direction] (the page's relative step). */
    fun volumeTo(value: Double) { if (zone.isNotEmpty()) send("That didn’t work. Try again.") { it.zoneVolume(zone, value) } }
    fun volumeStep(direction: Int, step: Double) {
        // A step, not a level: this server reads `value` as the level itself, `relative` as a step (ServerClient.zoneVolumeStep).
        if (zone.isNotEmpty()) send("That didn’t work. Try again.") { it.zoneVolumeStep(zone, direction, step.toInt().coerceAtLeast(1)) }
    }

    // ---- The queue --------------------------------------------------------------------------------------------------
    /** Read while its pane is open: the zone's queue, or what is said instead. */
    fun readQueue() {
        if (!queueOpen) return
        if (zone.isEmpty()) { queue = emptyList(); queueSaid = if (!zonesRead) "Reading the queue…" else if (zones.isNotEmpty()) "Choose a zone in the player below to see its queue." else "No zones found."; return }
        val c = client() ?: return
        val z = zone
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.queue(z) } }
            if (!queueOpen || z != zone) return@launch
            r.getOrNull()?.let { queue = it.items; queueSaid = if (it.items.isEmpty()) "Nothing is queued." else "" } ?: run {
                val e = r.exceptionOrNull() as? ServerClient.ServerException
                if (queue.isNotEmpty() && e?.status != 404) return@launch   // a blip under a list shown: kept
                queue = emptyList(); queueSaid = if (e?.status == 404) "That zone isn’t there any more." else "The queue couldn’t be read."
            }
        }
    }

    /** The queue plays on from [item]. */
    fun playFrom(item: QueueItem) {
        val c = client() ?: return
        val z = zone
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.playFromHere(z, item.id) } }
            shelf.say(if (r.isSuccess) "Playing from ${item.title.ifEmpty { "here" }}" else "That didn’t work. Try again.")
            armed = null
            delay(600); readQueue()
        }
    }

    /** The playing record brought to the front of the shelf, where it is on it ([view]: what the zone plays). */
    fun toPlaying(view: NowPlayingView?) {
        if (zone.isEmpty()) { shelf.say("Choose a zone to play in first"); return }
        if (view == null) { shelf.say("Nothing is playing"); return }
        if (view.album.isEmpty()) { shelf.say("What’s playing isn’t an album on this shelf"); return }
        val i = ShelfPlaying.index(shelf.list, view.albumId, view.album, view.artist)
        if (i < 0) { shelf.say("The playing record isn’t on this shelf"); return }
        shelf.bringTo(i)
    }

}
