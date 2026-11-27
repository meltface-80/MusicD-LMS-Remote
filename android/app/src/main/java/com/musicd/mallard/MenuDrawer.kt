package com.musicd.mallard

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Export
import com.musicd.mallard.client.Menu
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShellRoute
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The side menu over the native screens (the Rooms group of old/plan-android-native.md; bug list item 67), opened
 * by the row's Menu ([NativeShell.menuOpen]) and drawn as the page's (docs/specs/screens.json, menu; style.css, .menu-drawer):
 * the drawer from the left over the screen dimmed, Mandarin's mark and name with the red power button and its Restart and
 * Shut down, the Offline switch, the items (the ones that come and go as the server says), Rescan library with how old the
 * library is, and Settings. A tap beside it closes it, a tap on a blank part of it doesn't. Composed by the shell, whose
 * coroutines it uses, so a rescan's last word comes when the menu has long gone.
 */
@Composable
internal fun MenuOver(nav: NavHostController, client: () -> ServerClient?) {
    val open by NativeShell.menuOpen
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Drawn while open and while it slides away after (item 199): it went at once, and the screen under it showed for a
    // moment before the one chosen came. Now it goes once the screen chosen has been drawn under it.
    var drawn by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) drawn = true }
    // Back closes the menu first, over any screen (item 158: it closed the screen under it, the menu left open). Composed
    // only while open, so it comes after the screen's own and is asked first.
    if (open) BackHandler { NativeShell.menuOpen.value = false }
    if (open || drawn) MenuDrawer(WallPrefs.parse(NativeShell.prefs).menu, client, scope, open, onGone = { drawn = false }, onClose = { NativeShell.menuOpen.value = false },
        onItem = { if (NativeShell.menuOpen.value) menuTap(nav, it, context) })
    PowerOver(client)
}

/**
 * An item of the menu chosen ([what], its key in the contract): the menu closes and, as the row's buttons do, every native
 * screen over Home with it; Listen later, Pitchfork, Labels, Qobuz, Tidal and Settings open natively over Home;
 * Restart and Shut down ask and show the server's time away natively (PowerOver.kt, item 96); Shelf opens natively
 * too (item 207: it was the server's page in the phone's browser from item 111).
 */
internal fun menuTap(nav: NavHostController, what: String, context: android.content.Context) {
    AppLog.line("MENU", "item $what")
    NativeShell.menuOpen.value = false
    // Restart and Shut down: asked, and the server's time away shown, by the app itself (item 96).
    if (what.startsWith("power-")) { PowerKept.asking.value = what; return }
    // Opened over Home in one step, the screens between taken away with it (item 199: Home was drawn between them).
    val z = NativeShell.zone
    when (what) {
        "later" -> overHome(nav, ShellRoute.wall("later", z, ""))
        "pitchfork" -> overHome(nav, ShellRoute.pitchfork("", z))
        "qobuz", "tidal" -> overHome(nav, ShellRoute.serviceBrowser(what, z))
        "settings" -> overHome(nav, ShellRoute.settings())
        "shelf" -> overHome(nav, ShellRoute.shelf(z))
    }
}

/** The page's slide (menuSlideIn: .24s, cubic-bezier(.2,.8,.2,1)); out, the same curve in .2s (item 199). */
private val SLIDE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private const val SLIDE_OUT_MS = 200
/** The Offline switch's own movement (style.css, .18s), seen through before the page loads again (menu-drawer.js OFFLINE_SWITCH_MS). */
private const val OFFLINE_SWITCH_MS = 220L

