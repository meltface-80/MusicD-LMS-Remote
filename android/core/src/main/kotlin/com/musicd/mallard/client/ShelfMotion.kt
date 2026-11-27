package com.musicd.mallard.client

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * How the Shelf stands and moves natively (bug list item 207), as the page's shelf.js computes it frame by frame: the
 * stage's measures (layout), where each case stands at a distance from the front (place), which cases are drawn, a
 * critically damped spring to rest on an album, a three-second spin that lands somewhere, and what a finger's swipe,
 * flick or hold does. In the page's own units (CSS pixels, the app's dp), so the two shelves move alike.
 */

/** The stage's measures for a width and height: the cover's size [s], the cases' height [sh], a spine's width [t], the top they stand at, a swipe's step, how many are drawn each side. */
data class ShelfGeometry(val w: Float, val h: Float, val s: Float, val sh: Float, val t: Float, val top: Float, val stepPx: Float, val k: Int) {
    /** A stage taller than the cover is wide: the case in front a tall card, its cover over its names. */
    val tall: Boolean get() = sh > s
    /** The rig's perspective, and the height its eye is at. */
    val perspective: Float get() = (s * 3.2f).roundToInt().toFloat()
    val eyeY: Float get() = (top + sh * 0.58f).roundToInt().toFloat()
    /** A swipe of this far moves one album (v1.9.10: 1.4 a cover's step, less quick to move). */
    val swipe: Float get() = stepPx * ShelfMotion.SWIPE

    companion object {
        /**
         * shelf.js layout for a stage [w] by [h] with the pills over it reaching [clear] down: the stage less the pills' strip, a
         * divider card, the ledge and the 3% the front case gains drawn forward; the cover up to 55% of the width (52% if
         * taller); a stage taller than the cover is wide gives the cases its whole height, the case in front a tall card.
         */
        fun of(w: Float, h: Float, clear: Float = 0f): ShelfGeometry {
            var tabH = max(26f, h * 0.045f)
            var s = 0f; var sh = 0f; var t = 0f
            repeat(2) {
                val room = (h - clear - tabH - max(14f, h * 0.024f)) / 1.03f
                s = max(110f, min(room, w * if (h > w) 0.52f else 0.55f)).roundToInt().toFloat()
                sh = if (room - s > s * 0.18f) room.roundToInt().toFloat() else s
                t = max(16f, min(sh * 0.072f, w * 0.11f).roundToInt().toFloat())
                tabH = max(tabH, t * 1.5f)
            }
            val top = max(clear + tabH, (h - sh * 1.02f) * 0.55f).roundToInt().toFloat()
            val k = min(40, ceil((w / 2 - s / 2) / (t * 1.08f)).toInt() + 2)
            return ShelfGeometry(w, h, s, sh, t, top, max(52f, s * 0.24f), k)
        }
    }
}

/** Where a case stands, [d] albums from the front: across ([x]), toward the eye ([z]), its turn about the upright ([ry], degrees) and how solid ([op]). */
data class ShelfPlace(val x: Float, val z: Float, val ry: Float, val op: Float)

/** What a finger lifted from the shelf asks: to come to rest on [settle], or a spin [spinDir] of [spinStrength]. */
data class ShelfRelease(val settle: Float? = null, val spinDir: Int = 0, val spinStrength: Float = 0f, val velocity: Float = 0f)

object ShelfMotion {
    /** A finger's swipe for one album, against a cover's step. */
    const val SWIPE = 1.4f
    /** The spring's angular frequency, per millisecond (a 420 ms period, critically damped). */
    private const val OMEGA = (2 * Math.PI / 420).toFloat()
    /** A spin's length on a shelf that revolves; a short one's, that stands in a row. */
    const val SPIN_MS = 3000L
    const val SPIN_SHORT_MS = 900L

    /** More than three albums revolve; three or fewer stand in a row. */
    fun wraps(n: Int): Boolean = n > 3

    /** The case [d] albums from the front: it slides out a little before it turns, then stands edge-on in a row of spines. */
    fun place(g: ShelfGeometry, d: Float, n: Int): ShelfPlace {
        val ad = abs(d); val s = if (d < 0) -1f else 1f; val a = min(1f, ad)
        val gap = max(2f, g.t * 0.08f)
        val first = g.s / 2 + g.t / 2 + gap * 2
        val ea = 1 - (1 - a) * (1 - a)
        val x = s * (ea * first + max(0f, ad - 1) * (g.t + gap))
        val z = (g.s * 0.1f - g.t / 2) * (1 - a) - (g.s / 2) * a
        val ry = -s * 90f * a.pow(1.35f)
        return ShelfPlace(x, z, ry, edgeFade(g, ad, n))
    }

    /** Faded out toward the ends of what is drawn, and where a short revolving shelf's loop closes. */
    fun edgeFade(g: ShelfGeometry, ad: Float, n: Int): Float {
        var op = ((g.k + 0.5f - ad) / 1.2f).coerceIn(0f, 1f)
        if (wraps(n) && n < 2 * g.k + 2) op = min(op, ((n / 2f - ad) / 0.6f).coerceIn(0f, 1f))
        return op
    }

