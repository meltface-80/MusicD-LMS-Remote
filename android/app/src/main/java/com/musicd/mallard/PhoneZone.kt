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
 */
internal object PhoneZone {
    /** This phone's player while away; null at home. */
    @Volatile var id: String? = null
        private set
    @Volatile private var name = ""

    /** What LMS calls this phone: its maker and model ("Google Pixel 8"). */
    fun phoneName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").trim().ifEmpty { "Mallard phone" }

    fun goAway(c: Context) {
        val client = Store.client(c) ?: return
        val h = runCatching { client.phoneHello(Store.deviceId(c), phoneName()) }.getOrElse {
            AppLog.line("AWAY", "the server didn't start this phone's player: ${it.message}", anomaly = true); return
        }
        id = h.playerId; name = h.name
        AppLog.line("AWAY", "away: Lyrion plays to this phone (${h.name})")
        NativeShell.awayZone(h.playerId)
        play(c)
    }

    /** The stream (again, as the quality setting now says), while away. */
    fun play(c: Context) {
        val p = id ?: return
        val base = Store.client(c)?.address?.baseUrl ?: return
        PhoneStreamService.start(c, base + PhoneStream.path(p, Store.awayQuality(c)), name)
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
