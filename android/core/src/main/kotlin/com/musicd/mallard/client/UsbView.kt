package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * This phone's USB DAC in Settings → Away from home (Mandarin's USB DAC block, without its DSD): what the app's USB driver
 * says of it (UsbDac.json in the app), in the words the block shows.
 */
class UsbView(val j: JSONObject) {
    val attached: Boolean get() = j.optBoolean("attached")
    val permission: Boolean get() = j.optBoolean("permission")
    val direct: Boolean get() = j.optBoolean("direct")
    val fixed: Boolean get() = j.optBoolean("fixed")
    val limit: Int get() = j.optInt("limit", 80).let { if (it > 0) it else 80 }
    /** The DAC's level, 0–100, as the slider and the volume buttons set it (100 with fixed volume). */
    val volume: Int get() = j.optInt("volume", 100)
    val error: String? get() = j.optString("error").takeIf { it.isNotEmpty() && !j.isNull("error") }
    val streamError: String? get() = j.optString("stream_error").takeIf { it.isNotEmpty() }
    private val info: JSONObject? get() = j.optJSONObject("info")
    val hasInfo: Boolean get() = info != null
    val hasVolumeControl: Boolean get() = info?.optBoolean("volume_control") == true

    /** The DAC's name: its maker (where its name doesn't already say it) and its name. */
    val name: String get() = j.optJSONObject("device")?.let { d -> listOf(if (d.isNull("manufacturer")) "" else d.optString("manufacturer"), d.optString("name")).filter { it.isNotEmpty() }.joinToString(" ") }
        .orEmpty().ifEmpty { "USB audio device" }

    /** Every USB audio device Android lists, as choices: offered when there is more than one (a player's own DAC among them). */
    val devices: List<Choice> get() = j.optJSONArray("devices").let { a -> if (a == null) emptyList() else (0 until a.length()).map { i -> a.getJSONObject(i).let { d ->
        d.optString("name").let { n -> val m = if (d.isNull("manufacturer")) "" else d.optString("manufacturer")
            Choice(d.optString("key"), (if (m.isNotEmpty() && !n.startsWith(m, ignoreCase = true)) "$m $n" else n) + (if (d.optBoolean("own")) " (this device’s own)" else "")) } } } }
    val chosenDevice: String get() = j.optJSONArray("devices").let { a -> if (a == null) "" else (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("chosen") }?.optString("key") ?: "" }
    val rates: List<Int> get() = info?.optJSONArray("rates").ints()
    val bits: List<Int> get() = info?.optJSONArray("bits").ints()
    val ratesLine: String get() = if (rates.isEmpty()) "not stated" else rates.joinToString(" · ") { rateWord(it) } + " kHz"
    val bitsLine: String get() = if (bits.isEmpty()) "not stated" else bits.joinToString(" · ") { "$it-bit" }
    /** USB Audio Class 1 or 2, its speed, how it keeps time. */
    val classLine: String get() = info?.let { i ->
        "USB Audio Class " + (if (i.optInt("uac") == 2) "2" else "1") + i.optString("speed").let { s -> if (s.isNotEmpty() && s != "unknown") " · $s speed" else "" } + " · " + i.optString("sync")
    }.orEmpty()
    val volumeLine: String get() = if (hasVolumeControl) "The DAC has a USB volume control" else "No USB volume control (fixed at full)"
    val clock: String? get() = info?.let { if (it.isNull("current_rate") || it.optInt("current_rate") == 0) null else rateWord(it.optInt("current_rate")) + " kHz" }

    val directNote: String get() = if (direct) "On. Away from home this phone’s sound goes through Mallard’s own USB driver, at the stream’s own rate and the DAC’s depth." else "Off. The phone plays through Android’s mixer, at the mixer’s rate."
    val fixedNote: String get() = if (fixed) "On. The DAC is driven at full and the amplifier sets the level — for a DAC into a preamp. Not for headphones on a DAC with its own volume control."
        else if (hasVolumeControl) "Off. The DAC volume below and the phone’s volume buttons drive the DAC’s own control. It starts at 10% whenever USB direct goes on or the DAC is plugged in — never at the DAC’s own level."
        else "Off. This DAC has no USB volume control, so the DAC volume below and the phone’s volume buttons scale the samples before they go out — no longer bit-perfect. Switch on for bit-perfect at full."

    /** What is being fed to the DAC now, and how it is going; none without a stream. */
    val streaming: String? get() {
        val st = j.optJSONObject("stream") ?: return null
        val s = st.optJSONObject("stats") ?: JSONObject()
        val what = rateWord(st.optInt("rate")) + " kHz · " + st.optInt("bits") + "-bit"
        val vol = if (fixed) " · fixed volume" else if (st.opt("level") is Number) " · volume " + st.optInt("level") + "%" + (if (st.optBoolean("software_volume")) " in software" else "") +
            (st.optJSONObject("volume_db")?.let { " (DAC range " + one(it.optDouble("min")) + " to " + one(it.optDouble("max")) + " dB)" } ?: "") else ""
        val u = s.optInt("underruns"); val e = s.optInt("errors")
        return what + vol + (if (s.opt("running") == false) " · paused" else "") +
            (if (s.optDouble("feedback", 0.0) != 0.0) " · feedback " + String.format(Locale.US, "%.3f", s.optDouble("feedback")) + " frames/packet" else "") +
            (if (u > 0) " · $u underrun" + (if (u == 1) "" else "s") else "") + (if (e > 0) " · $e error" + (if (e == 1) "" else "s") else "")
    }

    companion object {
        const val NOTHING = "Nothing on the USB port. Plug a DAC in and it shows here."
        const val ASK = "Android asks once whether Mallard may use this device; a yes is remembered for it."
        const val READING = "Reading the DAC…"
        const val DIRECT_INFO = "Away from home, Mallard plays through its own USB driver: the DAC is fed the stream at its own rate and the DAC’s depth, Android’s mixer out of the way. A rate the DAC doesn’t take goes through Android as before. Off, the phone plays through Android’s mixer, at the mixer’s rate."
        const val LIMIT_NOTE = "The most the DAC volume can be set to through USB — it stops here, so the DAC is never driven to full by mistake."
        const val DIAGNOSTICS_NOTE = "Everything the DAC said about itself, as text — what a report needs."
        /** 44100 → "44.1", 96000 → "96". */
        fun rateWord(hz: Int): String = (hz / 1000.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
        private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
        private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    }
}