    /** The places drawn around [p]: a window on a shelf that revolves (each album once), the whole row on a short one. */
    fun window(g: ShelfGeometry, p: Float, n: Int): IntRange {
        if (n == 0) return IntRange.EMPTY
        if (!wraps(n)) return 0 until n
        val span = min(n, 2 * g.k + 2)
        val start = kotlin.math.floor(p - span / 2f).toInt() + 1
        return start until start + span
    }

    /** The order cases are drawn in: the nearer the front, the later (on top); of two as near, the one on the left. */
    fun layer(v: Int, p: Float): Int = -2 * (abs(v - p) * 100).roundToInt() - if (v > p) 1 else 0

    /** [a] modulo [n], never negative: the album at place [a] of a shelf that revolves. */
    fun mod(a: Int, n: Int): Int = ((a % n) + n) % n

    /** The spring stepped [dtMs] on: the new place and speed, and whether it has come to rest on [target]. */
    fun spring(p: Float, v: Float, target: Float, dtMs: Float): Triple<Float, Float, Boolean> {
        var pp = p; var vv = v; var left = dtMs
        while (left > 0) {
            val h = min(8f, left); left -= h
            val acc = -OMEGA * OMEGA * (pp - target) - 2 * OMEGA * vv
            vv += acc * h; pp += vv * h
        }
        if (abs(pp - target) < 6e-4f && abs(vv) < 4e-5f) return Triple(target, 0f, true)
        return Triple(pp, vv, false)
    }

    /** Where a spin lands from [p]: a short shelf on another album at random; a long one 26 to 80 albums on, plus up to 40, the way flicked. */
    fun spinBy(p: Float, n: Int, dir: Int, strength: Float, random: () -> Double): Float {
        if (!wraps(n)) {
            var t = (random() * n).toInt()
            if (t == p.roundToInt()) t = (t + 1) % n
            return t - p
        }
        val base = (strength * 24).coerceIn(26f, 80f)
        val d = dir * (base.roundToInt() + (random() * min(n, 40)).toInt())
        return (p + d).roundToInt() - p
    }

    /** A spin [u] of the way through (0 to 1): a cubic easing out, from [p0] by [by]. */
    fun spun(p0: Float, by: Float, u: Float): Float = p0 + by * (1 - (1 - u.coerceIn(0f, 1f)).pow(3))

    /** A short shelf pulled past its ends gives a little, and springs back. */
    fun rubber(x: Float, n: Int): Float = when { x < 0 -> x * 0.3f; x > n - 1 -> n - 1 + (x - (n - 1)) * 0.3f; else -> x }

    /** Where a finger [dx] along from [p0] holds the shelf, with [acc] albums a held swipe has turned it on. */
    fun dragged(g: ShelfGeometry, p0: Float, dx: Float, acc: Float, n: Int): Float {
        val raw = p0 - dx / g.swipe + acc
        return if (wraps(n)) raw else rubber(raw, n)
    }

    /** A swipe held out and still: it keeps turning, faster the further out; albums a second (0 within reach of where it started). */
    fun shuttleRate(g: ShelfGeometry, dx: Float): Float {
        val lim = max(40f, g.w * 0.08f)
        if (abs(dx) <= lim) return 0f
        return -sign(dx) * (3 + 15 * ((abs(dx) - lim) / (g.w * 0.35f)).coerceIn(0f, 1f).pow(1.3f))
    }

    /** A swipe is held out far enough to keep turning once still for 200 ms. */
    fun far(g: ShelfGeometry, dx: Float): Boolean = abs(dx) > max(80f, g.w * 0.2f)

    /**
     * A finger lifted after moving: [dx] from where it went down [msDown] ago, its speed [v] (px per ms over the last 100 ms),
     * from place [p0], the shelf now at [p]: a hard flick spins it; a short swipe moves one album; a longer one carries on.
     */
    fun release(g: ShelfGeometry, p0: Float, p: Float, dx: Float, v: Float, msDown: Long): ShelfRelease {
        if (abs(v) >= 1.7f && abs(dx) >= max(140f, g.w * 0.16f) && msDown < 700) return ShelfRelease(spinDir = if (v < 0) 1 else -1, spinStrength = abs(v))
        val vp = -v / g.swipe
        if (abs(dx) < (g.w * 0.16f).coerceIn(80f, 240f)) return ShelfRelease(settle = (p0.roundToInt() + if (dx < 0) 1 else -1).toFloat(), velocity = vp)
        return ShelfRelease(settle = (p + vp * 240).roundToInt().toFloat(), velocity = vp)
    }

    /** The place to rest on: any on a shelf that revolves, within its ends on a short one. */
    fun settleTarget(t: Float, n: Int): Float = if (wraps(n)) t else t.coerceIn(0f, (n - 1).toFloat())

    /** The nearest way round from [here] to album [i] of a revolving shelf of [n]. */
    fun nearest(here: Int, i: Int, n: Int): Int {
        if (!wraps(n)) return i
        val d = (((i - here + n / 2.0) % n) + n) % n - n / 2.0
        return here + Math.round(d).toInt()
    }
}
