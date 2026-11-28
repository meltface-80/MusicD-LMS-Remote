package com.musicd.mallard

import android.content.Context
import android.os.Build
import com.musicd.mallard.client.phoneBye
import com.musicd.mallard.client.phoneHello

/**
 * The phone's own player away from home (Mandarin's phone zone, made an LMS player by the server; lib/phoneplayers.js).
 * Gone away (Away.kt, through the phone's Tailscale): hello — the server starts the phone's player and the screens play in
 * it alone ([id]; the server's zone list away is that player only, as "This phone") — and its stream plays
 * (PhoneStreamService). Home again: goodbye, the stream stops, and the screens are back in the room kept for home, which
 * going away never overwrote (Store.zone). Blocking; called on Away's own thread. At home "This phone" is one of the
 * rooms too ([here] / [leaveHere]), as Mandarin's. The hello names the highest rate the USB DAC takes; each track then
 * comes as the file itself, or away with Opus chosen as its Opus (PhoneStreamService → PhoneRelay).
 */
internal object PhoneZone {
    /** This phone's player while away; null at home. */
    @Volatile var id: String? = null
        private set
    @Volatile private var name = ""
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** What LMS calls this phone: its maker and model ("Google Pixel 8"). */
    fun phoneName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").trim().ifEmpty { "Mallard phone" }

    fun goAway(c: Context) {
        val client = Store.client(c) ?: return
        val h = runCatching { client.phoneHello(Store.deviceId(c), phoneName(), maxRate(c)) }.getOrElse {
            AppLog.line("AWAY", "the server didn't start this phone's player: ${it.message}", anomaly = true); return
        }
        id = h.playerId; name = h.name
        AppLog.line("AWAY", "away: Lyrion plays to this phone (${h.name})")
        NativeShell.awayZone(h.playerId)
        play(c)
    }

    /** The phone's sound: its player's tracks, played here (PhoneStreamService). */
    fun play(c: Context) {
        val p = id ?: return
        PhoneStreamService.start(c, p, name)
    }

    /** This phone's own player id, whether or not it runs (the server lists it as "This phone" at home). */
    fun ownId(c: Context): String = com.musicd.mallard.client.ThisPhone.playerId(Store.deviceId(c))

    /**
     * "This phone" chosen among the rooms at home (owner request: the phone zone on the same network as Lyrion too, as
     * Mandarin's): its player started and its sound played here, as away. Choosing a room stops it again ([leaveHere]).
     */
    fun here(c: Context) {
        val app = c.applicationContext
        work.execute {
            if (id != null) return@execute
            val client = Store.client(app) ?: return@execute
            val h = runCatching { client.phoneHello(Store.deviceId(app), phoneName(), maxRate(app)) }.getOrElse {
                AppLog.line("PHONE", "the server didn't start this phone's player: ${it.message}", anomaly = true); return@execute
            }
            id = h.playerId; name = h.name
            AppLog.line("PHONE", "at home: Lyrion plays to this phone (${h.name})")
            play(app)
        }
    }

    /** A room chosen at home while this phone played: its player stopped, its sound too. */
    fun leaveHere(c: Context) {
        if (Store.awayVia) return
        val p = id ?: return
        id = null
        PhoneStreamService.stop(c)
        val app = c.applicationContext
        work.execute { runCatching { Store.client(app)?.phoneBye(p) } }
        AppLog.line("PHONE", "at home: this phone's own player stopped")
    }

    /** The highest rate this phone takes: the USB DAC's, with USB direct on and one on the port; else 192 kHz. */
    private fun maxRate(c: Context): Int {
        val info = UsbDac.info()
        val rates = info?.streams?.filter { it.pcm }?.flatMap { s -> s.rates + (s.continuous?.let { listOf(it[1]) } ?: emptyList()) }.orEmpty() + (info?.clockRates ?: emptyList())
        return rates.maxOrNull()?.takeIf { Store.usbDirect(c) && UsbDac.device() != null && it >= 44100 } ?: 192000
    }

    fun goHome(c: Context, homeClient: com.musicd.mallard.client.ServerClient?) {
        val p = id ?: return
        id = null
        PhoneStreamService.stop(c)
        NativeShell.awayZone(null)
        runCatching { homeClient?.phoneBye(p) }
        AppLog.line("AWAY", "home: the phone's own player stopped; the screens play in the room kept for home")
    }
}
