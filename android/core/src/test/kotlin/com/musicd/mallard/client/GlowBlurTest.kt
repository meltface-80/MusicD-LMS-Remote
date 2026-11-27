package com.musicd.mallard.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The album page's glow, made once (item 471): GlowBlur's blur and the size it is made at. */
class GlowBlurTest {
    private fun px(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 255

    @Test fun `a bright pixel spreads alike every way, across and down, the picture's light kept`() {
        val w = 21; val h = 21
        val src = IntArray(w * h) { px(255, 0, 0, 0) }
        src[10 * w + 10] = px(255, 255, 255, 255)
        val out = GlowBlur.blur(src, w, h, radius = 2, passes = 3)
        val at = { x: Int, y: Int -> red(out[y * w + x]) }
        // The same each way from the middle, across and down alike.
        for (d in 1..4) {
            assertEquals("left and right $d", at(10 - d, 10), at(10 + d, 10))
            assertEquals("up and down $d", at(10, 10 - d), at(10, 10 + d))
            assertTrue("across and down $d, within a step of rounding (each pass rounds)", Math.abs(at(10 + d, 10) - at(10, 10 + d)) <= 1)
        }
        assertTrue("spread: the middle dimmed, its neighbours lit", at(10, 10) in 1..254 && at(11, 10) > 0 && at(10, 11) > 0)
        assertTrue("falling away from the middle", at(10, 10) >= at(11, 10) && at(11, 10) >= at(13, 10))
        val total = out.sumOf { red(it) }
        assertTrue("the light kept, near enough (rounding): $total", total in 200..310)
    }

    @Test fun `alpha blurred too, a flat picture left flat, radius 0 left as it was, the edges held`() {
        val flat = IntArray(6 * 4) { px(200, 10, 20, 30) }
        assertArrayEquals(flat, GlowBlur.blur(flat, 6, 4))
        val some = IntArray(5 * 3) { px(it * 10, it, 2 * it, 3 * it) }
        assertArrayEquals(some, GlowBlur.blur(some, 5, 3, radius = 0))
        val half = IntArray(10) { if (it < 5) px(0, 0, 0, 0) else px(255, 255, 255, 255) }
        val out = GlowBlur.blur(half, 10, 1, radius = 1, passes = 1)
        assertEquals("the edge's own past it", px(0, 0, 0, 0), out[0])
        assertEquals(px(255, 255, 255, 255), out[9])
        assertTrue("alpha softened at the step", (out[4] ushr 24) in 1..254)
    }

    @Test fun `made 48 wide, its height in proportion, one pixel at least`() {
        assertEquals(48, GlowBlur.heightFor(1000, 1000))
        assertEquals(24, GlowBlur.heightFor(1000, 500))
        assertEquals(1, GlowBlur.heightFor(1000, 1))
        assertEquals(1, GlowBlur.heightFor(0, 0))
    }
}
