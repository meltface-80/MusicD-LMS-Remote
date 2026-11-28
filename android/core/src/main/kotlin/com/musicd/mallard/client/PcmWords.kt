package com.musicd.mallard.client

import java.nio.ByteBuffer

/**
 * Samples as a USB DAC is given them, exact (the app's USB driver: DspSink, UsbAudioSink). Every sample is carried
 * as a left-justified signed 32-bit word — a 16-bit sample in its top 16 bits, a 24-bit one in its top 24, a 32-bit
 * one as it is — and cut to the DAC's slot by taking its top bytes. So a sample no deeper than the slot reaches the
 * DAC bit for bit, padded with zeros (16-bit in a 32-bit slot: s × 65536), the same words SQ-tool's fingerprint
 * reads; a deeper one loses its lowest bits, undithered.
 *
 * A value in [-1, 1) is a word × 2⁻³¹, both ways: the decoders' 16- and 24-bit samples are exact in float, and come
 * back to the same word. (The scale was 2³¹ − 1 one way and 2³¹ the other: every 24-bit sample reached a 32-bit
 * slot one below its own word, and a 16-bit slot's loudest samples one below.)
 */
object PcmWords {
    private const val FULL = 2147483648.0   // 2³¹

    fun of16(s: Int): Int = s shl 16
    /** A 24-bit sample, its three bytes as an unsigned value or sign-extended: its word. */
    fun of24(raw: Int): Int = raw shl 8
    fun of8(unsigned: Int): Int = ((unsigned and 0xff) - 128) shl 24

    /** A value in [-1, 1) as a word: × 2³¹, to the nearest, held to the word's range (1.0 and over: the largest). */
    fun of(v: Double): Int {
        if (v.isNaN()) return 0
        val x = Math.round(v * FULL)
        return if (x > Int.MAX_VALUE) Int.MAX_VALUE else if (x < Int.MIN_VALUE) Int.MIN_VALUE else x.toInt()
    }

    /** A word as a value in [-1, 1). */
    fun value(w: Int): Double = w / FULL

    /** [w] scaled by [gain] (1: untouched; the software volume of a DAC with none of its own). */
    fun scaled(w: Int, gain: Double): Int = if (gain == 1.0) w else of(value(w) * gain)

    /** The word into [out] as the DAC's slot of [bytes] (2, 3 or 4) takes it: its top bytes, little-endian. */
    fun put(out: ByteBuffer, w: Int, bytes: Int) {
        when (bytes) {
            2 -> { val x = w shr 16; out.put((x and 0xff).toByte()); out.put(((x shr 8) and 0xff).toByte()) }
            3 -> { out.put(((w shr 8) and 0xff).toByte()); out.put(((w shr 16) and 0xff).toByte()); out.put(((w shr 24) and 0xff).toByte()) }
            else -> { out.put((w and 0xff).toByte()); out.put(((w shr 8) and 0xff).toByte()); out.put(((w shr 16) and 0xff).toByte()); out.put(((w shr 24) and 0xff).toByte()) }
        }
    }
}
