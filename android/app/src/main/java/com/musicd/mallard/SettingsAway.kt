package com.musicd.mallard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.TailscaleState
import com.musicd.mallard.client.tailscale
import com.musicd.mallard.client.tailscaleEnable
import com.musicd.mallard.client.tailscaleLogin
import com.musicd.mallard.client.tailscaleLogout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Away from home: the server's built-in Tailscale (lib/tailscale.js), as the web app's pane draws it
 * (public/away-settings.js) — switched on or off, its state, Sign in (Tailscale's page in the browser) and Sign out, and
 * its tailnet name and address once connected. Read again every 3 s while open, as signing in happens in the browser.
 * Under it, This phone: Tailscale on the phone itself (SettingsAwayPhone.kt).
 */

@Composable
internal fun AwayPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<TailscaleState?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = client()
            if (c != null && !busy) withContext(Dispatchers.IO) { runCatching { c.tailscale() } }
                .fold({ st = it; said = null }, { if (st == null) said = Failure.said(it, "Couldn't ask the server") })
            delay(3_000)
        }
    }
    fun act(call: (ServerClient) -> TailscaleState, then: (TailscaleState) -> Unit = {}) {
        val c = client() ?: return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { call(c) } }.fold({ st = it; then(it) }, { setupToast(context, Failure.said(it, "Tailscale didn't answer")) })
            busy = false
        }
    }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    SettingsFrame {
        PaneHead("Away from home", onBack)
        val s = st
        when {
            s == null -> SetupNote(said ?: "…", Modifier)
            !s.available -> SetupNote("Tailscale isn't built into this server yet (it is in the Docker image from this version on). Tailscale installed on the computer the server runs on works as before — see the README's Away from home.", Modifier)
            else -> Block {
                SetupRow("Built-in Tailscale", Modifier, Modifier.clickable { info("Tailscale, built into the server: sign it in once and it is a machine on your tailnet by itself — nothing else to install, no ports to open. Any of your own devices signed in to the same Tailscale account then reaches it anywhere. Only your own devices are let in.") }) {
                    Toggle(s.enabled, Modifier.clickable(enabled = !busy) { act({ it.tailscaleEnable(!s.enabled) }) })
                }
                SetupRow("Status", Modifier, null) { Text(s.words, color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp) }
                s.warning?.let { Text(it, color = paletteColor(Palette.DANGER), fontSize = 12.sp) }
                if (s.state == "Running" && s.enabled) {
                    s.dnsName?.let { n -> SetupRow("Name", Modifier, null) { Text(n, color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp) } }
                    s.address?.let { a -> SetupRow("Address", Modifier, null) { Text(a.removePrefix("http://"), color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp) } }
                    SetupRow("", Modifier, null) { SetupButton("Sign out of Tailscale", busy, Modifier.clickable(enabled = !busy) { act({ it.tailscaleLogout() }) }) }
                    SetupNote("On your tailnet as “${s.hostname}”. Any device signed in to the same Tailscale account opens ${s.address ?: "the address above"}.", Modifier)
                } else if (s.enabled) {
                    SetupRow("", Modifier, null) {
                        SetupButton("Sign in to Tailscale", busy, Modifier.clickable(enabled = !busy) {
                            val link = s.authUrl
                            if (link != null) open(link) else act({ it.tailscaleLogin() }) { r -> r.authUrl?.let(::open) }
                        })
                    }
                    SetupNote("Sign in with the same Tailscale account as your phone. The server then joins your tailnet as “${s.hostname}”. Free for personal use.", Modifier)
                }
                s.error?.let { Text(it, color = paletteColor(Palette.DANGER), fontSize = 12.sp) }
            }
        }
        PhoneTailscaleBlock()   // SettingsAwayPhone.kt
    }
}
