package com.musicd.mallard.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A USB DAC is given every sample bit for bit (PcmWords): through float and back, and into each slot width. */
class PcmWordsTest {
    private val edges16 = intArrayOf(0, 1, -1, 2, 100, -100, 16383, 16384, -16384, 32766, 32767, -32767, -32768)
    private val edges24 = intArrayOf(0, 1, -1, 255, 256, -256, 4194303, 4194304, 8388606, 8388607, -8388607, -8388608)

    /** As DspSink hands the USB sink a decoder's sample when it must go as float: s / 2ⁿ⁻¹, then float. */
    private fun asFloat(s: Int, bits: Int): Double = (s / Math.pow(2.0, (bits - 1).toDouble())).toFloat().toDouble()

    private fun slot(w: Int, bytes: Int): ByteArray {
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        PcmWords.put(b, w, bytes)
        return b.array()
    }

    private fun le(v: Long, bytes: Int) = ByteArray(bytes) { ((v shr (8 * it)) and 0xff).toByte() }

    @Test fun `a 16- or 24-bit sample through float comes back to its own word`() {
        for (s in edges16) assertEquals("16-bit $s", PcmWords.of16(s), PcmWords.of(asFloat(s, 16)))
        for (s in edges24) assertEquals("24-bit $s", PcmWords.of24(s), PcmWords.of(asFloat(s, 24)))
    }

    @Test fun `a sample no deeper than the slot reaches the DAC padded with zeros, bit for bit`() {
        for (s in edges24) {
            assertArrayEquals("24-bit $s in a 32-bit slot", le(s.toLong() * 256, 4), slot(PcmWords.of24(s), 4))
            assertArrayEquals("24-bit $s in a 24-bit slot", le(s.toLong(), 3), slot(PcmWords.of24(s), 3))
        }
        for (s in edges16) {
            assertArrayEquals("16-bit $s in a 32-bit slot", le(s.toLong() * 65536, 4), slot(PcmWords.of16(s), 4))
            assertArrayEquals("16-bit $s in a 24-bit slot", le(s.toLong() * 256, 3), slot(PcmWords.of16(s), 3))
            assertArrayEquals("16-bit $s in a 16-bit slot", le(s.toLong(), 2), slot(PcmWords.of16(s), 2))
        }
    }

    @Test fun `a 32-bit sample carried as a word reaches a 32-bit slot as it is`() {
        for (w in intArrayOf(0, 1, -1, 123456789, -123456789, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertArrayEquals("$w", le(w.toLong(), 4), slot(w, 4))
            assertEquals("untouched at unity gain", w, PcmWords.scaled(w, 1.0))
        }
    }

    @Test fun `a deeper sample loses its lowest bits in a narrower slot, and full scale is held`() {
        assertArrayEquals(le(0x123456, 3), slot(0x12345678, 3))
        assertArrayEquals(le(0x1234, 2), slot(0x12345678, 2))
        assertEquals(Int.MAX_VALUE, PcmWords.of(1.0))
        assertEquals(Int.MAX_VALUE, PcmWords.of(7.5))
        assertEquals(Int.MIN_VALUE, PcmWords.of(-1.0))
        assertEquals(0, PcmWords.of(Double.NaN))
        assertEquals(0, PcmWords.of8(0x80))
        assertEquals(PcmWords.of16(-32768), PcmWords.of8(0))
    }
}
