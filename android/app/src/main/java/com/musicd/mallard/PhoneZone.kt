package com.musicd.mallard

import android.content.Context
import android.os.Build
import com.musicd.mallard.client.PhoneStream
import com.musicd.mallard.client.phoneBye
import com.musicd.mallard.client.phoneHello

/**
 * The phone's own player away from home (Mandarin's phone zone, made an LMS player by the server; lib/phoneplayers.js).
 * Gone away (Away.kt, through the phone's Tailscale): hello — the server starts the phone's player and the screens play in
 * it alone ([id]; the server's zone list away is that player only, as "This phone") — and its stream plays
 * (PhoneStreamService). Home again: goodbye, the stream stops, and the screens are back in the room kept for home, which
 * going away never overwrote (Store.zone). Blocking; called on Away's own thread.
 *
 * Untouched (Stream away as): the hello asks for the server's relay player ("exact") and names the highest rate the USB
 * DAC takes, and the phone plays the relay's tracks (PhoneStreamService → PhoneRelay) instead of a stream.
 */
internal object PhoneZone {
    /** This phone's player while away; null at home. */
    @Volatile var id: String? = null
        private set
    @Volatile private var name = ""
    /** The server's player for this phone, as the hello made it: "exact" (the relay) or "pcm" (squeezelite). */
    @Volatile var mode = "pcm"
        private set
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** What LMS calls this phone: its maker and model ("Google Pixel 8"). */
    fun phoneName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").trim().ifEmpty { "Mallard phone" }

    fun goAway(c: Context) {
        val client = Store.client(c) ?: return
        val want = PhoneStream.mode(Store.awayQuality(c))
        val h = runCatching { client.phoneHello(Store.deviceId(c), phoneName(), want, maxRate(c)) }.getOrElse {
            AppLog.line("AWAY", "the server didn't start this phone's player: ${it.message}", anomaly = true); return
        }
        id = h.playerId; name = h.name; mode = h.mode
        AppLog.line("AWAY", "away: Lyrion plays to this phone (${h.name})")
        NativeShell.awayZone(h.playerId)
        play(c)
    }

    /** The phone's sound (again, as the setting now says), while away: the relay's tracks for Untouched, else the stream. */
    fun play(c: Context) {
        val p = id ?: return
        if (mode == "exact") { PhoneStreamService.startRelay(c, p, name); return }
        val base = Store.client(c)?.address?.baseUrl ?: return
        PhoneStreamService.start(c, base + PhoneStream.path(p, Store.awayQuality(c)), name)
    }

    /** Stream away as changed (Settings): a change between Untouched and the others swaps the server's player, so hello again first. */
    fun requality(c: Context) {
        if (id == null) return
        val app = c.applicationContext
        work.execute {
            if (PhoneStream.mode(Store.awayQuality(app)) != mode) goAway(app) else play(app)
        }
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
            val h = runCatching { client.phoneHello(Store.deviceId(app), phoneName(), PhoneStream.mode(Store.awayQuality(app)), maxRate(app)) }.getOrElse {
                AppLog.line("PHONE", "the server didn't start this phone's player: ${it.message}", anomaly = true); return@execute
            }
            id = h.playerId; name = h.name; mode = h.mode
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
