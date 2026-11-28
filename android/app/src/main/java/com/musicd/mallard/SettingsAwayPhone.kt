package com.musicd.mallard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PhoneEngineState
import com.musicd.mallard.client.PhoneTailscale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Away from home → This phone (owner request: "No Mallard android app tailscale login yet"): Tailscale on
 * the phone itself (PhoneEngine), as Mandarin's phone has it. Sign in once with the same Tailscale account as the
 * server; away from home Mallard then reaches the server through it by itself (Away.kt). While this is open the engine
 * runs even at home (Away.holding), so signing in can finish; it is read again every 2 s, as signing in happens in the
 * browser.
 */
@Composable
internal fun PhoneTailscaleBlock() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { PhoneEngine.installed(context) }
    var st by remember { mutableStateOf<PhoneEngineState?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var away by remember { mutableStateOf(Store.awayVia) }
    DisposableEffect(Unit) {
        Away.holding = true
        onDispose { Away.holding = false; Away.lookSoon(500) }
    }
    if (installed) LaunchedEffect(Unit) {
        while (true) {
            if (!busy) withContext(Dispatchers.IO) { runCatching { PhoneEngine.status(context) } }
                .fold({ st = it; said = null }, { said = it.message ?: "The engine didn't answer" })
            away = Store.awayVia
            delay(2_000)
        }
    }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun act(call: () -> PhoneEngineState, then: (PhoneEngineState) -> Unit = {}) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { call() } }.fold({ st = it; then(it) }, { setupToast(context, it.message ?: "Tailscale didn't answer") })
            busy = false
        }
    }
    val dim = paletteColor(Palette.TEXT_DIM)
    Text("THIS PHONE", Modifier, color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, letterSpacing = 0.7.sp)
    Block {
        if (!installed) { SetupNote("Tailscale for this phone isn't in this build: it needs a 64-bit phone.", Modifier); return@Block }
        val s = st
        SetupRow("Status", Modifier, null) { Text(s?.words ?: said ?: "Starting…", color = dim, fontSize = 14.sp) }
        if (s != null && s.signedIn) {
            s.dnsName?.let { n -> SetupRow("Name", Modifier, null) { Text(n, color = dim, fontSize = 14.sp) } }
            s.address?.let { a -> SetupRow("Address", Modifier, null) { Text(a, color = dim, fontSize = 14.sp) } }
            SetupRow("Now", Modifier, null) { Text(if (away) "Away, through Tailscale" else "At home", color = dim, fontSize = 14.sp) }
            SetupRow("", Modifier, null) { SetupButton("Sign out of Tailscale", busy, Modifier.clickable(enabled = !busy) { act({ PhoneEngine.logout(context) }) }) }
        } else if (s != null) {
            SetupRow("", Modifier, null) {
                SetupButton("Sign in to Tailscale", busy, Modifier.clickable(enabled = !busy) {
                    val link = s.authUrl
                    if (link != null) open(link) else act({ PhoneEngine.login(context) }) { r -> r.authUrl?.let(::open) }
                })
            }
        }
        // How this phone streams away and what it keeps ahead live on its own page (owner request): Audio Devices → This phone.
        SetupNote("How this phone streams away from home, and the tracks it keeps ahead: Settings → Audio Devices → This phone.", Modifier)
        val target = PhoneTailscale.target(Store.awayLearned(context))
        SetupNote(
            "Sign in with the same Tailscale account as the server. Away from home, Mallard then reaches the server through it by itself — no Tailscale app needed. " +
                if (target != null) "The server is at $target on your tailnet." else "The server's tailnet address isn't known yet: sign the server in above, then open Mallard once at home.",
            Modifier)
    }
}
