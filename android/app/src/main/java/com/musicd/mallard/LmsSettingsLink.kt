package com.musicd.mallard

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → LMS server → Server settings: LMS's OWN settings pages in the browser, through this server's proxy at its
 * own address (/api/lms/settings-info names the page: Material's when that plugin is installed, else Lyrion's classic
 * one), as the web app's Settings → LMS server frames them. It used to open the server's "/" — the web app's Home,
 * never the settings (owner report).
 */
internal fun openLmsSettings(context: Context, client: () -> ServerClient?, scope: CoroutineScope) {
    val c = client() ?: return
    scope.launch {
        val path = withContext(Dispatchers.IO) { runCatching { c.getJson("/api/lms/settings-info").optString("settings_path") }.getOrNull() }
            ?.takeIf { it.startsWith("/") } ?: "/settings/index.html"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.address.baseUrl + path))) }
    }
}
