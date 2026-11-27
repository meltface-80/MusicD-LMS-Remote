package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home's notices: one, a newer MusicD LMS Remote out (/api/update/status), said once a visit with the version, until
 * Later is tapped. The update itself is the server's (Settings → System → Update, in the app or on its page).
 */
internal object Notices {
    /** The version on offer, and the one Later was tapped on (not offered again this run). */
    val latest = mutableStateOf<String?>(null)
    var dismissed: String? = null
}

@Composable
internal fun NoticeBanners(zoom: Double, client: () -> ServerClient?, onPage: (String, String?) -> Unit) {
    val onScreen by NativeShell.onScreen
    var latest by Notices.latest
    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        val c = client() ?: return@LaunchedEffect
        val j = withContext(Dispatchers.IO) { runCatching { c.updateStatus() }.getOrNull() } ?: return@LaunchedEffect
        latest = j.optString("latest", "").takeIf { j.optBoolean("available", false) && !j.optBoolean("isDowngrade", false) && it.isNotEmpty() }
    }
    val v = latest?.takeIf { it != Notices.dismissed } ?: return
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
