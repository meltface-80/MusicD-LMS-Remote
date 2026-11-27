package com.musicd.mallard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfCase
import com.musicd.mallard.client.ShelfChoices
import com.musicd.mallard.client.ShelfGeometry
import com.musicd.mallard.client.ShelfLibrary
import com.musicd.mallard.client.ShelfMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * The native Shelf's own state and motion (bug list item 207; the page's shelf.js and shelf-pick.js): the library read
 * once and asked again with its signature, the choices applied to it, and the shelf's place moved frame by frame — a
 * spring to rest on an album, a three-second spin, a finger's swipe, flick and hold. Nothing is drawn here: the stage
 * (ShelfStage.kt) draws what this says, and the screen (ShelfScreen.kt) asks it to move.
 */
internal class ShelfControl(private val context: Context, private val client: () -> ServerClient?, private val scope: CoroutineScope) {
    /** The library as last read; null until it is. */
    var lib by mutableStateOf<ShelfLibrary?>(null); private set
    /** The choices made, kept per device as the page keeps them (the order, and the genres' order: item 213). */
    val choices = ShelfChoices(Store.shelf(context, "order"), Random.nextLong(1, 0xffffffffL), Store.shelf(context, "genre-sort"))
    /** What the shelf holds now, its divider cards, and a count that moves whenever either does (or a choice). */
    var list by mutableStateOf<List<ShelfCase>>(emptyList()); private set
    var tabs by mutableStateOf<List<String>>(emptyList()); private set
    var rev by mutableIntStateOf(0); private set
    /** What the choices say while the library is being read, or couldn't be. */
    var waiting by mutableStateOf("Reading your library…"); private set
    /** What the stage says with nothing to show: its title, why, and whether Undo and Reset are offered. */
    var note by mutableStateOf<Triple<String, String, Boolean>?>(null); private set
    /** When the library was first read (the playing record is put in front only soon after). */
    var readyAt = 0L; private set

    // ---- The motion: the shelf's place [p] (an album a unit), its speed, where it is going, and what moves it ----
    var geometry by mutableStateOf(ShelfGeometry.of(390f, 700f))
    var p by mutableFloatStateOf(0f)
    private var vp = 0f
    private var target = 0f
    /** idle, spring, spin, drag. */
    var mode by mutableStateOf("idle"); private set
    /** The place whose case is turned over (the album view open on it), or null. */
    var flipped by mutableStateOf<Int?>(null)
    /** A new shelf fades in from this moment (0: drawn whole). */
    var fadeFrom by mutableLongStateOf(0L); private set
    /** The spin under way: from, by, its start and length. */
    private var spinFrom = 0f; private var spinBy = 0f; private var spinAt = 0L; private var spinMs = 0L
    /** When a spin started (the bar's countdown ring and turning disc), and how long it lasts; 0 when none. */
    var spinning by mutableLongStateOf(0L); private set
    var spinLength = ShelfMotion.SPIN_MS; private set
    /** A spin landed: the front case's ring, from this moment. */
    var landedAt by mutableLongStateOf(0L); private set
    /** A held swipe turning the shelf on: -1 left, 1 right, 0 not. */
    var shuttle by mutableIntStateOf(0); private set
    /** Said for a moment at the top: what, and when. */
    var toast by mutableStateOf<Pair<String, Long>?>(null); private set

    private class Drag(val x0: Float, val p0: Float, val t0: Long, val caught: Boolean) {
        var x = x0; var still = t0; var moved = false; var shuttling = false; var acc = 0f; var rate = 0f
        val samples = ArrayDeque<Pair<Long, Float>>()
    }
    private var drag: Drag? = null

    val n: Int get() = list.size
    val wraps: Boolean get() = ShelfMotion.wraps(n)

    /** The album in front, or null on an empty shelf. */
    fun centre(): ShelfCase? = if (n == 0) null else list[ShelfMotion.mod(p.roundToInt(), n)]

    /** The album the view is open on: the case turned over at the front. */
    fun opened(): ShelfCase? = flipped?.takeIf { abs(it - p) < 0.5f }?.let { list[ShelfMotion.mod(it, n)] }

    fun say(text: String) { toast = text to System.currentTimeMillis() }

    // ---- The library ------------------------------------------------------------------------------------------------
    private var loading = false
    private var retryMs = 5000L

