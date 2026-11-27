package com.musicd.mallard

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LmsPlugin
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PluginList
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.acceptPluginRisk
import com.musicd.mallard.client.changePlugin
import com.musicd.mallard.client.plugins
import com.musicd.mallard.client.restartLms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → LMS server → Plugins: LMS's plugins — installed, offered by its repositories, updates — with Install,
 * Update, Remove, Enable / Disable, the plugin's own settings (in the app, LmsSettingsLink.kt), and Restart LMS to apply. The twin of the web app's
 * Plugins sheet (public/lms-plugins.js); both draw /api/lms/plugins, which drives LMS's own plugin page, and the server
 * says which buttons each plugin gets (core's LmsPlugin.can), so the two apps can't offer different things.
 */

private const val RISK_WORDS = "LMS plugins are written by other people, not by the LMS team, and run inside your server: they can reach the network, read your library and settings, and affect how well LMS runs. Install the ones you trust, check what they do, and remove any you stop using."

@Composable
internal fun PluginsPane(client: () -> ServerClient?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<PluginList?>(null) }
    var said by remember { mutableStateOf("Reading LMS's plugins…") }
    var tab by remember { mutableStateOf("installed") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var restarting by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var page by remember { mutableStateOf<LmsPlugin?>(null) }   // a plugin's own settings, open in the app
    val toast = { t: String -> setupToast(context, t) }

    suspend fun load() {
        val c = client() ?: return
        withContext(Dispatchers.IO) { runCatching { c.plugins() } }.fold({ list = it }, { list = null; said = Failure.said(it, "LMS didn't answer") })
    }
    LaunchedEffect(Unit) { load() }

    fun change(p: LmsPlugin, action: String) {
        val c = client() ?: return
        busy = p.name
        toast("${LmsPlugin.doing(action)} ${p.title}…")
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.changePlugin(p.name, action) } }.fold(
                { list = it; toast(if (it.restart) "${p.title}: done — restart LMS to apply it" else "${p.title}: done") },
                { toast(Failure.said(it, "LMS didn't take that")); load() })   // a refusal over LMS's warning: read again, which shows it
            busy = ""
        }
    }
    fun ask(p: LmsPlugin, action: String) {
        val question = when (action) {
            "remove" -> "Remove ${p.title} from LMS?"
            "disable" -> "Switch ${p.title} off? It stays installed; switch it on again any time."
            else -> null
        }
        if (question == null) change(p, action) else asking = question to { change(p, action) }
    }
    fun restart() {
        val c = client() ?: return
        restarting = true
        scope.launch {
            val asked = withContext(Dispatchers.IO) { runCatching { c.restartLms() } }
            if (asked.isFailure) { restarting = false; toast(Failure.said(asked.exceptionOrNull()!!, "LMS didn't answer")); return@launch }
            // LMS is gone a while (it loads every plugin again): ask until it answers, for two minutes at most.
            delay(6_000)
            val until = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < until) {
                val back = withContext(Dispatchers.IO) { runCatching { c.plugins() } }.getOrNull()
                if (back != null) { list = back; restarting = false; toast("LMS restarted"); return@launch }
                delay(3_000)
            }
            restarting = false
            toast("LMS hasn't come back yet — try again in a minute")
        }
    }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    page?.let { p -> LmsPage(p.settings, p.title, client) { page = null }; return }
    SettingsFrame {
        PaneHead("Plugins", onBack)
        val l = list
        when {
            l == null -> SetupNote(said, Modifier)
            l.risk -> RiskBlock {
                val c = client()
                if (c != null) scope.launch {
                    withContext(Dispatchers.IO) { runCatching { c.acceptPluginRisk() } }.fold({ list = it }, { toast(Failure.said(it, "LMS didn't answer")) })
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TabPill(l.installedTab(), tab == "installed", Modifier.weight(1f)) { tab = "installed" }
                    TabPill("Available", tab == "available", Modifier.weight(1f)) { tab = "available" }
                }
                FindField(query) { query = it }
                if (l.restart || restarting) RestartBlock(restarting) { asking = "Restart LMS now? Music stops for a moment while it starts again." to { restart() } }
                val groups = if (tab == "installed") l.installed(query) else l.availableByRepo(query)
                if (groups.isEmpty()) SetupNote(when {
                    query.isNotBlank() -> "Nothing matches."
                    tab == "installed" -> "No plugins installed."
                    else -> "LMS's repositories offer nothing more to install."
                }, Modifier)
                for ((title, ps) in groups) {
                    Text(title.uppercase(), Modifier.padding(top = 16.dp, bottom = 4.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 0.7.sp)
                    for (p in ps) PluginRow(p, client, busy, restarting, { a -> ask(p, a) }, { if (p.settings != null) page = p }, { p.homepage?.let { open(it) } })
                }
            }
        }
    }
    asking?.let { (q, yes) -> ConfirmDialog(q) { ok -> asking = null; if (ok) yes() } }
}

