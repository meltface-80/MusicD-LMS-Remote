package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.musicd.mallard.client.ServerClient
import org.json.JSONObject

/*
 * Settings → Shelf (as Mandarin's, and the web app's Settings → Shelf): the Shelf switched on or off. The switch is the
 * server's (/api/settings/shelf, lib/shelf-routes.js), so the web app and Mallard show Shelf in their menus together.
 */
@Composable
internal fun ShelfPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("shelf") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var known by remember { mutableStateOf(kept != null) }
    LaunchedEffect(Unit) { readSetting(client, "shelf")?.let { on = it.optBoolean("enabled"); known = true } }
    fun save() = saveSetting(scope, context, client, "shelf", JSONObject().put("enabled", on), if (on) "Shelf on — it's in the menu" else "Shelf off", undo = { on = !on }) { j ->
        if (j.has("enabled")) on = j.optBoolean("enabled")
    }
    SettingsFrame {
        PaneHead("Shelf", onBack)
        Block {
            SetupRow("Shelf", Modifier, Modifier.clickable { info("A full-screen way to browse the whole library like a record shop's racks — flick through the spines, narrow by genre, artist, decade, label, format or favourites, tap to turn a case over and play it. In the menu of Mallard and the web app while it is on.") }) {
                Toggle(on, Modifier.clickable { on = !on; save() }, known)
            }
            SetupNote(if (on) "On: Shelf is in the menu." else "Off. Switch it on to add Shelf to the menu.", Modifier)
        }
    }
}
