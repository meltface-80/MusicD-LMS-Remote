package com.musicd.mallard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AudioDevices
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.DeviceRow
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Settings → Audio Devices (owner request: Playback and Player settings "condensed into one settings page called Audio
 * Devices like Mandarin"): Mandarin's page — the zone the screens play in at the top, then every Lyrion player as a card
 * (its kind's icon, its name, its model, what it is doing, ›). A tap opens the player's own page (SettingsDevice.kt),
 * Back returns to the list. Read again every ten seconds while shown.
 */

private val ICONS = mapOf("phone" to R.drawable.dv_phone, "upnp" to R.drawable.dv_upnp, "local" to R.drawable.dv_local)

@Composable
internal fun AudioDevicesPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    val open = device
    if (open != null) {
        BackHandler { device = null }
        DevicePane(open, client, { device = null }, info)
    } else DeviceListPane(client, onBack, { device = it }, info)
}

@Composable
private fun DeviceListPane(client: () -> ServerClient?, onBack: () -> Unit, onOpen: (String) -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<DeviceRow>?>(null) }
    var zone by remember { mutableStateOf(NativeShell.zone.ifEmpty { Store.zone(context).orEmpty() }) }
    LaunchedEffect(Unit) {
        while (true) {
            untilOnScreen()
            val c = client()
            if (c != null) withContext(Dispatchers.IO) { runCatching { AudioDevices.parse(c.getJson("/api/zones"), PhoneZone.ownId(context)) }.getOrNull() }?.let { rows = it }
            delay(10_000)
        }
    }
    SettingsFrame {
        PaneHead("Audio Devices", onBack)
        val list = rows.orEmpty()
        Block {
            val zones = list.map { Choice(it.id, it.name) }
            SetupRow("Zone / output", Modifier, Modifier.clickable { info(AudioDevices.ZONE_INFO) }) {
                SelectBox(choiceWords(zones, zone).ifEmpty { "None" }, zones, Modifier, Modifier.clickable {
                    if (zones.isNotEmpty()) pickChoice(context, zones, zone) { id -> zone = id; NativeShell.roomChosen(id) }
                })
            }
        }
        SettingsDivider(18, 18)
        Block {
            SetupRow("Devices", Modifier, Modifier.clickable { info(AudioDevices.DEVICES_INFO) }) {}
            if (rows == null) SetupNote("Asking Lyrion…", Modifier)
            else if (list.isEmpty()) SetupNote("Lyrion knows no players yet. Switch one on, or play to This phone.", Modifier)
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in list) DeviceRowView(r, r.id == zone) { onOpen(r.id) }
            }
        }
    }
}

/** A device's card (Mandarin's DeviceRowView): its icon in a tile, its name and model, what it is doing, ›. */
@Composable
private fun DeviceRowView(r: DeviceRow, chosen: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(paletteColor(if (chosen) Palette.ACCENT_SOFT else Palette.BG_ELEV_2))
            .border(1.dp, paletteColor(if (chosen) Palette.ACCENT else Palette.BORDER), shape).clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(paletteColor(Palette.BG_ELEV))
            .alpha(if (r.stateShown == "Offline") 0.55f else if (r.off) 0.5f else 1f), contentAlignment = Alignment.Center) {
            Icon(painterResource(ICONS[r.kind] ?: R.drawable.dv_upnp), contentDescription = null, tint = paletteColor(Palette.ACCENT_TEXT), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.name, color = paletteColor(Palette.TEXT), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.sub.isNotEmpty()) Text(r.sub, color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(r.stateShown.uppercase(), color = paletteColor(if (r.playing) Palette.ACCENT_TEXT else Palette.TEXT_FAINT),
            fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.W700, letterSpacing = 0.08.em)
        Text("›", color = paletteColor(Palette.TEXT_FAINT), fontSize = 20.sp)
    }
}

/** A device page's section title (Mandarin's DeviceTitle): its name in small capitals, faint. */
@Composable
internal fun DeviceTitle(text: String, info: Modifier? = null) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W700, letterSpacing = 0.06.em)
        if (info != null) Box(info.size(32.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
            Text("ⓘ", color = paletteColor(Palette.TEXT_FAINT), fontSize = 16.sp)
        }
    }
}
