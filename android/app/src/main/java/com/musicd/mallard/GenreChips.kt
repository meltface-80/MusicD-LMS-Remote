package com.musicd.mallard

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract

/**
 * Genres as small chips (bug list item 139; the page's .genre-chips): an album's own, or an artist's, the most used
 * first; a tap opens the genre's wall ([onGenre]). [where] is "album" or "artist", for their keys in the contract.
 * [center]: in the middle of the line, as on an artist's head.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreChips(genres: List<String>, where: String, center: Boolean, modifier: Modifier = Modifier, onGenre: (String) -> Unit) {
    if (genres.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, if (center) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in genres) {
            // Each key written out, as the gate reads keys from the source; the chip's shape is the page's .genre-chip.
            val shape = RoundedCornerShape(12.dp)
            val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
            if (where == "album") Text(g, Modifier.parity("album.genre", Contract.label("album.genre", 0, g)).clip(shape).then(edge).clickable { onGenre(g) }.padding(horizontal = 10.dp, vertical = 3.dp), style = chipText(), maxLines = 1)
            else Text(g, Modifier.parity("artist.genre", Contract.label("artist.genre", 0, g)).clip(shape).then(edge).clickable { onGenre(g) }.padding(horizontal = 10.dp, vertical = 3.dp), style = chipText(), maxLines = 1)
        }
    }
}

/** A chip's words: small and quiet, one line. */
@Composable
private fun chipText() = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
