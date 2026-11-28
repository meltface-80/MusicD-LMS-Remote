package com.musicd.mallard

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.musicd.mallard.client.PcmWords
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio sink that plays through the app's USB driver (Mandarin's, Stage 9.2, PCM only).
 *
 * Sits under [WordsSink], so what arrives is float or, for a DAC, left-justified 32-bit words (PcmWords). With USB direct
 * on and a DAC on the port that takes the stream's rate, the stream is opened on the DAC and every buffer is packed to its
 * depth and written to the driver; the position comes from the frames the DAC has taken. Otherwise — switch off, no DAC, a
 * rate it doesn't take, the DAC gone — the buffers go to Android's track as before.
 *
 * Where the stream goes on at another rate the DAC is opened again at the new one and given half a second of silence
 * first, so it settles on nothing and the music's first moment is heard whole. The position leaves the silence out.
 */
@UnstableApi
class UsbAudioSink(private val inner: AudioSink, private val context: Context) : ForwardingAudioSink(inner) {

    @Volatile var usb = false
        private set
    /** ExoPlayer says play() once, when the renderer starts, not again when the sink is configured: a stream opened later is started here. */
    private var playing = false
    private var rate = 0
    private var channels = 0
    private var subslot = 0
    private var startUs = 0L
    private var startSet = false
    private var playedAtStart = 0L
    private var drained = false
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var outFor: ByteBuffer? = null
    private var outPos = 0
    private var words = false                // PCM carried as 32-bit words (WordsSink), not as float
    private var settle: ByteBuffer? = null   // the silence still to go ahead of the music, at a change of rate
    private var afterSilence = 0L            // the frames the DAC has taken once that silence is played

    private fun wantUsb(f: Format): Boolean =
        Store.usbDirect(context) && UsbDac.device() != null && f.sampleMimeType == MimeTypes.AUDIO_RAW &&
            (f.pcmEncoding == C.ENCODING_PCM_FLOAT || f.pcmEncoding == C.ENCODING_PCM_32BIT) &&
            f.channelCount == 2 && UsbDriver.streamFor(UsbDac.info(), f.sampleRate) != null

    /** Would this PCM go to the DAC? Then WordsSink carries it as 32-bit words, so a 32-bit sample reaches a 32-bit slot as it is (float holds 24 bits). */
    fun takesWords(f: Format): Boolean = wantUsb(f.buildUpon().setPcmEncoding(C.ENCODING_PCM_32BIT).build())

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (wantUsb(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else inner.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (wantUsb(inputFormat) && keeps(inputFormat.sampleRate)) {
            // Going on at the rate playing: its stream kept, as Android's own sink keeps its track.
            words = inputFormat.pcmEncoding == C.ENCODING_PCM_32BIT
            drained = false
            return
        }
        val change = usb
        if (wantUsb(inputFormat) && UsbDriver.open(context, inputFormat.sampleRate)) {
            usb = true; words = inputFormat.pcmEncoding == C.ENCODING_PCM_32BIT
            rate = inputFormat.sampleRate; channels = inputFormat.channelCount
            subslot = UsbDriver.current?.subslot ?: 3
            startSet = false; drained = false; outFor = null
            if (change) settleWith(ByteArray(channels * subslot))
            if (playing) UsbDriver.play()
            AppLog.line("USB", "playing through the DAC: " + (UsbDriver.describe() ?: "$rate Hz"))
            return   // Android's track is left unconfigured: nothing goes to it.
        }
        if (usb) { UsbDriver.close(); usb = false }
        inner.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    /** Whether the stream open on the DAC is the one [rate] would open: the same DAC, its rate, alive. */
    private fun keeps(rate: Int): Boolean {
        val c = UsbDriver.current ?: return false
        return usb && c.rate == rate && c.device == UsbDac.device() && !UsbDriver.dead()
    }

    /** Half a second of [frame] (one frame of silence) to go to the DAC, just opened at a new rate, ahead of the music. */
    private fun settleWith(frame: ByteArray) {
        val count = rate / 2
        val b = ByteBuffer.allocateDirect(count * frame.size)
        while (b.remaining() >= frame.size) b.put(frame)
        b.flip()
        settle = b
        afterSilence = UsbDriver.played() + count
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!usb) return inner.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (UsbDriver.dead() || !UsbDriver.active) throw AudioSink.WriteException(-1, Format.Builder().setSampleRate(rate).build(), true)
        settle?.let { sil ->
            val n = UsbDriver.write(sil, sil.position(), sil.remaining())
            if (n < 0) throw AudioSink.WriteException(n, Format.Builder().setSampleRate(rate).build(), true)
            sil.position(sil.position() + n)
            if (sil.hasRemaining()) return false
            settle = null
        }
        if (!startSet) { startUs = presentationTimeUs; playedAtStart = maxOf(UsbDriver.played(), afterSilence); startSet = true; if (playing) UsbDriver.play() }
        // The same buffer comes back until it is taken: packed once, written as the ring has room.
        if (outFor !== buffer) { pack(buffer); outFor = buffer; outPos = 0 }
        val n = UsbDriver.write(out, outPos, out.limit() - outPos)
        if (n < 0) throw AudioSink.WriteException(n, Format.Builder().setSampleRate(rate).build(), true)
        outPos += n
        if (outPos >= out.limit()) { buffer.position(buffer.limit()); outFor = null; return true }
        return false
    }

    /** The samples packed to the DAC's subslot, little-endian, left-justified (PcmWords): words as they come, float × 2³¹. */
    private fun pack(buffer: ByteBuffer) {
        val samples = buffer.remaining() / 4
        val need = samples * subslot
        if (out.capacity() < need) out = ByteBuffer.allocateDirect(need).order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        val src = buffer.duplicate().order(if (words) ByteOrder.LITTLE_ENDIAN else ByteOrder.nativeOrder())
        // 1 (a DAC with its own volume control, or fixed volume) leaves the samples untouched — and always Untouched.
        val gain = if (PhoneZone.mode == "exact") 1.0 else UsbDriver.softwareGain().toDouble()
        for (i in 0 until samples) {
            val w = if (words) PcmWords.scaled(src.getInt(), gain) else PcmWords.of(src.getFloat().toDouble() * gain)
            PcmWords.put(out, w, subslot)
        }
        out.flip()
    }

    private fun stopUsb() { UsbDriver.flush(); startSet = false; drained = false; outFor = null; settle = null; afterSilence = 0 }

    override fun play() { playing = true; if (usb) UsbDriver.play() else inner.play() }
    override fun pause() { playing = false; if (usb) UsbDriver.pause() else inner.pause() }
    override fun handleDiscontinuity() { if (usb) startSet = false else inner.handleDiscontinuity() }
    override fun playToEndOfStream() { if (usb) { UsbDriver.drain(); drained = true } else inner.playToEndOfStream() }
    override fun isEnded(): Boolean = if (usb) drained && UsbDriver.pending() == 0L else inner.isEnded()
    override fun hasPendingData(): Boolean = if (usb) UsbDriver.pending() > 0 else inner.hasPendingData()
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usb) return inner.getCurrentPositionUs(sourceEnded)
        if (!startSet || rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = (UsbDriver.played() - playedAtStart).coerceAtLeast(0)
        return startUs + played * 1_000_000L / rate
    }
    override fun setVolume(volume: Float) { if (!usb) inner.setVolume(volume) }
    override fun flush() { if (usb) stopUsb() else inner.flush() }
    override fun reset() { if (usb) stopUsb() else inner.reset() }
    override fun release() { if (usb) { UsbDriver.close(); usb = false }; inner.release() }
}
