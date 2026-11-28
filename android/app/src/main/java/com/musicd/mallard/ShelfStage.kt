package com.musicd.mallard

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ShelfMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/** The turn's curve (shelf.css .box: .65s cubic-bezier(.3,.7,.2,1)). */
internal val SHELF_EASE = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)

/**
 * The native Shelf's stage (bug list item 207; docs/specs/screens.json, shelf.stage and shelf.case), drawn as the page's
 * shelf.js draws it: the cases stand in a row of spines, the one in front turned to show its cover (a tall card on a stage
 * taller than the cover is wide), each placed in three dimensions about the stage's middle and seen in the rig's
 * perspective, nearer the front drawn over farther, on a ledge. A swipe moves one album, a swipe held out keeps it turning,
 * a flick spins it; a tap brings a spine to the front, or turns the front case over and opens the album. The arrows, Enter
 * and S do the same from a keyboard or a TV's remote.
 */
@Composable
internal fun ShelfStage(shelf: ShelfControl, art: ShelfArt, said: Array<String>, line: (com.musicd.mallard.client.ShelfCase) -> String, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val faces = remember(density) { ShelfFaces(context, density) }
    val hits = remember { ArrayList<Pair<Int, RectF>>() }
    // The turn of the case at the front, 0 to 180 degrees, and which case it is (kept while it turns back).
    val flip = remember { Animatable(0f) }
    var flipV by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(shelf.flipped) {
        val f = shelf.flipped
        if (f != null) { flipV = f; flip.animateTo(180f, tween(650, easing = SHELF_EASE)) } else flip.animateTo(0f, tween(650, easing = SHELF_EASE))
    }
    // Frame by frame while the shelf moves (or a new one fades in): its own clock, not a transition.
    var fast by remember { mutableStateOf(false) }
    LaunchedEffect(shelf.mode, shelf.fadeFrom) {
        var last = 0L
        while (true) {
            val t = withFrameNanos { it }
            val dt = if (last == 0L) 16f else ((t - last) / 1e6f).coerceAtMost(50f)
            last = t
            val before = shelf.p
            val more = shelf.frame(dt, System.currentTimeMillis())
            fast = abs(shelf.p - before) / dt * 1000f > 8f && (shelf.mode == "spin" || shelf.mode == "drag")
            if (!more) { fast = false; break }
        }
    }
    BoxWithConstraints(modifier) {
        val w = maxWidth.value; val h = maxHeight.value
        LaunchedEffect(w, h) { shelf.geometry = com.musicd.mallard.client.ShelfGeometry.of(w, h) }
        Box(Modifier.fillMaxSize().parity("shelf.stage", Contract.label("shelf.stage", 0, *said))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { shelf.step(if (e.isShiftPressed) 10 else 1); true }
                    Key.DirectionLeft -> { shelf.step(if (e.isShiftPressed) -10 else -1); true }
                    Key.Enter, Key.DirectionCenter, Key.Spacebar -> { shelf.turnOver(shelf.p.roundToInt()); true }
                    Key.S -> { shelf.spin(1, 2.4f); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(shelf) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTouch()
                    shelf.down(down.position.x / density, System.currentTimeMillis())
                    var at = down.position
                    var ended = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ended = true
                                shelf.up(System.currentTimeMillis(), false) { shelf.tapped(hitAt(hits, at)) }
                                break
                            }
                            if (ch.positionChanged()) { shelf.move(ch.position.x / density, System.currentTimeMillis()); ch.consume() }
                            at = ch.position
                        }
                    } finally { if (!ended) shelf.up(System.currentTimeMillis(), true) {} }
                }
            }) {
            Canvas(Modifier.fillMaxSize().parity("shelf.case")) {
                art.rev; shelf.rev   // drawn again when a cover comes, or the shelf changes
                stageGround(this, shelf, density)
                drawIntoCanvas { c -> drawCases(c.nativeCanvas, shelf, art, faces, hits, density, flip.value, flipV, fast, line) }
            }
        }
    }
}

/** The case under a point: the face it shows there (its cover at the front, a spine at the sides), the nearest the front where two overlap. */
private fun hitAt(hits: List<Pair<Int, RectF>>, at: Offset): Int? = hits.lastOrNull { it.second.contains(at.x, at.y) }?.first

/** The stage's ground: its faint glow, and the ledge the cases stand on with its shadow. */
private fun stageGround(d: DrawScope, shelf: ShelfControl, density: Float) {
    d.ground(shelf, density)
}

private fun DrawScope.ground(shelf: ShelfControl, density: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0x0EECEBE6), Color.Transparent), center = Offset(size.width / 2, size.height * 0.4f), radius = size.maxDimension * 0.55f))
    if (shelf.n == 0) return
    val g = shelf.geometry
    val top = (g.top + g.sh) * density; val ledge = g.s * 0.075f * density
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x2E000000)), startY = top - g.sh * 1.18f * density, endY = top), Offset(0f, top - g.sh * 1.18f * density), androidx.compose.ui.geometry.Size(size.width, g.sh * 1.18f * density))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF34383E), Color(0xFF1B1D21), Color(0xFF1B1D21)), startY = top, endY = top + ledge), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, ledge))
    drawRect(paletteColor(Palette.ACCENT).copy(alpha = 0.4f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, density))
}

/**
 * Every case drawn, farthest first: each a box [s] wide, [sh] tall and [t] deep, turned about its upright and moved as
 * [ShelfMotion.place] says, its faces that look this way drawn, each through the perspective in its own flat coordinates.
 */
