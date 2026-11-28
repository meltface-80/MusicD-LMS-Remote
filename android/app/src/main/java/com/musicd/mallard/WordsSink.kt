package com.musicd.mallard

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
 * Every decoded buffer in one carrier for the sink under it (Mandarin's DspSink, without the DSP): float for Android's
 * track, or — when the USB sink would send it to a DAC (UsbAudioSink.takesWords) — each sample's own left-justified
 * 32-bit word (PcmWords), so a 16- or 24-bit sample reaches the DAC bit for bit, padded with zeros.
 *
 * The sink is wrapped rather than an AudioProcessor added, because Media3's DefaultAudioSink runs its processors only on
 * 16-bit input, and a decoder's 24-bit or float output would bypass them.
 *
 * LMS's ReplayGain for the track (TrackGain) is applied here, as Mandarin's DspSink applies it: on the sample's word, in
 * double. At 1 (no gain) nothing is touched, so a USB DAC still gets each sample bit for bit.
 */
@UnstableApi
class WordsSink(private val inner: AudioSink) : ForwardingAudioSink(inner) {
    private var pcm = false
    private var encoding = C.ENCODING_INVALID
    private var words = false
    private var pending: ByteBuffer? = null
    private var pendingFor: ByteBuffer? = null
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    private fun linearPcm(f: Format) = f.sampleMimeType == MimeTypes.AUDIO_RAW && f.pcmEncoding in PCM
    private fun asFloat(f: Format) = f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
    private fun asWords(f: Format) = f.buildUpon().setPcmEncoding(C.ENCODING_PCM_32BIT).build()
    private fun takesWords(f: Format) = (inner as? UsbAudioSink)?.takesWords(f) == true
    private fun carrier(f: Format) = if (takesWords(f)) asWords(f) else asFloat(f)

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (linearPcm(format)) inner.getFormatSupport(carrier(format)) else inner.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcm = linearPcm(inputFormat)
        pending = null; pendingFor = null
        if (!pcm) { inner.configure(inputFormat, specifiedBufferSize, outputChannels); return }
        encoding = inputFormat.pcmEncoding
        words = takesWords(inputFormat)
        inner.configure(carrier(inputFormat), specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!pcm) return inner.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // The same input buffer comes back until it is taken: its converted form is kept and offered again.
        if (pendingFor !== buffer || pending == null) { pending = convert(buffer); pendingFor = buffer }
        val done = inner.handleBuffer(pending!!, presentationTimeUs, encodedAccessUnitCount)
        if (done) { buffer.position(buffer.limit()); pending = null; pendingFor = null }
        return done
    }

    /** [buffer] (the decoder's PCM) as float, or as words. */
    private fun convert(buffer: ByteBuffer): ByteBuffer {
        val bytesPer = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            else -> 4
        }
        val samples = buffer.remaining() / bytesPer
        if (out.capacity() < samples * 4) out = ByteBuffer.allocateDirect(samples * 4)
        out.order(if (words) ByteOrder.LITTLE_ENDIAN else ByteOrder.nativeOrder())
        out.clear()
        val big = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val src = buffer.duplicate().order(if (big) ByteOrder.BIG_ENDIAN else if (encoding == C.ENCODING_PCM_FLOAT) ByteOrder.nativeOrder() else ByteOrder.LITTLE_ENDIAN)
        val gain = TrackGain.gain
        for (i in 0 until samples) {
            val w = when (encoding) {
                C.ENCODING_PCM_FLOAT -> PcmWords.of(src.getFloat().toDouble())
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> PcmWords.of16(src.getShort().toInt())
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                    val b0 = src.get().toInt() and 0xff; val b1 = src.get().toInt() and 0xff; val b2 = src.get().toInt() and 0xff
                    PcmWords.of24(if (big) (b0 shl 16) or (b1 shl 8) or b2 else (b2 shl 16) or (b1 shl 8) or b0)
                }
                C.ENCODING_PCM_8BIT -> PcmWords.of8(src.get().toInt())
                else -> src.getInt()
            }
            val g = PcmWords.scaled(w, gain)
            if (words) out.putInt(g) else out.putFloat(PcmWords.value(g).toFloat())
        }
        out.flip()
        return out
    }

    /** The decoders have moved on to the next track (gapless): its ReplayGain from here. */
    override fun handleDiscontinuity() { TrackGain.streamChanged(); inner.handleDiscontinuity() }

    override fun flush() { inner.flush(); pending = null; pendingFor = null }
    override fun reset() { inner.reset(); pending = null; pendingFor = null }

    private companion object {
        val PCM = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT)
    }
}

/**
 * LMS's ReplayGain on what is being decoded now (Mandarin's DspEngine.gains): a multiplier, 1 for none. The decoders run
 * ahead of what is heard, so the next track's gain is taken up when they move on to it (WordsSink's discontinuity), not
 * when it is heard. PhoneRelay sets the track heard now and the one after it at each change.
 */
object TrackGain {
    @Volatile var gain: Double = 1.0
        private set
    private var upcoming = 1.0
    private var armed = false

    /** dB as a multiplier (null: none). */
    fun of(db: Double?): Double = if (db == null) 1.0 else Math.pow(10.0, db / 20.0)

    /** The track heard now and the one after it. */
    @Synchronized fun gains(current: Double, next: Double) { gain = current; upcoming = next; armed = true }
    /** The decoders moved on: the next track's gain, once per change. */
    @Synchronized fun streamChanged() { if (armed) { gain = upcoming; armed = false } }
    @Synchronized fun reset() { gain = 1.0; upcoming = 1.0; armed = false }
}