    /** The library read, or asked again with the signature held (a few bytes while nothing has changed). */
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            val c = client()
            val got = if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.getJson(ShelfLibrary.path(lib?.sig)) } }
            loading = false
            val j = got?.getOrNull()
            if (j == null) {
                val e = got?.exceptionOrNull() as? ServerClient.ServerException
                // Switched off in Settings: the shelf says so, as the page's /shelf does.
                if (e?.status == 403) { waiting = "Shelf is switched off in Settings → Setup → Shelf."; note = Triple("Shelf is off", waiting, false); return@launch }
                if (lib != null) return@launch   // a true library, only older: the next look asks again
                val building = e?.status == 503
                waiting = if (building) "Reading your library…" else "Mandarin didn’t answer. Trying again…"
                note = if (building) Triple("Reading your library", "Mandarin is still scanning your music folders. The shelf fills as soon as it has found some albums.", false)
                else Triple("Can’t reach Mandarin", "Mandarin didn’t answer. The shelf keeps trying.", false)
                delay(retryMs); retryMs = minOf(60_000L, retryMs * 2); load()
                return@launch
            }
            retryMs = 5000L
            val read = ShelfLibrary.parse(j) ?: return@launch   // the same: nothing to draw again
            val first = lib == null
            choices.carry(read)
            lib = read
            if (first) readyAt = System.currentTimeMillis()
            refresh(reread = !first)
            // Somewhere in the middle of the collection rather than at "A", until the playing record is found.
            if (first && n > 1 && choices.order() != "random") { p = Random.nextInt(n).toFloat(); target = p }
        }
    }

    /** Asked again while nothing is moving and no album is open (the page's checkLive, every 30 s). */
    fun check() { if (lib != null && drag == null && mode == "idle" && flipped == null) load() }

    /**
     * The choices applied again: the same album kept in front where it is still on the shelf, the shelf at rest; a new
     * shelf fades in, one read again (the same place) does not.
     */
    fun refresh(reread: Boolean = false) {
        val l = lib ?: return
        val cur = centre()
        list = choices.list(l)
        tabs = choices.tabsFor(l, list)
        var idx = if (cur == null) -1 else list.indexOf(cur).takeIf { it >= 0 } ?: list.indexOfFirst { it.title == cur.title && it.artist == cur.artist }
        if (idx < 0) idx = 0
        p = idx.toFloat(); target = p; vp = 0f; mode = "idle"; flipped = null
        spinning = 0L; drag = null; shuttle = 0
        fadeFrom = if (reread) 0L else System.currentTimeMillis()
        note = choices.empty(l, n)
        rev++
    }

    /** A choice made or taken back on the choices (its tile, chip, Clear all, Reset, the note's Undo): kept where it is kept, and the shelf filled again. */
    fun choose(change: ShelfChoices.() -> String?) {
        choices.change()?.let { Store.setShelf(context, "order", it) }
        refresh()
    }

    /** The genres put in order [id] on their tab (#N, A → Z, Z → A; item 213): kept on this device, the tiles drawn again. */
    fun sortGenres(id: String) {
        choices.sortGenres(id)?.let { Store.setShelf(context, "genre-sort", it); rev++ }
    }

    // ---- Moving -----------------------------------------------------------------------------------------------------
    /** To rest on place [t] by the spring. */
    fun settleTo(t: Float) {
        target = ShelfMotion.settleTarget(t, n)
        vp = vp.coerceIn(-0.02f, 0.02f)
        mode = "spring"
    }

    /** A spin the way [dir], as hard as [strength]: it lands somewhere three seconds later. */
    fun spin(dir: Int, strength: Float) {
        if (n < 2) {
            say(if (n == 1) "There’s only one album on this shelf" else "Nothing on the shelf to spin")
            if (n == 1) settleTo(p.roundToInt().toFloat())
            return
        }
        flipped = null
        spinFrom = p; spinBy = ShelfMotion.spinBy(p, n, dir, strength) { Random.nextDouble() }
        spinAt = System.currentTimeMillis(); spinMs = if (wraps) ShelfMotion.SPIN_MS else ShelfMotion.SPIN_SHORT_MS
        spinLength = spinMs; spinning = spinAt
        mode = "spin"; vp = 0f
    }

    /** One frame [dtMs] on, at [now]: the spring, the spin or the held swipe moves the shelf; false once it is at rest. */
    fun frame(dtMs: Float, now: Long): Boolean {
        when (mode) {
            "spring" -> { val (np, nv, rest) = ShelfMotion.spring(p, vp, target, dtMs); p = np; vp = nv; if (rest) { mode = "idle" } }
            "spin" -> {
                val u = ((now - spinAt).toFloat() / spinMs).coerceIn(0f, 1f)
                p = ShelfMotion.spun(spinFrom, spinBy, u)
                if (u >= 1f) { p = p.roundToInt().toFloat(); target = p; mode = "idle"; spinning = 0L; landed() }
            }
            "drag" -> stepDrag(now, dtMs)
        }
        if (fadeFrom != 0L && now - fadeFrom > 420) fadeFrom = 0L
        // Moved off the album: its case turns back, unless the shelf is on its way to it.
        val f = flipped
        if (f != null && abs(f - p) >= 0.5f && !(mode == "spring" && target == f.toFloat())) flipped = null
        return mode != "idle" || fadeFrom != 0L
    }

    private fun landed() {
        landedAt = System.currentTimeMillis()
        centre()?.let { say("Landed on ${it.title}") }
    }

    /** A finger down on the stage at [x]: a spin or a drift caught where it is. */
    fun down(x: Float, now: Long) {
        if (n == 0) return
        val caught = mode == "spin" || (mode == "spring" && abs(vp) > 0.003f)
        spinning = 0L
        drag = Drag(x, p, now, caught).also { it.samples.addLast(now to x) }
        mode = "drag"; vp = 0f
    }

    fun move(x: Float, now: Long) {
        val d = drag ?: return
        if (abs(x - d.x) > 1.5f) d.still = now
        d.x = x
        d.samples.addLast(now to x)
        while (d.samples.size > 2 && now - d.samples.first().first > 120) d.samples.removeFirst()
        if (!d.moved && abs(d.x - d.x0) > 8) { d.moved = true; flipped = null }
        p = ShelfMotion.dragged(geometry, d.p0, d.x - d.x0, d.acc, n)
    }

    private fun stepDrag(now: Long, dtMs: Float) {
        val d = drag ?: run { mode = "idle"; return }
        val dx = d.x - d.x0
        if (!d.shuttling && d.moved && ShelfMotion.far(geometry, dx) && wraps && now - d.still > 200) d.shuttling = true
        if (d.shuttling) {
            d.rate = ShelfMotion.shuttleRate(geometry, dx)
            d.acc += d.rate * dtMs / 1000f
            shuttle = if (d.rate == 0f) 0 else if (dx < 0) -1 else 1
            p = ShelfMotion.dragged(geometry, d.p0, dx, d.acc, n)
        }
    }

    /** The finger lifted ([cancel]: taken away): a tap (handed to [tap]), a swipe, a flick or the end of a hold. */
    fun up(now: Long, cancel: Boolean, tap: () -> Unit) {
        val d = drag ?: return
        drag = null; shuttle = 0
        if (cancel) { settleTo(p.roundToInt().toFloat()); return }
        if (!d.moved) { if (d.caught) settleTo(p.roundToInt().toFloat()) else { mode = "idle"; tap() }; return }
        if (d.shuttling) { vp = d.rate / 1000f; settleTo((p + kotlin.math.sign(d.rate) * 0.4f).roundToInt().toFloat()); return }
        val recent = d.samples.filter { now - it.first < 100 }
        val v = if (recent.size >= 2 && now - recent.last().first < 80) (recent.last().second - recent.first().second) / maxOf(8f, (recent.last().first - recent.first().first).toFloat()) else 0f
        val r = ShelfMotion.release(geometry, d.p0, p, d.x - d.x0, v, now - d.t0)
        if (r.spinDir != 0) { spin(r.spinDir, r.spinStrength); return }
        vp = r.velocity
        settleTo(r.settle ?: p.roundToInt().toFloat())
    }

    /** A tap on the case at place [v] (null: none there): a spine is brought to the front, the front one turned over. */
    fun tapped(v: Int?) {
        if (v == null) { settleTo(p.roundToInt().toFloat()); return }
        if (abs(v - p) < 0.5f) turnOver(v) else settleTo(v.toFloat())
    }

    /** The case at [v] turned over (the album view opens on it), or back. */
    fun turnOver(v: Int) {
        if (mode == "spin") { mode = "idle"; spinning = 0L }
        flipped = if (flipped == v) null else v
        if (abs(v - p) > 1e-3f) { vp = 0f; settleTo(v.toFloat()) }
    }

    /** A key's step along the shelf ([by] albums): with the album view open, it shows the next album in it. */
    fun step(by: Int) {
        if (n == 0) return
        if (mode == "spin") { mode = "idle"; spinning = 0L }
        val base = if (mode == "spring") target else p.roundToInt().toFloat()
        vp = 0f; settleTo(base + by)
        if (flipped != null) flipped = target.roundToInt()
    }

    /** Straight to album [i] of the list: near, the shelf turns to it; far, it is put there. [open]: its case turned over too. */
    fun bringTo(i: Int, open: Boolean = false) {
        if (n == 0) return
        if (mode == "spin") { mode = "idle"; spinning = 0L }
        val to = ShelfMotion.nearest(p.roundToInt(), i, n)
        if (abs(to - p) > 30 || open) { p = to.toFloat(); target = p; vp = 0f; mode = "idle" } else { vp = 0f; settleTo(to.toFloat()) }
        if (open) flipped = to else flipped = null
    }

    /** A finger is on the shelf, a spin is landing, or an album is open: not a moment to read the library again. */
    val busy: Boolean get() = drag != null || mode != "idle" || flipped != null
}