private fun drawCases(c: android.graphics.Canvas, shelf: ShelfControl, art: ShelfArt, faces: ShelfFaces, hits: ArrayList<Pair<Int, RectF>>, density: Float,
                      flipDeg: Float, flipV: Int?, fast: Boolean, line: (com.musicd.mallard.client.ShelfCase) -> String) {
    hits.clear()
    val n = shelf.n; if (n == 0) return
    val g = shelf.geometry; val p = shelf.p; val list = shelf.list; val tabs = shelf.tabs
    faces.rem = (g.w * 0.0055f + 8.5f).coerceIn(14f, 20f) * density
    val now = System.currentTimeMillis()
    val fade = if (shelf.fadeFrom == 0L) 1f else ((now - shelf.fadeFrom) / 420f).coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) }
    val ring = if (shelf.landedAt == 0L) 0f else (1f - (now - shelf.landedAt) / 1100f).coerceIn(0f, 1f)
    val k = density
    val cx = g.w / 2 * k; val cy = (g.top + g.sh / 2) * k
    val ox = g.w / 2 * k; val oy = g.eyeY * k; val persp = g.perspective * k
    val s = g.s * k; val sh = g.sh * k; val t = g.t * k
    val order = ShelfMotion.window(g, p, n).map { it to ShelfMotion.place(g, it - p, n) }.sortedBy { ShelfMotion.layer(it.first, p) }
    val m = Matrix(); val src = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f); val dst = FloatArray(8)
    for ((v, pl) in order) {
        val op = pl.op * fade
        if (op < 0.002f) continue
        val i = ShelfMotion.mod(v, n); val a = list[i]
        val (bg, fg) = art.colours(a)
        val ad = abs(v - p)
        val turn = if (v == flipV) flipDeg else 0f
        // a corner of a face, from the box's middle, to the screen: the turn over, the place's turn, its move, the eye
        fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
            val (x1, z1) = rot(x, z, turn)
            val (x2, z2) = rot(x1, z1, pl.ry)
            val px = cx + x2 + pl.x * k; val pz = z2 + pl.z * k; val py = cy + y
            val f = persp / (persp - pz)
            out[at] = ox + (px - ox) * f; out[at + 1] = oy + (py - oy) * f
        }
        fun face(w: Float, h: Float, corners: Array<FloatArray>, paint: (android.graphics.Canvas) -> Unit): RectF? {
            for (j in 0 until 4) project(corners[j][0], corners[j][1], corners[j][2], dst, j * 2)
            if (area(dst) <= 0f) return null
            src[2] = w; src[4] = w; src[5] = h; src[7] = h
            m.setPolyToPoly(src, 0, dst, 0, 4)
            val save = if (op < 0.999f) c.saveLayerAlpha(null, (op * 255).toInt()) else c.save()
            c.concat(m); paint(c)
            c.restoreToCount(save)
            return RectF(minOf(dst[0], dst[2], dst[4], dst[6]), minOf(dst[1], dst[3], dst[5], dst[7]), maxOf(dst[0], dst[2], dst[4], dst[6]), maxOf(dst[1], dst[3], dst[5], dst[7]))
        }
        val hs = s / 2; val hh = sh / 2; val ht = t / 2
        val tab = tabs.getOrElse(i) { "" }; val up = i > 0 && tabs.getOrElse(i - 1) { "" }.isNotEmpty()
        val spine: (android.graphics.Canvas) -> Unit = { cv -> faces.spine(cv, a, t, sh, bg, fg, tab, up) }
        val left = face(t, sh, arrayOf(floatArrayOf(-hs, -hh, -ht), floatArrayOf(-hs, -hh, ht), floatArrayOf(-hs, hh, ht), floatArrayOf(-hs, hh, -ht)), spine)
        val right = face(t, sh, arrayOf(floatArrayOf(hs, -hh, ht), floatArrayOf(hs, -hh, -ht), floatArrayOf(hs, hh, -ht), floatArrayOf(hs, hh, ht)), spine)
        // the front, not painted once the case stands edge-on; the back while it is turned over
        val front = if (ad >= 1.2f) null else face(s, sh, arrayOf(floatArrayOf(-hs, -hh, ht), floatArrayOf(hs, -hh, ht), floatArrayOf(hs, hh, ht), floatArrayOf(-hs, hh, ht))) { cv ->
            faces.front(cv, a, s, sh, bg, art.cover(a, fast), if (ad < 0.5f) ring else 0f)
        }
        if (turn > 0f) face(s, sh, arrayOf(floatArrayOf(hs, -hh, -ht), floatArrayOf(-hs, -hh, -ht), floatArrayOf(-hs, hh, -ht), floatArrayOf(hs, hh, -ht))) { cv -> faces.back(cv, a, line(a), s, sh, bg) }
        (if (ad < 0.5f) front else if (v < p) left else right)?.let { hits += v to it }
    }
}

/** A turn of [deg] degrees about the upright, as CSS rotateY turns a point: x cos + z sin, −x sin + z cos. */
private fun rot(x: Float, z: Float, deg: Float): Pair<Float, Float> {
    if (deg == 0f) return x to z
    val r = Math.toRadians(deg.toDouble()); val cs = kotlin.math.cos(r).toFloat(); val sn = kotlin.math.sin(r).toFloat()
    return (x * cs + z * sn) to (-x * sn + z * cs)
}

/** A projected quad's signed area: positive where its face looks this way (its corners clockwise on the screen). */
private fun area(q: FloatArray): Float {
    var sum = 0f
    for (j in 0 until 4) { val a = j * 2; val b = ((j + 1) % 4) * 2; sum += q[a] * q[b + 1] - q[b] * q[a + 1] }
    return sum / 2
}