@Composable
private fun MenuDrawer(zoom: Double, client: () -> ServerClient?, scope: CoroutineScope, open: Boolean, onGone: () -> Unit, onClose: () -> Unit, onItem: (String) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // min(82vw, 320px); its insides at the UI Settings' menu size (style.css: .menu-drawer > * { zoom: var(--ui-menu) }).
    val width = minOf(LocalConfiguration.current.screenWidthDp * 0.82f, 320f).dp
    val widthPx = with(density) { width.toPx() }
    // Opened as it was last seen, then following the server (item 122: it opened as the markup has it, Labels and Shelf
    // drawn, then took them away and put the rescan line in as the server answered, at each opening).
    val kept = remember { Store.menuKept(context) }
    var sub by remember { mutableStateOf(Menu.rescanSub(Menu.keptStatus(kept), System.currentTimeMillis())) }
    var items by remember { mutableStateOf(Menu.keptItems(kept) ?: Menu.UNKNOWN) }
    var offline by remember { mutableStateOf(Store.offlineMode(context)) }
    var power by remember { mutableStateOf(false) }
    var powerInfo by remember { mutableStateOf<JSONObject?>(null) }
    // Export library (item 436): where to save the file asked first, the menu left open under the dialog so its answer
    // reaches this drawer; closed once there is one.
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        onClose()
        if (uri != null) exportTo(context, client, scope, uri)
    }
    val slide = remember { Animatable(-1f) }
    // In as it opens; out once closed, two frames on, so a screen chosen from it is drawn under it first (item 199).
    LaunchedEffect(open) {
        if (open) slide.animateTo(0f, tween(240, easing = SLIDE))
        else { withFrameNanos { }; withFrameNanos { }; slide.animateTo(-1f, tween(SLIDE_OUT_MS, easing = SLIDE)); onGone() }
    }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        val (status, shown) = withContext(Dispatchers.IO) {
            fun ask(path: String) = async { runCatching { c.getJson(path) }.getOrNull() }
            val st = ask("/api/status"); val q = ask("/api/settings/qobuz")
            val t = ask("/api/settings/tidal"); val sh = ask("/api/settings/shelf")
            st.await() to Menu.items(q.await(), t.await(), sh.await(), items)
        }
        if (shown != items) AppLog.line("MENU", "its rows changed as the server answered: $items, then $shown", anomaly = kept != null)
        sub = Menu.rescanSub(status ?: Menu.keptStatus(kept), System.currentTimeMillis()); items = shown
        NavShelf.on.value = shown.shelf   // the row along the foot's Shelf too (item 210)
        Store.setMenuKept(context, Menu.keep(shown, status ?: Menu.keptStatus(kept)))
    }
    LaunchedEffect(power) {
        val c = client() ?: return@LaunchedEffect
        if (power) powerInfo = withContext(Dispatchers.IO) { runCatching { c.getJson("/api/system/power") }.getOrNull() } ?: powerInfo
    }
    // Taps beside the drawer close it; on a blank part of it, they only close the power drop-down (the items take their own).
    Box(Modifier.fillMaxSize().parity("menu.close", Contract.label("menu.close")).pointerInput(widthPx, open) {
        detectTapGestures { at -> if (!open) return@detectTapGestures; if (at.x > widthPx) onClose() else power = false }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f + slide.value }.background(Color.Black.copy(alpha = 0.5f)))   // the dim comes and goes with it
        val border = paletteColor(Palette.BORDER)
        Box(Modifier.width(width).fillMaxHeight().graphicsLayer { translationX = slide.value * size.width }
            // box-shadow: 8px 0 40px rgba(0,0,0,.4), off its right edge; border-right: 1px var(--border).
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent), size.width, size.width + 48.dp.toPx()), Offset(size.width, 0f), Size(48.dp.toPx(), size.height))
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind { drawRect(border, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * zoom.toFloat(), density.fontScale)) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column {
                        MenuHead(power) { power = !power }
                        MenuList(items, offline, sub,
                            onOffline = {
                                val to = !offline
                                offline = to
                                val activity = context as? MainActivity
                                if (activity != null) scope.launch { delay(OFFLINE_SWITCH_MS); switchOffline(activity, to) }
                            },
                            onRescan = { onClose(); rescan(context, client, scope) },
                            onExport = { saveAs.launch(Export.fileName(java.time.LocalDate.now())) },
                            onItem = onItem)
                    }
                    if (power) PowerPop(powerInfo, Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 14.dp), onItem)
                }
            }
        }
    }
}

