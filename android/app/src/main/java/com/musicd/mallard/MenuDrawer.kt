package com.musicd.mallard

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.musicd.mallard.client.CollectionRoute
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Features
import com.musicd.mallard.client.features
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShellRoute
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The side menu over the native screens, opened by the row's Menu ([NativeShell.menuOpen]) and drawn as Mandarin's
 * drawer: from the left over the screen dimmed, Mallard's mark and name, then MusicD LMS Remote's own list — Home,
 * Random albums, Favourites, Apps and Radio (every LMS plugin), Pitchfork; Rescan library; Settings. A tap beside it closes it. Composed by the shell, whose
 * coroutines it uses, so a rescan's last word comes when the menu has long gone.
 */
@Composable
internal fun MenuOver(nav: NavHostController, client: () -> ServerClient?) {
    val open by NativeShell.menuOpen
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Drawn while open and while it slides away after: it goes once the screen chosen has been drawn under it.
    var drawn by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) drawn = true }
    if (open) BackHandler { NativeShell.menuOpen.value = false }
    if (open || drawn) MenuDrawer(WallPrefs.parse(NativeShell.prefs).menu, client, scope, open, onGone = { drawn = false }, onClose = { NativeShell.menuOpen.value = false },
        onItem = { if (NativeShell.menuOpen.value) menuTap(nav, it, context) })
}

/** An item of the menu chosen ([what]): the menu closes and, as the row's buttons do, the screen opens over Home. */
internal fun menuTap(nav: NavHostController, what: String, context: android.content.Context) {
    AppLog.line("MENU", "item $what")
    NativeShell.menuOpen.value = false
    val z = NativeShell.zone
    when (what) {
        "home" -> navTap(nav, "home")
        "random" -> overHome(nav, ShellRoute.wall("random", z, ""))
        "favourites" -> overHome(nav, ShellRoute.wall("favourites", z, ""))
        "apps" -> overHome(nav, ShellRoute.apps("app", z))
        "shelf" -> overHome(nav, ShellRoute.shelf(z))
        "radio" -> overHome(nav, ShellRoute.apps("radio", z))
        "pitchfork" -> overHome(nav, ShellRoute.pitchfork("", z))
        "picks" -> overHome(nav, ShellRoute.picks(z))
        "merges" -> overHome(nav, CollectionRoute.merges(z))
        "liveplaylists" -> overHome(nav, CollectionRoute.livePlaylists(z))
        "playlists" -> overHome(nav, CollectionRoute.playlists(z))
        "import" -> overHome(nav, CollectionRoute.import(z))
        "settings" -> overHome(nav, ShellRoute.settings())
    }
}

/** The page's slide (menuSlideIn: .24s, cubic-bezier(.2,.8,.2,1)); out, the same curve in .2s. */
private val SLIDE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private const val SLIDE_OUT_MS = 200

