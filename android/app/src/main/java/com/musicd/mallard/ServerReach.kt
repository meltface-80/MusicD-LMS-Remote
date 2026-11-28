package com.musicd.mallard

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Health
import com.musicd.mallard.client.Palette

/**
 * Whether the server can be reached, looked at while the app is on screen: at every return to the screen, when the
 * phone's network comes back, and every 20 s while the server is out of reach. Out of reach, a banner says so over
 * every screen with Try again and Change server; the screens themselves keep what they last drew.
 *
 * Reachable but not connected to its Lyrion Music Server is a different answer, and said differently: the server is
 * there, LMS isn't (yet).
 */
internal class ServerReach(private val a: MainActivity) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var onScreen = false

    fun start() {
        runCatching { a.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netWatch) }
    }

    fun stop() {
        runCatching { a.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netWatch) }
        main.removeCallbacks(look)
    }

    fun resumed() { onScreen = true; lookSoon(0) }

    fun paused() { onScreen = false; main.removeCallbacks(look) }

    /** The banner's Try again: looked at now. */
    fun lookNow() { lookSoon(0) }

    private fun lookSoon(ms: Long) { main.removeCallbacks(look); main.postDelayed(look, ms) }

    private val look = Runnable {
        if (!onScreen) return@Runnable
        val client = Store.client(a) ?: return@Runnable
        Thread({
            val health = runCatching { Health.parse(client.getJson("/api/status", 4000)) }
            main.post {
                val h = health.getOrNull()
                state.value = when {
                    h == null -> Reach.Down
                    !h.connected -> Reach.NoLms
                    else -> Reach.Up
                }
                if (h == null) AppLog.line("REACH", "the server didn't answer: " + (health.exceptionOrNull()?.message ?: "?"), anomaly = true)
                if (h == null) Away.lookSoon(0) else Store.setAwayLearned(a, h.tailscale)   // home or away again; its tailnet address kept
                if (state.value != Reach.Up && onScreen) lookSoon(20_000)
            }
        }, "reach").apply { isDaemon = true; start() }
    }

    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { if (onScreen) lookSoon(500) } }
        override fun onLost(network: Network) { main.post { if (onScreen) lookSoon(1500) } }
    }

    enum class Reach { Up, Down, NoLms }

    companion object {
        /** What the last look found; the screens draw the banner from it. */
        val state = mutableStateOf(Reach.Up)
    }
}

/** The out-of-reach banner over every screen: what's wrong, Try again, Change server. Nothing while all is well. */
@Composable
internal fun ReachBanner(onTryAgain: () -> Unit, onChangeServer: () -> Unit) {
    val s = ServerReach.state.value
    if (s == ServerReach.Reach.Up) return
    val c = ::paletteColor
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c(Palette.BG_ELEV)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(if (s == ServerReach.Reach.Down) "Can't reach MusicD LMS Remote" else "Waiting for Lyrion Music Server",
                color = c(Palette.TEXT), fontWeight = FontWeight.W700, fontSize = 15.sp)
            Text(if (s == ServerReach.Reach.Down) "Is the phone on the same network as the server, and is the server running?"
                 else "The server is up but can't reach LMS yet. It keeps trying by itself.",
                color = c(Palette.TEXT_DIM), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Try again", color = c(Palette.ACCENT_TEXT), fontWeight = FontWeight.W700, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onTryAgain).padding(vertical = 6.dp))
                Text("Change server", color = c(Palette.ACCENT_TEXT), fontWeight = FontWeight.W700, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onChangeServer).padding(vertical = 6.dp))
            }
        }
    }
}
