package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.HomeRow
import com.musicd.mallard.client.HomeRowsSettings
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.ListedRow
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.HomeRows
import com.musicd.mallard.client.SettingsKept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Setup → Home Screen natively (the group T of old/plan-android-native.md, T3; bug list item 81), as the
 * page's (docs/specs/screens.json, settings-homescreen; settings-home.js renderHomeRowsList, style.css .home-row-item): the
 * greeting and Random Album, each on or off on this phone; then Home's rows in the owner's order, each with its handle, its
 * name (and why it can't be switched, where it can't) and its switch. Held by its handle a row follows the finger, the rows
 * it passes stepping past it; let go, the order is saved, as a switch is at once, and native Home takes it from the answer.
 */

/** The rows as the server last said, every row in (item 198); none if never read. */
private fun keptRows(): List<HomeRow> =
    SettingsKept.last("/api/settings/home-rows") { HomeRowsSettings.complete(HomeRows.parse(it), HomeKept.ROW_IDS) } ?: emptyList()

/** Settings' Home Screen ([client]: the server's, [onBack]: back to Setup). */
@Composable
internal fun HomeScreenPane(client: () -> ServerClient?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(pagePrefs(context)) }
    // As last seen (this run's Home, else the last answer kept), then the server's (item 198).
    val rows = remember { mutableStateListOf<HomeRow>().apply { addAll(HomeKept.layout.ifEmpty { keptRows() }) } }
    var downloaded by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    fun show(next: List<HomeRow>) { rows.clear(); rows.addAll(HomeRowsSettings.complete(next, HomeKept.ROW_IDS)) }
    suspend fun reload() {
        val c = client() ?: return
        HomeKept.readLayout(LibraryClient { c.getJson(it) })?.let(::show)
    }
    LaunchedEffect(Unit) {
        reload()
    }
    // Saved at once (the page's saveHomeRows), and Home laid out from it before the answer comes; the server's answer drawn
    // when it does, or what it keeps read again when it refuses, rather than keep a lie. Saved by Home's own keeper, so a
    // save outlives this page and Home's next read waits for it (item 83).
    fun save() {
        val c = client() ?: return
        HomeKept.saveLayout(c, rows.toList()) { r ->
            r.onSuccess { got -> if (got != null) show(got) }
                .onFailure { setupToast(context, com.musicd.mallard.client.Failure.said(it, "Couldn't save")); scope.launch { reload() } }
        }
    }
    // The row held moved by [by] down the screen: past each neighbour once the finger is beyond its middle (the page's drag).
    fun drag(id: String, by: Float) {
        dy += by
        while (true) {
            val listed = HomeRowsSettings.listed(rows, downloaded)
            val at = listed.indexOfFirst { it.id == id }
            val prev = listed.getOrNull(at - 1)?.let { heights[it.id] } ?: Int.MAX_VALUE
            val next = listed.getOrNull(at + 1)?.let { heights[it.id] } ?: Int.MAX_VALUE
            val step = if (dy < -prev / 2f) -1 else if (dy > next / 2f) 1 else 0
            if (step == 0) return
            val moved = HomeRowsSettings.swapped(rows.toList(), id, step)
            rows.clear(); rows.addAll(moved)
            dy -= step * (if (step < 0) prev else next)
        }
    }
    fun drop() { if (dragging == null) return; dragging = null; dy = 0f; save() }
    SettingsFrame {
        SettingsHead(Modifier.parity("settings-homescreen.back", Contract.label("settings-homescreen.back")).clip(CircleShape).clickable(onClick = onBack), R.drawable.np_back,
            "Home Screen", Modifier.parity("settings-homescreen.heading"))
        Block {
            SetupRow("Greeting", Modifier.parity("settings-homescreen.name"), null) {
                Toggle(prefs.greeting, Modifier.parity("settings-homescreen.greeting", Contract.label("settings-homescreen.greeting")).clickable {
                    prefs = setPagePref(context, "greeting", if (prefs.greeting) "off" else "on")
                })
            }
            SetupRow("Random Album", Modifier.parity("settings-homescreen.name"), null) {
                Toggle(prefs.random, Modifier.parity("settings-homescreen.random", Contract.label("settings-homescreen.random")).clickable {
                    prefs = setPagePref(context, "random", if (prefs.random) "off" else "on")
                })
            }
            SetupRow("Album of the Day", Modifier.parity("settings-homescreen.name"), null) {
                Toggle(prefs.aotd, Modifier.parity("settings-homescreen.aotd", Contract.label("settings-homescreen.aotd")).clickable {
                    prefs = setPagePref(context, "aotd", if (prefs.aotd) "off" else "on")
                })
            }
            SetupNote("The greeting at the top of Home, and the Random Album and Album of the Day tiles under it. On this device only.", Modifier.parity("settings-homescreen.note"))
        }
        Block {
            SetupNote("Hold the handle to drag a row into a new position. A row switched off is not fetched at all — it does no work in the background.", Modifier.parity("settings-homescreen.note"))
            Column(Modifier.padding(top = 8.dp)) {
                val listed = HomeRowsSettings.listed(rows, downloaded)
                for ((i, row) in listed.withIndex()) key(row.id) {
                    val held = dragging == row.id
                    HomeRowItem(row, i == listed.lastIndex, held, if (held) dy else 0f,
                        Modifier.onSizeChanged { heights[row.id] = it.height },
                        Modifier.parity("settings-homescreen.grip", Contract.label("settings-homescreen.grip", 0, row.name)).pointerInput(row.id) {
                            detectDragGestures(onDragStart = { dragging = row.id; dy = 0f }, onDragEnd = { drop() }, onDragCancel = { drop() }) { change, amount ->
                                change.consume(); drag(row.id, amount.y)
                            }
                        },
                        Modifier.parity("settings-homescreen.row-on", Contract.label("settings-homescreen.row-on", 0, row.name)).clickable(enabled = row.switchable) {
                            val next = HomeRowsSettings.switched(rows.toList(), row.id, !row.on)
                            rows.clear(); rows.addAll(next); save()
                        })
                }
            }
        }
    }
}

