package com.musicd.mallard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.PageNotices
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ServerNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The notices at the head of Home: the server's, worked out by the app from /api/status ([watch]; core ServerNotices,
 * bug list item 112), and the update offer, the app's own (UpdateNow, core UpdateBanner; bug list item 111), drawn as
 * the page draws them at the head of its own.
 */
internal object Notices {
    val now = mutableStateOf(PageNotices())

    /** The server asked for its status now and then while Home is on screen ([client]), its notice drawn, as the page's check does. */
    suspend fun watch(client: () -> ServerClient?) {
        while (true) {
            val status = client()?.let { c -> withContext(Dispatchers.IO) { runCatching { c.getJson("/api/status") }.getOrNull() } }
            // No answer: no notice, asked again soon (the offline sign says the rest).
            val n = status?.let { ServerNotices.from(it) }
            now.value = now.value.copy(notice = n)
            delay(if (status == null) 5_000 else ServerNotices.nextLook(n, status.optJSONObject("scan")?.optBoolean("running") == true))
        }
    }

    /** The notice's button [i] (Forget a folder, Remove the missing ones), its question answered Yes: done, said, and asked again. */
    suspend fun forget(context: Context, client: () -> ServerClient?, i: Int) {
        val n = now.value.notice ?: return
        val dir = n.dirs.getOrNull(i) ?: return
        val c = client() ?: return
        withContext(Dispatchers.IO) { runCatching { c.post("/api/library/forget-folder", JSONObject().put("dir", dir)) } }
            .onSuccess { j -> Toast.makeText(context, ServerNotices.forgotten(j.optInt("removed"), n.names.getOrNull(i).orEmpty()), Toast.LENGTH_SHORT).show(); watchAgain.value++ }
            .onFailure { Toast.makeText(context, com.musicd.mallard.client.Failure.said(it, "Couldn't reach the server"), Toast.LENGTH_SHORT).show() }
    }

    /** Bumped to ask the server again at once (a folder forgotten). */
    val watchAgain = mutableStateOf(0)
}

/**
 * Both notices, the server's over the update offer, as the page's (style.css: .status-banner, .update-toast), at the UI
 * Settings' menu size (their zoom). The notice's button (Forget a folder) asks its question as the page's yes-or-no sheet,
 * then forgets the folder itself (item 112); the update offer's Update and Later are UpdateNow's ([onPage]: "update").
 */
@Composable
internal fun NoticeBanners(zoom: Double, client: () -> ServerClient?, onPage: (String, String?) -> Unit) {
    val now by Notices.now
    val again by Notices.watchAgain
    // Asked while the app is on screen (item 120: the status every minute all night too).
    val onScreen by NativeShell.onScreen
    LaunchedEffect(again, onScreen) { if (onScreen) Notices.watch(client) }
    LaunchedEffect(onScreen) { if (onScreen) UpdateNow.watch() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Pair<Int, String>?>(null) }
    asking?.let { (i, q) -> ConfirmDialog(q) { yes -> asking = null; if (yes) scope.launch { Notices.forget(context, client, i) } } }
    if (now.notice == null && now.update == null) return
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * zoom.toFloat(), density.fontScale)) {
        Column {
            now.notice?.let { n ->
                val ink = if (n.error) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                val box = RoundedCornerShape(10.dp)
                Column(Modifier.parity("home.notice").padding(start = 28.dp, top = 14.dp, end = 28.dp).fillMaxWidth().clip(box).background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (n.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, box).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    for (line in n.lines) Text(line, color = ink, fontSize = 14.sp, lineHeight = 20.3.sp)
                    Row {
                        n.buttons.forEachIndexed { i, word ->
                            val pill = RoundedCornerShape(8.dp)
                            Box(Modifier.parity("home.notice-button", Contract.label("home.notice-button", if (word.startsWith("Forget")) 0 else 1, word.removePrefix("Forget ")))
                                .padding(top = 10.dp, end = 8.dp).height(36.dp).clip(pill).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, MaterialTheme.colorScheme.outline, pill)
                                .clickable { n.ask(i)?.let { q -> asking = i to q } ?: scope.launch { Notices.forget(context, client, i) } }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text(word, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            now.update?.let { u ->
                val red = Color(0xFFE5484D)
                val border = MaterialTheme.colorScheme.outline
                Column(Modifier.parity("home.update").padding(start = 14.dp, end = 14.dp, bottom = 14.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(if (u.error) red.copy(alpha = 0.16f) else paletteColor(Palette.ACCENT_SOFT))
                    .drawBehind { drawLine(border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.up_icon), contentDescription = null, tint = if (u.error) red else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Text(u.text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 18.9.sp)
                        // Busy (downloading, restarting): no Update or Later to tap, as the page's are let alone then.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            u.now?.let { word ->
                                Box(Modifier.parity("home.update-now", Contract.label("home.update-now")).height(36.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary).clickable { onPage("update", "now") }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(word, color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, maxLines = 1)
                                }
                            }
                            if (u.later) {
                                val later = RoundedCornerShape(10.dp)
                                Box(Modifier.parity("home.update-later", Contract.label("home.update-later")).height(36.dp).clip(later).background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, border, later).clickable { onPage("update", "later") }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (u.notes.isNotEmpty()) Text(u.notes, Modifier.parity("home.update-notes").fillMaxWidth().heightIn(max = 120.dp)
                        .drawBehind { drawLine(border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }.verticalScroll(rememberScrollState()).padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
