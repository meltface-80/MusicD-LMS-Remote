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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.SettingsLists
import com.musicd.mallard.client.SettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Settings, natively: the layout is MusicD LMS Remote's own Settings (the server page's list and panes, section for
 * section, the owner's choice), drawn in Mandarin's colours and type. ✕ and Settings at the head, a row a section (its
 * brass icon, its name and the line under it), the server's version and View on GitHub at the foot; each pane under its
 * Back. [pane]: "home", or a pane of SettingsMallard.kt.
 */
@Composable
internal fun SettingsScreen(pane: String, client: () -> ServerClient?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
    if (pane.isEmpty() || pane == "home") SettingsHome(client, onBack, onOpen)
    else SettingsPane(pane, client, onBack)
}

/** Settings' foot as last read. */
private var lastVersion = com.musicd.mallard.client.SettingsKept.last("/api/update/status") { SettingsLists.version(it) } ?: "MusicD LMS Remote"

@Composable
private fun SettingsHome(client: () -> ServerClient?, onClose: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    // The version as last read, so the foot doesn't change as Settings opens; then the server's.
    var version by rememberSaveable { mutableStateOf(lastVersion) }
    LaunchedEffect(Unit) {
        val c = client() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { SettingsLists.version(c.updateStatus()) }.getOrNull() }?.let { version = it; lastVersion = it }
    }
    SettingsFrame(scroll = false) {
        SettingsHead(Modifier.parity("settings.close", Contract.label("settings.close")).clip(CircleShape).clickable(onClick = onClose), R.drawable.np_close,
            "Settings", Modifier.parity("settings.heading"), below = 14)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (row in SettingsLists.HOME) SettingsNavRow(row, Modifier.parity("settings.item", Contract.label("settings.item", 0, row.title)).clip(RoundedCornerShape(10.dp)).clickable { open(row.pane) })
        }
        // The foot: the version on the left, View on GitHub on the right, 14 over them.
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(version, Modifier.parity("settings.version").weight(1f, fill = false), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.width(16.dp))
            val pill = RoundedCornerShape(999.dp)
            Row(Modifier.parity("settings.repo", Contract.label("settings.repo")).clip(pill).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), pill)
                .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsLists.REPO))) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.st_github), contentDescription = null, tint = paletteColor(Palette.TEXT_DIM), modifier = Modifier.size(16.dp))
                Text("View on GitHub", color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** A row of the Settings list (.settings-nav-item): its 22 brass icon, 14 on, its name in 15/500 and the line under it in 12 dim. */
@Composable
internal fun SettingsNavRow(row: SettingsRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(painterResource(ICONS[row.pane] ?: R.drawable.st_setup), contentDescription = null, tint = paletteColor(Palette.ACCENT), modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, color = paletteColor(Palette.TEXT), fontSize = 15.sp, lineHeight = 18.75.sp, fontWeight = FontWeight.W500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.desc.isNotEmpty()) Text(row.desc, color = paletteColor(Palette.TEXT_DIM), fontSize = 12.sp, lineHeight = 15.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.st_caret), contentDescription = null, tint = paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f })
    }
}

private val ICONS = mapOf("playback" to R.drawable.st_audio, "player" to R.drawable.np_device, "labels" to R.drawable.st_scanner, "metadata" to R.drawable.st_services,
    "homescreen" to R.drawable.st_homescreen, "smartpicks" to R.drawable.st_smartpicks, "lms" to R.drawable.st_setup, "display" to R.drawable.st_display, "shelf" to R.drawable.mn_shelf, "away" to R.drawable.st_away,
    "appearance" to R.drawable.st_ui, "system" to R.drawable.st_updates)

/**
 * A Settings screen's ground (#settings-overlay > .settings-sheet): the raised colour over the whole screen, 20 in at
 * the sides, 14 at the top and 16 at the foot; its insides scrolled ([scroll]) unless they keep a foot of their own.
 */
@Composable
internal fun SettingsFrame(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(paletteColor(Palette.BG_ELEV)).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 16.dp), content = content)
    }
}

/**
 * A page's head (.settings-pane-head): the brass disc, 40 round with its 20 glyph dark on it ([disc], its key and tap;
 * [icon]), and the title in the serif, 8 apart, [below] under (18, the page having no line under it since item 101; the list's own head, 14).
 */
@Composable
internal fun SettingsHead(disc: Modifier, icon: Int, title: String, heading: Modifier, below: Int = 18) {
    Row(Modifier.fillMaxWidth().padding(bottom = below.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(disc.size(40.dp).background(paletteColor(Palette.ACCENT), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(20.dp))
        }
        Text(title, heading, color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontSize = 22.sp, lineHeight = 31.sp, letterSpacing = 0.01.em)
    }
}


/** A rule between two blocks of a page (.settings-divider), and the room round it. */
@Composable
internal fun SettingsDivider(above: Int = 22, below: Int = 22) {
    Spacer(Modifier.height(above.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(paletteColor(Palette.BORDER)))
    Spacer(Modifier.height(below.dp))
}
