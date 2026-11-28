package com.musicd.mallard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LabelEntry
import com.musicd.mallard.client.LabelList
import com.musicd.mallard.client.LabelMerge
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.labelList
import com.musicd.mallard.client.labelsRescan
import com.musicd.mallard.client.mergeLabels
import com.musicd.mallard.client.unmergeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Labels (the web app's side-menu row; public/app.js, the labels browser): every record label as a tile — its logo, or
 * its name in words — with how many albums and how many labels are merged into it. A tap opens its albums (LabelScreen);
 * a long press starts picking labels to merge, the first picked being the one the rest merge into; "N merged" lists those
 * merged into it, each with ✕ to undo. Settings → Labels sets the fewest albums a label needs, and the albums' order.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LabelsScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav, onLabel: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var list by remember { mutableStateOf<LabelList?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<List<LabelEntry>?>(null) }   // picking to merge: null when not
    var merged by remember { mutableStateOf<LabelEntry?>(null) }         // its "N merged" sheet open
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        while (true) {
            val c = client() ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { runCatching { c.labelList() } }.fold({ list = it; failed = null }, { failed = "Couldn't load labels: " + Failure.said(it, "no answer") })
            val l = list ?: return@LaunchedEffect
            if (!l.scanning) return@LaunchedEffect
            delay(if (l.labels.isEmpty()) 4_000 else 5_000)   // the scan at work: looked at again, as the page does
        }
    }
    val min = Store.labelMin(context)
    val shown = list?.atLeast(min).orEmpty()
    fun rescan() {
        val c = client() ?: return
        starting = true
        scope.launch { withContext(Dispatchers.IO) { runCatching { c.labelsRescan(false) } }; starting = false; reload++ }
    }
    fun merge() {
        val c = client() ?: return
        val p = picked ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.mergeLabels(p) } }.fold({ picked = null; reload++ }, { toast("Merge failed: " + Failure.said(it, "no answer")) })
        }
    }
    CollectionFrame(zoneId, client, nav) {
        LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    WallHead("Labels", WallPrefs(), failed?.let { it to true })
                    val l = list
                    if (l == null && failed == null) ListNote("Loading…")
                    else if (l != null && shown.isEmpty()) {
                        ListNote(if (l.labels.isNotEmpty() && !l.scanning) "No label has $min or more albums. Change that in Settings → Labels." else l.empty())
                        if (!l.scanning && l.labels.isEmpty()) ActPill(if (starting) "Starting…" else "Rescan now", true, enabled = !starting) { rescan() }
                    }
                }
            }
            items(shown, key = { it.key }) { lb ->
                val p = picked
                val at = p?.indexOfFirst { it.key == lb.key } ?: -1
                Column(Modifier.combinedClickable(
                    onClick = { if (p != null) picked = if (at >= 0) p.filter { it.key != lb.key } else p + lb else onLabel(lb.title) },
                    onLongClick = { if (p == null) picked = listOf(lb) else if (at < 0) picked = p + lb })) {
                    LabelArt(lb, client, if (at == 0) 3 else if (at > 0) 2 else 0)
                    Text(lb.title, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(lb.subtitle, color = paletteColor(Palette.TEXT_DIM), fontSize = 12.sp, maxLines = 1)
                    if (lb.mergedFrom.isNotEmpty()) Text("${lb.mergedFrom.size} merged", Modifier.clickable { if (picked == null) merged = lb }.padding(vertical = 2.dp),
                        color = paletteColor(Palette.ACCENT_TEXT), fontSize = 12.sp)
                }
            }
        }
        // Picking to merge: what it will do, Merge and ✕, across the top.
        picked?.let { p ->
            Row(Modifier.align(Alignment.TopCenter).padding(start = 64.dp, end = 14.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(LabelMerge.said(p), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ActPill("Merge", true, enabled = p.size >= 2) { merge() }
                Box(Modifier.clip(CircleShape).clickable { picked = null }.padding(10.dp)) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp) }
            }
        }
    }
    merged?.let { lb -> UnmergeSheet(lb, client, onClose = { merged = null }) { reload++ } }
}

/** A label's square: its logo (letterboxed on the raised ground), or its name a word to a line; [ring] 3 the merge target, 2 picked. */
@Composable
private fun LabelArt(lb: LabelEntry, client: () -> ServerClient?, ring: Int) {
    val shape = RoundedCornerShape(10.dp)
    val logo = rememberLabelLogo(lb.logoUrl, client)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
        .border(if (ring > 0) ring.dp else 1.dp, paletteColor(if (ring > 0) Palette.ACCENT else Palette.BORDER), shape), contentAlignment = Alignment.Center) {
        if (logo != null) Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
        else Text(lb.title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { listOf("?") }.joinToString("\n"), Modifier.padding(10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.W700, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

/** A label's logo: one of this server's (a path, or its own address), else a picture elsewhere on the web. */
@Composable
internal fun rememberLabelLogo(url: String?, client: () -> ServerClient?): ImageBitmap? {
    val c = client()
    val own = url != null && c != null && (url.startsWith("/") || url.startsWith(c.address.baseUrl))
    val server = rememberImage(if (own) c!!.absoluteUrl(url!!) else null, client)
    val web = rememberWebImage(if (own) null else url)
    return server ?: web
}

/** The labels merged into [lb], each with ✕ to take it out again (the page's unmerge sheet). */
@Composable
private fun UnmergeSheet(lb: LabelEntry, client: () -> ServerClient?, onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var left by remember(lb) { mutableStateOf(lb.mergedFrom) }
    SheetPanel(onClose = onClose, head = {
        SheetTitle("Merged into ${lb.title}", Modifier.weight(1f))
        SheetClose(Modifier.clip(CircleShape).clickable(onClick = onClose))
    }, foot = null) {
        for ((key, display) in left) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(display, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Box(Modifier.clip(CircleShape).clickable {
                val c = client() ?: return@clickable
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { c.unmergeLabel(key) } }.fold({ left = left.filter { it.first != key }; onChanged(); if (left.isEmpty()) onClose() },
                        { toast("Unmerge failed: " + Failure.said(it, "no answer")) })
                }
            }.padding(10.dp)) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp) }
        }
    }
}
