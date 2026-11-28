package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** The USB sink's software gain: the slider's curve on a DAC with no volume of its own, nothing where it has one or the volume is fixed. */
class UsbGainTest {
    @Test
    fun the_slider_scales_a_DAC_without_its_own_volume() {
        assertEquals(0.125f, UsbGain.software(volumeControl = false, fixed = false, muted = false, level = 50), 1e-6f)
        assertEquals(1f, UsbGain.software(volumeControl = false, fixed = false, muted = false, level = 100), 0f)
        assertEquals(0f, UsbGain.software(volumeControl = false, fixed = false, muted = true, level = 50), 0f)
    }

    @Test
    fun bit_perfect_where_the_DAC_has_its_own_volume_or_it_is_fixed() {
        for (level in listOf(0, 10, 50)) {
            assertEquals(1f, UsbGain.software(volumeControl = true, fixed = false, muted = false, level = level), 0f)
            assertEquals(1f, UsbGain.software(volumeControl = false, fixed = true, muted = false, level = level), 0f)
        }
    }
}
