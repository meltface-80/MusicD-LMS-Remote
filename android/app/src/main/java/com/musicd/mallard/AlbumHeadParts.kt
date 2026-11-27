package com.musicd.mallard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import org.json.JSONObject

/* An album's page (AlbumScreen.kt), its ⋯ menu, split from it (bug list item 108); its head's lines are AlbumPhone.kt's (item 539). */

/**
 * ⋯: what the server offers beyond Play Now and Queue (Play Next, Shuffle,
 * Radio), a streamed album's favourite on its service ([serviceFavourite],
 * once known), Listen later, the page's own Edit album,
 * and, for an album of your own files, Download to this phone ([download],
 * the item's words as the store has them); a version that isn't its album's main,
 * Make this the main version ([onMain], item 349).
 */
@Composable
internal fun MoreMenu(a: Album, later: Boolean, onLater: () -> Unit, onPlay: (String, String) -> Unit, onPage: (String, String?) -> Unit,
                     download: String?, onDownload: () -> Unit, serviceFavourite: Boolean?, onServiceFavourite: (Boolean) -> Unit, onMain: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val kinds = a.actions.map { it.kind }.toSet()
    val offset = a.album.offset.toString()
    Box {
        OutlinedIconButton(onClick = { open = !open }, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = paletteColor(Palette.MODAL_TEXT)),
            modifier = Modifier.parity("album.more", Contract.label("album.more")).size(40.dp, 42.dp)) {
            Icon(painterResource(R.drawable.np_more), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        // The page's menu (.overflow-menu): 186 wide, each item 15px in 10 of padding, 41 tall.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(10.dp), containerColor = paletteColor(Palette.BG_ELEV_2),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.width(186.dp)) {
            if ("play_next" in kinds) DropdownMenuItem(text = { Text(Contract.label("album.play-next"), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.play-next", Contract.label("album.play-next")),
                onClick = { open = false; onPlay("play_next", "Playing next") })
            if ("shuffle" in kinds) DropdownMenuItem(text = { Text(Contract.label("album.shuffle"), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.shuffle", Contract.label("album.shuffle")),
                onClick = { open = false; onPlay("shuffle", "Shuffling") })
            if ("radio" in kinds) DropdownMenuItem(text = { Text(Contract.label("album.radio"), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.radio", Contract.label("album.radio")),
                onClick = { open = false; onPlay("radio", "Radio") })
            val service = a.album.serviceName
            if (service != null && a.album.serviceId != null && serviceFavourite != null) {
                val words = (if (serviceFavourite) "Remove from " else "Add to ") + service
                DropdownMenuItem(text = { Text(Contract.label("album.service-favourite", 0, words), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.service-favourite", Contract.label("album.service-favourite", 0, words)),
                    onClick = { open = false; onServiceFavourite(!serviceFavourite) })
            }
            DropdownMenuItem(text = { Text(Contract.label("album.later", if (later) 1 else 0), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.later", Contract.label("album.later", if (later) 1 else 0)),
                onClick = { open = false; onLater() })
            // Share card (item 373): here, with no button of its own at the corner.
            DropdownMenuItem(text = { Text(Contract.label("album.share"), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.share", Contract.label("album.share")),
                onClick = { open = false; onPage("share", null) })
            if (onMain != null) DropdownMenuItem(text = { Text(Contract.label("album.version-main"), fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.primary) }, contentPadding = PaddingValues(10.dp),
                modifier = Modifier.parity("album.version-main", Contract.label("album.version-main")), onClick = { open = false; onMain() })
            if (a.album.source == null || a.album.source == "local") DropdownMenuItem(text = { Text(Contract.label("album.edit"), fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp),
                modifier = Modifier.parity("album.edit", Contract.label("album.edit")), onClick = { open = false; onPage("album-edit", offset) })
            if (download != null) DropdownMenuItem(text = { Text(download, fontSize = 15.sp, lineHeight = 21.sp) }, contentPadding = PaddingValues(10.dp), modifier = Modifier.parity("album.download", Contract.label("album.download", 0)),
                onClick = { open = false; onDownload() })
        }
    }
}

/** Download to this phone's words, as the page's bridge words them from the store's [download] state; null where it isn't [offered]. */
internal fun downloadWords(download: JSONObject, offered: Boolean): String? {
    if (!offered) return null
    return when (download.optString("state", "none")) {
        "done" -> Contract.label("album.download", 1)
        "downloading", "queued", "waiting" -> Contract.label("album.download", 2, "${download.optInt("done", 0)}/${download.optInt("total", 0).takeIf { it > 0 } ?: "?"}")
        else -> Contract.label("album.download", 0)
    }
}

/** A streamed album's favourite on its service set [on] (off the main thread): what to say of it, and whether it was set; null where it has no service. */
internal fun setServiceFavourite(c: ServerClient, a: Album, on: Boolean): Pair<String, Boolean>? {
    val name = a.album.serviceName ?: return null
    val source = a.album.source ?: return null
    val id = a.album.serviceId ?: return null
    val r = runCatching { c.serviceFavourite(source, id, on) }
    val err = r.exceptionOrNull()?.message ?: r.getOrNull()?.optString("error", "")?.ifEmpty { null }
    return if (err != null) err to false else (if (on) "Added to your $name favourites" else "Removed from your $name favourites") to true
}