/**
 * A row of the list (.home-row-item): its handle (32 by 44, the dots faint), 12, its name in 15 (and why, 12 dim, under it),
 * 12, its switch; 10 and 2 in, a hairline under all but the last. Held, it rides [dy] over the rest, raised on the lighter
 * ground. A row whose setting is off is dimmed to .6, its switch off and still.
 */
@Composable
private fun HomeRowItem(row: ListedRow, last: Boolean, held: Boolean, dy: Float, measure: Modifier, grip: Modifier, switch: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val off = row.why != null && row.why != HomeRowsSettings.HELD
    Column(measure.zIndex(if (held) 1f else 0f).graphicsLayer { translationY = dy }
        .let { if (held) it.shadow(10.dp, shape).background(paletteColor(Palette.BG_ELEV_2), shape) else it.background(paletteColor(Palette.BG_ELEV)) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val faint = paletteColor(Palette.TEXT_FAINT)
            Canvas(grip.size(32.dp, 44.dp).alpha(if (off) 0.6f else 1f)) {
                // The page's ⠿ in 18: two columns of three dots.
                val r = 1.3.dp.toPx(); val gx = 3.2.dp.toPx(); val gy = 4.4.dp.toPx()
                for (cx in listOf(-gx, gx)) for (cy in listOf(-gy, 0f, gy)) drawCircle(faint, r, Offset(size.width / 2 + cx, size.height / 2 + cy))
            }
            Column(Modifier.weight(1f).alpha(if (off) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, Modifier.parity("settings-homescreen.row-name"), color = paletteColor(Palette.TEXT), fontSize = 15.sp, lineHeight = 21.sp,
                    maxLines = if (row.why != null) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                row.why?.let { Text(it, Modifier.parity("settings-homescreen.row-why"), color = paletteColor(Palette.TEXT_DIM), fontSize = 12.sp, lineHeight = 15.6.sp) }
            }
            Toggle(row.on, switch)
        }
        if (!last && !held) Box(Modifier.fillMaxWidth().height(1.dp).background(paletteColor(Palette.PANEL_HAIRLINE)))
    }
}
