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

/* An album's page (AlbumScreen.kt), its ⋯ menu, split from it (bug list item 108); its head's lines are AlbumPhone.kt's (item 539). */

/**
 * ⋯: what the server offers beyond Play Now and Queue (Play Next, as its album answer lists it), and the artist's page.
 * MusicD LMS Remote has no editor, versions, booklets or downloads to offer here.
 */
@Composable
internal fun MoreMenu(a: Album, onPlay: (String, String) -> Unit, onPage: (String, String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val kinds = a.actions.map { it.kind }.toSet()
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
            val lead = com.musicd.mallard.client.Artists.lead(a.album.subtitle)
            if (lead.isNotEmpty()) DropdownMenuItem(text = { Text("Go to $lead", fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, contentPadding = PaddingValues(10.dp),
                onClick = { open = false; onPage("artist", lead) })
        }
    }
}
