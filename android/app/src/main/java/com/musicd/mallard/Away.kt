package com.musicd.mallard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.musicd.mallard.client.Health
import com.musicd.mallard.client.PhoneTailscale
import com.musicd.mallard.client.Route
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Home or away (Mandarin's Away, cut to what Mallard needs). At home the app talks to the server's saved address. Off
 * the home network — the saved address not answering over the Wi-Fi, or no Wi-Fi at all — and with the phone signed in
 * to its own Tailscale (PhoneEngine) and the server's tailnet address known (Store.awayLearned, from /api/status), it
 * talks to 127.0.0.1:PORT, which the engine forwards to the server over the tailnet. Looked at when the network
 * changes and whenever the app comes back on screen; [changed] then tells the app (ServerReach looks again).
 *
 * At home the engine is kept joined while the app is on screen or this phone plays, so going away is at once (Mandarin's
 * keepReady); otherwise stopped, except while Settings → This phone is open ([holding]).
 * Away and on screen, the engine's lease is renewed every minute; off screen it is let go, and stops itself once
 * nothing passes through it.
 */
internal object Away {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "away").apply { isDaemon = true } }
    private val warming = Executors.newSingleThreadExecutor { r -> Thread(r, "away-warm").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var wasAway = false
    @Volatile var holding = false
    /** Told after each look that changed the way (on the main thread). */
    @Volatile var changed: () -> Unit = {}

    /** Watching the network from now on. */
    fun watch(c: Context) {
        if (app != null) return
        app = c.applicationContext
        runCatching {
            c.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = lookSoon(1500)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { PhoneEngine.sendInterfaces(); lookSoon(1500) }
                override fun onLost(network: Network) = lookSoon(300)
            })
        }
        lookSoon(0)
    }

    private val look = Runnable { lookAt = 0L; work.execute { check() } }
    @Volatile private var lookAt = 0L
    /** Looked at in [ms] — or sooner, if a look is already due sooner (the Wi-Fi lost, then the mobile data come, must not wait longer). */
    fun lookSoon(ms: Long) {
        val at = android.os.SystemClock.uptimeMillis() + ms
        if (lookAt != 0L && lookAt <= at) return
        lookAt = at
        main.removeCallbacks(look); main.postAtTime(look, at)
    }
    /** A look is under way or due: the way may be about to change (ServerReach waits for it before saying it can't reach). */
    val busy: Boolean get() = checking || lookAt != 0L
    @Volatile private var checking = false

    /** The Wi-Fi (or Ethernet) network the phone is on, if any: not a VPN. */
    @Suppress("DEPRECATION")
    private fun wifi(c: Context): Network? = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun vpn(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    /** The saved address answering as this server, over [net] (the Wi-Fi) when there is one. */
    private fun homeAnswers(c: Context, net: Network?): Boolean {
        val home = Store.server(c) ?: return false
        return runCatching {
            val url = URL(home.baseUrl + "/api/status")
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 2500
            try {
                if (conn.responseCode != 200) false
                else { val h = Health.parse(JSONObject(conn.inputStream.bufferedReader().readText())); Store.setAwayLearned(c, h.tailscale); true }
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    private fun check() {
        checking = true
        try { checkNow() } finally { checking = false }
    }

    private fun checkNow() {
        val c = app ?: return
        if (Store.server(c) == null) { set(false); return }
        val net = wifi(c)
        val onVpn = net == null && vpn(c)
        var answers = false
        if (net != null || onVpn) {
            val tries = Route.looksBeforeAway(wasAway, net != null)
            for (i in 0 until tries) { if (homeAnswers(c, net)) { answers = true; break }; if (i < tries - 1) Thread.sleep(1000) }
        }
        val target = PhoneTailscale.target(Store.awayLearned(c))
        val way = Route.way(net != null, answers, onVpn, target != null, PhoneEngine.signedIn(c))
        wasAway = way.away
        if (way.viaEngine && target != null) {
            val up = PhoneEngine.connectAway(c, target)
            AppLog.line("REACH", if (up) "away: through the phone's Tailscale to the server" else "away: the phone's Tailscale didn't reach the server", anomaly = !up)
            set(up)
            if (up) lease()
        } else {
            set(false)
            // At home: kept joined and forwarding while the app is on screen or this phone plays, so that the moment the
            // Wi-Fi goes the way away is already there (Mandarin's keepReady, item 202); else stopped.
            val warm = target != null && PhoneEngine.signedIn(c) && (NativeShell.onScreen.value || PhoneZone.id != null)
            // On its own thread: joining can take a few seconds, and the next look (the Wi-Fi gone) mustn't wait for it.
            if (warm && target != null) { if (!PhoneEngine.forwarding()) warming.execute { runCatching { PhoneEngine.connectAway(c, target) } } }
            else if (!holding && PhoneEngine.alive()) PhoneEngine.stop()
        }
    }

    private fun set(via: Boolean) {
        val c = app
        if (Store.awayVia == via) { if (via && c != null && PhoneZone.id == null) PhoneZone.goAway(c); return }
        val home = c?.let { Store.client(it) }   // the client before the switch: away, the tunnel; home, the home address
        Store.awayVia = via
        // Away: this phone's own player (hello, its stream, the screens in it alone); home: goodbye, back to the home room.
        if (c != null) { if (via) PhoneZone.goAway(c) else PhoneZone.goHome(c, home) }
        main.post { changed() }
    }

    // The lease while the app is on screen and away: renewed every minute; let go otherwise.
    private val renew = Runnable { lease() }
    private fun lease() {
        main.removeCallbacks(renew)
        val keep = Store.awayVia && NativeShell.onScreen.value
        work.execute { PhoneEngine.keep(if (keep) PhoneTailscale.LEASE_S else 0) }
        if (keep) main.postDelayed(renew, PhoneTailscale.RENEW_MS)
    }

    /** The app back on screen: looked at again, and the lease taken up. */
    fun resumed() {
        lookSoon(0); main.post { lease() }
        // Away, the phone's stream played again if it was stopped (its notification's Stop, or Android not letting it start off screen).
        app?.let { c -> if (PhoneZone.id != null) work.execute { PhoneZone.play(c) } }
    }

    /** Off screen: the lease let go (the engine stops itself once nothing uses it; the next look starts it again). */
    fun paused() { main.post { lease() }; lookSoon(1_000) }   // at home, off screen and not playing: the engine let go
}
