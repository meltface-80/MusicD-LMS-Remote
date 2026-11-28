package com.musicd.mallard.client

/**
 * The gain the USB sink puts on PCM (the app's UsbDriver.softwareGain; Mandarin's, without its DSD). 1 is bit-perfect:
 * nothing touched. A DAC with its own volume control and fixed volume are never scaled; otherwise the slider's loudness
 * curve, (level/100)³, and 0 muted.
 */
object UsbGain {
    fun software(volumeControl: Boolean, fixed: Boolean, muted: Boolean, level: Int): Float {
        if (volumeControl || fixed) return 1f
        if (muted) return 0f
        val l = level / 100.0
        return (l * l * l).toFloat()
    }
}