/** Mandarin's mark and name (Young Serif, 20), and the power button: red, deeper red while its drop-down is open. */
@Composable
private fun MenuHead(open: Boolean, onPower: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.parity("menu.brand"), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.menu_brand), contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("Mandarin", color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = 20.sp, lineHeight = 28.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        val red = if (open) Color(0xFFB71C1C) else Color(0xFFD32F2F)
        Box(Modifier.parity("menu.power", Contract.label("menu.power")).size(40.dp).clip(CircleShape).background(red).border(1.dp, red, CircleShape).clickable(onClick = onPower),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.mn_power), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** The power button's drop-down: Restart; Shut down where the server can. */
@Composable
private fun PowerPop(info: JSONObject?, modifier: Modifier, onItem: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(190.dp).shadow(12.dp, shape).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(6.dp)) {
        Text(Contract.label("menu.power-restart"), Modifier.parity("menu.power-restart", Contract.label("menu.power-restart")).then(POWER_ITEM).clickable { onItem("power-restart") }.padding(horizontal = 12.dp, vertical = 11.dp),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
        if (Menu.canShutDown(info)) Text(Contract.label("menu.power-shutdown"), Modifier.parity("menu.power-shutdown", Contract.label("menu.power-shutdown")).then(POWER_ITEM).clickable { onItem("power-shutdown") }
            .padding(horizontal = 12.dp, vertical = 11.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
    }
}

/** A drop-down item: the drop-down's width, its corners 8 when pressed. */
private val POWER_ITEM = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))

/** The list: Offline mode, a line, the items, a line, Rescan library, a line, Export library and Settings (style.css: .menu-list, 4 8 16, 2 apart). */
@Composable
private fun MenuList(items: Menu.Items, offline: Boolean, sub: String, onOffline: () -> Unit, onRescan: () -> Unit, onExport: () -> Unit, onItem: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OfflineRow(offline, onOffline)
        MenuSep()
        MenuItem(Modifier.parity("menu.pitchfork", Contract.label("menu.pitchfork")).clip(ITEM).clickable { onItem("pitchfork") }, R.drawable.mn_pitchfork, Contract.label("menu.pitchfork"))
        if (items.qobuz) MenuItem(Modifier.parity("menu.qobuz", Contract.label("menu.qobuz")).clip(ITEM).clickable { onItem("qobuz") }, R.drawable.mn_qobuz, Contract.label("menu.qobuz"))
        if (items.tidal) MenuItem(Modifier.parity("menu.tidal", Contract.label("menu.tidal")).clip(ITEM).clickable { onItem("tidal") }, R.drawable.src_tidal, Contract.label("menu.tidal"))
        // Shelf is the server's whole library, a page of its own: not offline.
        if (items.shelf && !offline) MenuItem(Modifier.parity("menu.shelf", Contract.label("menu.shelf")).clip(ITEM).clickable { onItem("shelf") }, R.drawable.mn_shelf, Contract.label("menu.shelf"))
        MenuItem(Modifier.parity("menu.later", Contract.label("menu.later")).clip(ITEM).clickable { onItem("later") }, R.drawable.mn_later, Contract.label("menu.later"))
        MenuSep()
        MenuItem(Modifier.parity("menu.rescan", Contract.label("menu.rescan")).clip(ITEM).clickable(onClick = onRescan), R.drawable.mn_rescan, Contract.label("menu.rescan"), sub)
        MenuSep()
        // The library's own files as a CSV for Mandarin Migration (item 436): where to save it asked first.
        MenuItem(Modifier.parity("menu.export", Contract.label("menu.export")).clip(ITEM).clickable(onClick = onExport), R.drawable.mn_export, Contract.label("menu.export"))
        MenuItem(Modifier.parity("menu.settings", Contract.label("menu.settings")).clip(ITEM).clickable { onItem("settings") }, R.drawable.mn_settings, Contract.label("menu.settings"))
    }
}

