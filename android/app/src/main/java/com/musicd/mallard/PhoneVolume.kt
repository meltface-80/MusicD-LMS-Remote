package com.musicd.mallard

import android.content.Context
import android.media.AudioManager
import com.musicd.mallard.client.LocalVolume

/**
 * This phone's own volume, for its own player (Mandarin's rule, owner request: the phone's player is fixed at 100% in
 * LMS and "volume changes take place with the phone's volume control"). Android's media volume; through a USB DAC under
 * USB direct, the DAC's level (UsbDriver) — the DAC's own hardware control where it has one, so Untouched stays
 * bit-perfect. A DAC Mallard can't set (fixed volume, or Untouched to a DAC with no control of its own) reads as fixed.
 * Installed as ThisPhone.volume, so every volume call ServerClient makes for this phone's player comes here.
 */
internal class PhoneVolume(context: Context) : LocalVolume {
    private val app = context.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val own by lazy { PhoneZone.ownId(app) }
    private val max get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** The DAC is what plays and Mallard drives its level. */
    private fun dac() = UsbDriver.active
    private fun dacLevel() = UsbDriver.controlsVolume && (UsbDriver.hasVolume || PhoneZone.mode != "exact")

    /** This phone's own player, running or about to (chosen at home a moment ago). */
    override fun owns(zoneId: String): Boolean = zoneId == PhoneZone.id || zoneId == own

    override fun level(): Int? =
        if (dac()) (if (dacLevel()) UsbDriver.volume() else null)
        else Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt()

    override fun muted(): Boolean = if (dac()) UsbDriver.isMuted() else am.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun set(value: Int) {
        if (dac()) { if (dacLevel()) UsbDriver.setVolume(value); return }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value.coerceIn(0, 100) * max / 100.0).toInt(), 0)
    }

    override fun step(direction: Int) {
        if (dac()) { if (dacLevel()) UsbDriver.stepVolume(direction > 0); return }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    override fun mute(on: Boolean) {
        if (dac()) { UsbDriver.setMute(on); return }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }

    companion object {
        /**
         * The media session for zone [id]: on this phone's own player (not through a USB DAC, whose level the room-style
         * provider hands to ThisPhone), it plays "locally", so the buttons and the lock screen move Android's media
         * volume. Switched over when it [was] not; true while local.
         */
        fun session(s: android.media.session.MediaSession, id: String, was: Boolean): Boolean {
            if (id != PhoneZone.id || UsbDriver.active) return false
            if (!was) s.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            return true
        }
    }

    /** The phone's volume buttons on this phone's player: the DAC's level where Mallard drives it (true), else Android's own (false). */
    fun key(up: Boolean): Boolean {
        if (dac() && dacLevel()) { UsbDriver.stepVolume(up); return true }
        return false
    }
}
