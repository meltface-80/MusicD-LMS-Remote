package com.musicd.mallard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.musicd.mallard.client.DiagnosticFile
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.diagnosticLog
import com.musicd.mallard.client.diagnostics
import com.musicd.mallard.client.setDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → System → the diagnostic log (owner request: "logging … off by default, a user turns it on if they feel
 * they are having trouble … accessed via the Mallard android app and able to download to the phone"; the server's
 * lib/diagnostics.js, the web app's diag-log.js). The switch is the server's, shared with the web app; Download saves one
 * text file — the server's log (with this phone's lines while logging was on), Lyrion's own server and scanner logs, and
 * this phone's recent lines — to Downloads/Mallard, or where the phone's own picker says on Android 8 and 9.
 */
private const val INFO = "Off unless you switch it on. While it is on, the server logs in detail, and this phone and the web app " +
    "send it what they do — screens, taps, every request, playback, errors and crashes — so a problem can be found. Switch it " +
    "on if something isn't working, use the app until it happens, then download the log and send it. Passwords, keys, emails " +
    "and internet addresses are left out."

@Composable
internal fun DiagnosticsBlock(client: () -> ServerClient?, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        on = withContext(Dispatchers.IO) { runCatching { c.diagnostics() }.getOrNull() }
    }
    // Android 8 and 9: the phone's own picker says where (no shared Downloads to write to without a permission).
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } } }
            setupToast(context, r.fold({ "Log saved" }, { Failure.said(it, "Couldn't save the log") }))
        }
    }
    SetupRow("Diagnostic logging", Modifier, Modifier.clickable { info(INFO) }) {
        Toggle(on == true, Modifier.clickable(enabled = on != null) {
            val c = client() ?: return@clickable
            val want = on != true
            on = want
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { c.setDiagnostics(want) } }
                r.onSuccess { on = it; AppLog.shipping = it }
                setupToast(context, r.fold({ if (it) "Diagnostic logging on — download the log once the problem has happened" else "Diagnostic logging off" },
                    { on = !want; Failure.said(it, "Couldn't reach the server") }))
            }
        }, on != null)
    }
    SetupRow("Log", Modifier, null) {
        SetupButton(if (busy) "…" else "Download", busy, Modifier.clickable(enabled = !busy) {
            val c = client() ?: return@clickable
            busy = true
            scope.launch {
                val device = PhoneZone.phoneName(context)
                val got = withContext(Dispatchers.IO) { runCatching { DiagnosticFile.join(String(c.diagnosticLog()), AppLog.lines().joinToString("\n"), device) } }
                busy = false
                val text = got.getOrElse { setupToast(context, Failure.said(it, "Couldn't get the log from the server")); return@launch }
                val name = DiagnosticFile.name(System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= 29) {
                    val r = withContext(Dispatchers.IO) { runCatching { saveToDownloads(context, name, text) } }
                    setupToast(context, r.fold({ "Saved to Downloads/Mallard/$name" }, { Failure.said(it, "Couldn't save the log") }))
                } else { pending = text; pick.launch(name) }
            }
        })
    }
    SetupNote("One file: the server's log — with this phone's and the web app's lines while logging is on — Lyrion's own " +
        "server and scanner logs, and this phone's recent lines. Look through it before you send it.", Modifier)
}

/** [text] saved as Downloads/Mallard/[name] (Android 10 and later: MediaStore, no permission needed). */
private fun saveToDownloads(c: Context, name: String, text: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Mallard")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = c.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android wouldn't make the file")
    resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    return uri
}
