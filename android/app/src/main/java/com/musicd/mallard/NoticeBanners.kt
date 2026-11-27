package com.musicd.mallard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.MallardRelease
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home's notices: a newer Mallard (AppUpdate, as Mandarin's app offers its own: the up_icon, Update and Later), and a
 * newer MusicD LMS Remote on the server (/api/update/status). Each said once a visit, until Later is tapped.
 */
internal object Notices {
    /** The server version on offer, and the one Later was tapped on (not offered again this run). */
    val latest = mutableStateOf<String?>(null)
    var dismissed: String? = null
    /** The Mallard build Later was tapped on. */
    var appDismissed: Int? = null
    /** How often Home looks again while it is on screen (the app's own lookup holds its answer an hour). */
    const val LOOK_MS = 15L * 60 * 1000
}

@Composable
internal fun NoticeBanners(zoom: Double, client: () -> ServerClient?, onPage: (String, String?) -> Unit) {
    val onScreen by NativeShell.onScreen
    var latest by Notices.latest
    val app by AppUpdate.latest
    var appLater by androidx.compose.runtime.remember { mutableStateOf(Notices.appDismissed) }
    val context = LocalContext.current
    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        while (true) {
            AppUpdate.lookup(false)
            val c = client()
            if (c != null) {
                val j = withContext(Dispatchers.IO) { runCatching { c.updateStatus() }.getOrNull() }
                if (j != null) latest = j.optString("latest", "").takeIf { j.optBoolean("available", false) && !j.optBoolean("isDowngrade", false) && it.isNotEmpty() }
            }
            delay(Notices.LOOK_MS)
        }
    }
    Column {
        app?.takeIf { it.newerThan(BuildConfig.VERSION_CODE) && it.code != appLater }?.let { r ->
            AppOffer(r, onUpdate = { context.activity()?.let { AppUpdate.installNow(it) } }, onLater = { Notices.appDismissed = r.code; appLater = r.code })
        }
        val v = latest?.takeIf { it != Notices.dismissed } ?: return@Column
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).clip(shape).background(paletteColor(Palette.ACCENT_SOFT))
            .border(1.dp, MaterialTheme.colorScheme.primary, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MusicD LMS Remote $v is available", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.W700, fontSize = 15.sp)
            Text("Update the server from Settings → System.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.W700, fontSize = 14.sp,
                    modifier = Modifier.clickable { NativeShell.open(com.musicd.mallard.client.ShellRoute.settings("system")) }.padding(vertical = 4.dp))
                Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.W600, fontSize = 14.sp,
                    modifier = Modifier.clickable { Notices.dismissed = v; latest = null }.padding(vertical = 4.dp))
            }
        }
    }
}

/** A newer Mallard, as Mandarin's Home offers its app: the up_icon, the line, Update and Later, the notes under them. */
@Composable
private fun AppOffer(r: MallardRelease, onUpdate: () -> Unit, onLater: () -> Unit) {
    val border = MaterialTheme.colorScheme.outline
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(paletteColor(Palette.ACCENT_SOFT)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.up_icon), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Text(MallardRelease.offer(r, BuildConfig.VERSION_NAME), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 18.9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onUpdate)
                    .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Update", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, maxLines = 1)
                }
                val later = RoundedCornerShape(10.dp)
                androidx.compose.foundation.layout.Box(Modifier.height(36.dp).clip(later).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, border, later)
                    .clickable(onClick = onLater).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                }
            }
        }
        r.notes?.let { Text(it, Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}

/** The Activity under a Compose context (Android's installer and dialogs need one). */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}
