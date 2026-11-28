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
 * At home the engine is stopped (nothing at home uses it), except while Settings → This phone is open ([holding]).
 * Away and on screen, the engine's lease is renewed every minute; off screen it is let go, and stops itself once
 * nothing passes through it.
 */
internal object Away {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "away").apply { isDaemon = true } }
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

    private val look = Runnable { work.execute { check() } }
    fun lookSoon(ms: Long) { main.removeCallbacks(look); main.postDelayed(look, ms) }

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
            if (!holding && PhoneEngine.alive()) PhoneEngine.stop()
        }
    }

    private fun set(via: Boolean) {
        if (Store.awayVia == via) return
        Store.awayVia = via
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
    fun resumed() { lookSoon(0); main.post { lease() } }

    /** Off screen: the lease let go (the engine stops itself once nothing uses it; the next look starts it again). */
    fun paused() { main.post { lease() } }
}
