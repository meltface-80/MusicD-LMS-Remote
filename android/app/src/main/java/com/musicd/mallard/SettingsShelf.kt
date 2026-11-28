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
 * The Shelf's switch, in Settings → Wall display (owner: "Shelf isn't in wall display"), as the web app's Wall display
 * pane has it. The switch is the server's (/api/settings/shelf, lib/shelf-routes.js), so both apps follow it; in Mallard
 * it also puts Shelf in the bottom bar (TouchNav's NavShelf), as Mandarin's switch does.
 */
@Composable
internal fun ShelfBlock(client: () -> ServerClient?, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("shelf") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var known by remember { mutableStateOf(kept != null) }
    LaunchedEffect(Unit) { readSetting(client, "shelf")?.let { on = it.optBoolean("enabled"); known = true; NavShelf.on.value = on } }
    fun save() = saveSetting(scope, context, client, "shelf", JSONObject().put("enabled", on), if (on) "Shelf on — it's in the bottom bar and the menu" else "Shelf off",
        undo = { on = !on; NavShelf.on.value = on }) { j ->
        if (j.has("enabled")) on = j.optBoolean("enabled")
        NavShelf.on.value = on
    }
    Block {
        SetupRow("Shelf", Modifier, Modifier.clickable { info("A full-screen way to browse the whole library like a record shop's racks — flick through the spines, narrow by genre, artist, decade, label, format or favourites, tap to turn a case over and play it. At /shelf on the server, and while it is on, in Mallard's bottom bar and in both apps' menus.") }) {
            Toggle(on, Modifier.clickable { on = !on; NavShelf.on.value = on; save() }, known)
        }
        SetupNote(if (on) "On: Shelf is in the bottom bar and the menu." else "Off. Switch it on to add Shelf to the bottom bar and the menu.", Modifier)
    }
}