@Composable
private fun TabPill(words: String, on: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(modifier.heightIn(min = 38.dp).clip(shape).background(paletteColor(if (on) Palette.ACCENT else Palette.BG_ELEV))
        .border(1.dp, paletteColor(if (on) Palette.ACCENT else Palette.BORDER), shape).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Text(words, color = paletteColor(if (on) Palette.ON_ACCENT else Palette.TEXT_DIM), fontSize = 14.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
private fun FindField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    BasicTextField(query, onChange, Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
        .border(1.dp, paletteColor(Palette.BORDER), shape).padding(horizontal = 14.dp, vertical = 10.dp),
        textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 14.sp), singleLine = true, cursorBrush = SolidColor(paletteColor(Palette.ACCENT)),
        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Find a plugin", color = paletteColor(Palette.TEXT_FAINT), fontSize = 14.sp); inner() } })
}

@Composable
private fun RiskBlock(onAccept: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Plugins are third-party software", color = paletteColor(Palette.TEXT), fontSize = 16.sp, fontWeight = FontWeight.W700)
        Text(RISK_WORDS, color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp, lineHeight = 20.sp)
        PillButton("I understand — show plugins", true, Modifier.clickable(onClick = onAccept))
    }
}

@Composable
private fun RestartBlock(restarting: Boolean, onRestart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (restarting) "LMS is restarting…" else "LMS applies plugin changes when it restarts.", color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp)
        if (!restarting) PillButton("Restart LMS", true, Modifier.clickable(onClick = onRestart))
    }
    SettingsDivider(4, 4)
}

/** A button of the plugin list's: brass for what adds (Install, Update, Restart), the raised ground for the rest. */
@Composable
private fun PillButton(words: String, primary: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.clip(shape).background(paletteColor(if (primary) Palette.ACCENT else Palette.BG_ELEV_2))
        .border(1.dp, paletteColor(if (primary) Palette.ACCENT else Palette.BORDER), shape).then(modifier).heightIn(min = 34.dp)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(words, color = paletteColor(if (primary) Palette.ON_ACCENT else Palette.TEXT), fontSize = 13.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginRow(p: LmsPlugin, client: () -> ServerClient?, busy: String, restarting: Boolean, onAction: (String) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            AppPicture(p.imageKey, p.imagePath, 40.dp, client)
            if (p.imageKey == null && p.imagePath == null) Text(p.title.trim().take(1).uppercase(), Modifier.align(Alignment.Center), color = paletteColor(Palette.TEXT_DIM), fontWeight = FontWeight.W700)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(p.title, Modifier.weight(1f, fill = false), color = paletteColor(Palette.TEXT), fontSize = 15.sp, fontWeight = FontWeight.W600)
                if (p.version.isNotEmpty()) Text(" v${p.version}", color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp)
            }
            p.error?.let { Text(it, color = paletteColor(Palette.DANGER), fontSize = 13.sp) }
            if (p.desc.isNotEmpty() && p.desc != p.error) Text(p.desc, color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val by = listOf(p.creator, if (p.builtIn) "Part of LMS" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            if (by.isNotEmpty()) Text(by, color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (busy == p.name) Text("Working…", Modifier.padding(top = 6.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp)
            else FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val idle = busy.isEmpty() && !restarting
                for (a in p.can) PillButton(LmsPlugin.ACTIONS[a] ?: a, a == "install" || a == "update", if (idle) Modifier.clickable { onAction(a) } else Modifier)
                if (p.settings != null && p.state == "enabled") PillButton("Settings", false, Modifier.clickable(onClick = onSettings))
                if (p.homepage != null) Box(Modifier.heightIn(min = 34.dp).clickable(onClick = onAbout).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text("About", color = paletteColor(Palette.ACCENT_TEXT), fontSize = 13.sp)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(paletteColor(Palette.BORDER)))
}
