package com.musicd.mallard

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * LMS's own settings pages inside Mallard (owner request): Settings → LMS server → Server settings, and a plugin's own
 * settings from Plugins, open in a page of the app's — Back in the brass disc, the page under it — rather than in the
 * phone's browser. The pages come through this server's LMS proxy at its own address (/api/lms/settings-info names the
 * page: Material's when that plugin is installed, else Lyrion's classic), as the web app's Settings frames them; the
 * proxy adds LMS's login and a phone viewport. Links to anywhere else leave for the browser. The web app is unchanged.
 */

/** The settings page /api/lms/settings-info names, as a path on this server (Lyrion's classic index if it can't say). */
internal suspend fun lmsSettingsPath(client: () -> ServerClient?): String {
    val c = client() ?: return "/settings/index.html"
    return withContext(Dispatchers.IO) { runCatching { c.getJson("/api/lms/settings-info").optString("settings_path") }.getOrNull() }
        ?.takeIf { it.startsWith("/") } ?: "/settings/index.html"
}

/**
 * An LMS page in the app: [path] on this server ([title] at the head; null [path]: the settings page the server names).
 * Back steps back through the pages opened in it, then closes it ([onBack]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LmsPage(path: String?, title: String, client: () -> ServerClient?, onBack: () -> Unit) {
    val context = LocalContext.current
    val base = client()?.address?.baseUrl.orEmpty()
    var url by remember { mutableStateOf(path?.let { base + it }) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(path) { if (path == null) url = base + lmsSettingsPath(client) }
    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else onBack() }
    DisposableEffect(Unit) { onDispose { web?.apply { stopLoading(); destroy() } } }
    Column(Modifier.fillMaxSize().background(paletteColor(Palette.BG_ELEV)).systemBarsPadding()) {
        Box(Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp)) {
            SettingsHead(Modifier.clip(CircleShape).clickable(onClick = onBack), R.drawable.np_back, title, Modifier, below = 10)
        }
        // A brass line along the top while a page loads.
        Box(Modifier.fillMaxWidth().height(2.dp).background(paletteColor(if (loading) Palette.ACCENT else Palette.BG_ELEV)))
        val start = url
        if (start == null) Text("Asking the server…", Modifier.padding(20.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp)
        else AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Palette.BG_ELEV.toInt())
                settings.javaScriptEnabled = true          // LMS's pages and Material's are scripts
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        if (base.isNotEmpty() && u.startsWith(base)) return false   // this server's pages stay here
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                    override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { loading = true }
                    override fun onPageFinished(view: WebView, u: String?) { loading = false }
                }
                loadUrl(start)
                web = this
            }
        })
    }
}