/** A line between the groups (style.css: .menu-sep, 1px of the border colour, 8 in from every side). */
@Composable
private fun MenuSep() {
    Box(Modifier.fillMaxWidth().padding(8.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
}

/** An item's shape when pressed (style.css: .menu-item, radius 10). */
private val ITEM = RoundedCornerShape(10.dp)

/** An item ([modifier], its key and its tap): its icon (22, in the brass) and its [word] (15), 12 all round, 14 between, no wider than they are; its live line under the word if it has one. */
@Composable
private fun MenuItem(modifier: Modifier, icon: Int, word: String, sub: String = "") {
    Row(modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(word, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, Modifier.parity("menu.rescan-sub"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.6.sp)
        }
    }
}

/** Offline mode: the row the drawer's width, its line under it, its switch at the right (46 by 28; on, the brass with the thumb white and over). */
@Composable
private fun OfflineRow(on: Boolean, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.parity("menu.offline", Contract.label("menu.offline")).fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onTap).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.mn_offline), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        // One line: "Offline Mode - On" or "- Off" (item 98).
        Text(Menu.offlineSub(on), Modifier.parity("menu.offline-sub").weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
        val track = if (on) accent else paletteColor(Palette.BG_ELEV_2)
        Box(Modifier.width(46.dp).height(28.dp).clip(CircleShape).background(track).border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 3.dp),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = if (on) 18.dp else 0.dp).size(22.dp).clip(CircleShape).background(if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

/**
 * The Offline mode switch (item 98's row): the app goes onto the phone's music or back onto the server, the native screens
 * staying in front either way (item 109; before, the page was loaded again under a picture of the screen), the menu closed.
 */
private fun switchOffline(activity: MainActivity, on: Boolean) {
    Store.setOfflineMode(activity, on)
    NativeShell.menuOpen.value = false
    activity.offlineModeChanged()
}

/** Rescan library, as the page's rescanLibrary: its toasts, and a scan still going waited for, then how it ended said. */
/**
 * Export library (item 436): the server's CSV of the library's own files written where the save dialog was pointed
 * ([uri]), then what went in, counted from the file, as the page says it.
 */
private fun exportTo(context: Context, client: () -> ServerClient?, scope: CoroutineScope, uri: Uri) {
    val c = client() ?: return
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    toast(Export.EXPORTING)
    scope.launch {
        val bytes = withContext(Dispatchers.IO) { runCatching { c.exportCsv() }.getOrNull() }
        if (bytes == null) { toast(Export.FAILED); return@launch }
        val written = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess }
        if (!written) { toast(Export.NOT_WRITTEN); return@launch }
        toast(Export.doneText(Export.counts(String(bytes, Charsets.UTF_8)), Export.fileName(java.time.LocalDate.now())))
    }
}

private fun rescan(context: Context, client: () -> ServerClient?, scope: CoroutineScope) {
    val c = client() ?: return
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    toast("Scanning your music folder…")
    scope.launch {
        // The server answers within 8 s, or says it goes on (ScanRoutes.cs).
        val r = withContext(Dispatchers.IO) { runCatching { Menu.rescan(c.post("/api/library/rescan", JSONObject(), 20_000)) }.getOrNull() }
        if (r == null) { toast("Rescan failed"); return@launch }
        toast(r.text)
        if (!r.going) return@launch
        val until = System.currentTimeMillis() + 60 * 60 * 1000L
        while (System.currentTimeMillis() < until) {
            delay(2_500)
            val st = withContext(Dispatchers.IO) { runCatching { c.getJson("/api/status") }.getOrNull() } ?: continue
            val end = Menu.scanEnded(st) ?: continue
            toast(end.text)
            return@launch
        }
    }
}
