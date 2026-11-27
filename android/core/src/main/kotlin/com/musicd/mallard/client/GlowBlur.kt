package com.musicd.mallard.client

/**
 * The album page's glow made once (item 471): the cover made small and blurred here, in the background, then drawn
 * stretched and faint. It was the cover drawn the page's width and 420 dp tall under a live blur (Android 12's
 * RenderEffect), which Android works out again on every frame the screen draws: while an album played, its page drew
 * every frame (the playing track's progress), and the phone blurred a screen-sized picture sixty times a second (629
 * slow frames in half a minute on the album playing, the owner's log; 2 on one that wasn't). A small picture blurred
 * once looks the same.
 */
object GlowBlur {
    /** How wide the cover is made before it is blurred: 48 pixels, its height in proportion. */
    const val WIDTH = 48

    /** How far each pass reaches each way, in those pixels: three passes of 4 come near the 40 dp blur it was. */
    const val RADIUS = 4

    /** The height a cover [w] × [h] is made at, [WIDTH] wide: one pixel at least. */
    fun heightFor(w: Int, h: Int): Int = if (w <= 0 || h <= 0) 1 else maxOf(1, Math.round(WIDTH.toFloat() * h / w))

    /**
     * [argb] ([w] × [h], a pixel an Int as Android's Bitmap gives them) blurred: a box [radius] each way, across then
     * down, [passes] times over (near a Gaussian), each channel alpha included, the edges held (a pixel past the edge
     * is the edge's own). [radius] 0 gives it back as it was.
     */
    fun blur(argb: IntArray, w: Int, h: Int, radius: Int = RADIUS, passes: Int = 3): IntArray {
        require(w > 0 && h > 0 && argb.size == w * h) { "$w × $h is not ${argb.size} pixels" }
        val a = argb.copyOf()
        if (radius <= 0) return a
        val b = IntArray(a.size)
        repeat(passes) {
            box(a, b, w, h, radius, across = true)
            box(b, a, w, h, radius, across = false)
        }
        return a
    }

    // One box pass, across each row or down each column, from [src] into [dst].
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, across: Boolean) {
        val lines = if (across) h else w
        val len = if (across) w else h
        val n = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int) = if (across) l * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + l
            for (i in 0 until len) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) {
                    val p = src[at(i + k)]
                    sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                }
                dst[at(i)] = ((sa + n / 2) / n shl 24) or ((sr + n / 2) / n shl 16) or ((sg + n / 2) / n shl 8) or ((sb + n / 2) / n)
            }
        }
    }
}