@Composable
private fun MenuDrawer(zoom: Double, client: () -> ServerClient?, scope: CoroutineScope, open: Boolean, onGone: () -> Unit, onClose: () -> Unit, onItem: (String) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // min(82vw, 320px); its insides at the UI Settings' menu size.
    val width = minOf(LocalConfiguration.current.screenWidthDp * 0.82f, 320f).dp
    val widthPx = with(density) { width.toPx() }
    val slide = remember { Animatable(-1f) }
    // In as it opens; out once closed, two frames on, so a screen chosen from it is drawn under it first.
    LaunchedEffect(open) {
        if (open) slide.animateTo(0f, tween(240, easing = SLIDE))
        else { withFrameNanos { }; withFrameNanos { }; slide.animateTo(-1f, tween(SLIDE_OUT_MS, easing = SLIDE)); onGone() }
    }
    // The LMS server's name under Rescan library, as the server reports it.
    var lms by remember { mutableStateOf("") }
    // The server's switches, read as the page's menu reads them (/api/settings/features): the Shelf row only while it is
    // on; Smart Picks unless it is switched off.
    var features by remember { mutableStateOf(Features(setOf("shelf"))) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        lms = withContext(Dispatchers.IO) { runCatching { c.health().lms }.getOrNull() }.orEmpty()
        withContext(Dispatchers.IO) { runCatching { c.features() }.getOrNull() }?.let { features = it }
    }
    Box(Modifier.fillMaxSize().parity("menu.close", Contract.label("menu.close")).pointerInput(widthPx, open) {
        detectTapGestures { at -> if (!open) return@detectTapGestures; if (at.x > widthPx) onClose() }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f + slide.value }.background(Color.Black.copy(alpha = 0.5f)))   // the dim comes and goes with it
        val border = paletteColor(Palette.BORDER)
        Box(Modifier.width(width).fillMaxHeight().graphicsLayer { translationX = slide.value * size.width }
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent), size.width, size.width + 48.dp.toPx()), Offset(size.width, 0f), Size(48.dp.toPx(), size.height))
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind { drawRect(border, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * zoom.toFloat(), density.fontScale)) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column {
                        MenuHead()
                        MenuList(lms, features, onRescan = { onClose(); rescan(context, client, scope) }, onItem = onItem)
                    }
                }
            }
        }
    }
}

/** Mallard's mark and name (Young Serif, 20). */
@Composable
private fun MenuHead() {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.parity("menu.brand"), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.menu_brand), contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("Mallard", color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = 20.sp, lineHeight = 28.sp, maxLines = 1)
        }
    }
}

/** The list, as the web app's menu (public/index.html): Home, Random albums and the Shelf; the collections; Rescan; Settings. */
@Composable
private fun MenuList(lms: String, features: Features, onRescan: () -> Unit, onItem: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MenuItem(Modifier.clip(ITEM).clickable { onItem("home") }, R.drawable.tn_home, "Home")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("random") }, R.drawable.tn_reshuffle, "Random albums")
        if (features.on("shelf")) MenuItem(Modifier.clip(ITEM).clickable { onItem("shelf") }, R.drawable.mn_shelf, "Shelf")
        MenuSep()
        MenuItem(Modifier.clip(ITEM).clickable { onItem("favourites") }, R.drawable.np_heart, "Favourites")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("apps") }, R.drawable.mn_apps, "Apps")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("radio") }, R.drawable.np_device, "Radio")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("merges") }, R.drawable.mn_merged, "Merged albums")
        MenuItem(Modifier.parity("menu.pitchfork", Contract.label("menu.pitchfork")).clip(ITEM).clickable { onItem("pitchfork") }, R.drawable.mn_pitchfork, Contract.label("menu.pitchfork"))
        if (features.on("smartPicks")) MenuItem(Modifier.clip(ITEM).clickable { onItem("picks") }, R.drawable.mn_picks, "Smart Picks")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("liveplaylists") }, R.drawable.mn_live, "Dynamic Playlists")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("playlists") }, R.drawable.mn_playlists, "Playlists")
        MenuItem(Modifier.clip(ITEM).clickable { onItem("import") }, R.drawable.mn_import, "Import a playlist")
        MenuSep()
        MenuItem(Modifier.parity("menu.rescan", Contract.label("menu.rescan")).clip(ITEM).clickable(onClick = onRescan), R.drawable.mn_rescan, Contract.label("menu.rescan"), lms)
        MenuSep()
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

/** An item: its icon (22, in the brass) and its [word] (15), 12 all round, 14 between; its line under the word if it has one. */
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

/**
 * Rescan library: LMS rescans its own library (a "new and changed" scan), and the app's index follows by itself when the
 * scan ends — the server notices LMS's lastScan move. Said as the server's page says it.
 */
private fun rescan(context: Context, client: () -> ServerClient?, scope: CoroutineScope) {
    val c = client() ?: return
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { c.rescan() } }
        toast(if (r.isSuccess) "Rescan started — the library updates when LMS finishes" else (r.exceptionOrNull()?.message ?: "Rescan failed"))
    }
}
