package com.musicd.mallard

import android.content.Intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Settings → System: the two things that update, each with its own row, as Mandarin's Settings → Updates has them.
 * The server (MusicD LMS Remote, /api/update/…) updates itself in place; Mallard updates through AppUpdate, Android's
 * installer putting the new build over this one. A test build of either says so and how it is updated instead.
 */
@Composable
internal fun SystemPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    SettingsFrame {
        PaneHead("System", onBack)
        ServerAddressRow(client)   // this phone's server (it was Settings → Mallard's own pane; owner: one fewer row)
        Block { ServerUpdateRow(client) }
        Block { AppUpdateRow() }
        SettingsDivider()
        Block { DiagnosticsBlock(client, info) }   // the diagnostic log (SettingsDiag.kt)
    }
}

@Composable
private fun ServerUpdateRow(client: () -> ServerClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        status = withContext(Dispatchers.IO) { runCatching { c.updateStatus() }.getOrNull() }
    }
    val s = status
    val current = s?.optString("current").orEmpty()
    val dev = s?.optString("channel") == "dev"
    val available = s?.optBoolean("available") == true && s.optBoolean("isDowngrade") != true
    SetupRow("Server", Modifier, null) {
        if (available) SetupButton(if (busy) "…" else "Update to " + s!!.optString("latest"), busy, Modifier.clickable(enabled = !busy) {
            val c = client() ?: return@clickable
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { c.post("/api/update/apply", JSONObject(), 30_000) } }
                setupToast(context, r.fold({ "Updating — the server restarts by itself in a minute" }, { Failure.said(it, "Couldn't start the update") }))
                busy = false
            }
        })
        else if (!dev) SetupButton(if (busy) "…" else "Check for updates", busy, Modifier.clickable(enabled = !busy) {
            val c = client() ?: return@clickable
            busy = true
            scope.launch {
                status = withContext(Dispatchers.IO) { runCatching { c.post("/api/update/check", JSONObject(), 30_000) }.getOrNull() } ?: status
                busy = false
                if (status?.optBoolean("available") != true) setupToast(context, "You're on the newest version")
            }
        })
    }
    SetupNote(when {
        current.isEmpty() -> "…"
        dev -> "Mallard server v$current · a test build"
        else -> "Mallard server v$current" + if (available) " · v${s!!.optString("latest")} is available" else " · up to date"
    }, Modifier)
    s?.optString("notes")?.takeIf { (available || dev) && it.isNotEmpty() && !s.isNull("notes") }?.let { SetupNote(it.take(1200), Modifier.padding(top = 8.dp)) }
}

/** This app's row: Check for updates, or Update to the newer build (AppUpdate), and what is new in it. */
@Composable
private fun AppUpdateRow() {
    val context = LocalContext.current
    val latest by AppUpdate.latest
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppUpdate.lookup(false) }
    val offer = latest?.takeIf { it.newerThan(BuildConfig.VERSION_CODE) }
    SetupRow("Mallard", Modifier, null) {
        if (offer != null) SetupButton("Update to " + offer.version, false, Modifier.clickable { context.activity()?.let { AppUpdate.installNow(it) } })
        else SetupButton(if (checking) "Checking…" else "Check for updates", checking, Modifier.clickable(enabled = !checking) {
            checking = true
            AppUpdate.lookup(true) { ok ->
                checking = false
                if (!ok) setupToast(context, "Couldn't reach GitHub to look for an update")
                else if (!AppUpdate.available()) setupToast(context, "Mallard is up to date")
            }
        })
    }
    SetupNote("Mallard v${BuildConfig.VERSION_NAME}" + when {
        offer != null -> " · v${offer.version} is available"
        BuildConfig.VERSION_NAME.contains("-t") -> " · a test build: offered each new test build, and the release over them"
        else -> ""
    }, Modifier)
    offer?.notes?.let { SetupNote(it.take(1200), Modifier.padding(top = 8.dp)) }
}

/** The server this phone talks to, as an address and port, and Change (the connect screen). */
@Composable
private fun ServerAddressRow(client: () -> ServerClient?) {
    val context = LocalContext.current
    Block {
        SetupRow("Server address", Modifier, null) {
            SetupButton("Change", false, Modifier.clickable { context.startActivity(Intent(context, ConnectActivity::class.java)) })
        }
        SetupNote(client()?.address?.toString() ?: "None chosen", Modifier)
    }
}
