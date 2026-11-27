package com.musicd.mallard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient

/*
 * What the side menu's collection screens share (Merged albums, Playlists, Dynamic Playlists, Import): the wall's
 * chrome — Back in the brass disc at the top left, the mini player and the row along the foot — and their small parts:
 * a playlist's mosaic of four covers, and the brass and quiet pills the web app's sheets use for Play now and Queue.
 */

/** The callbacks every collection screen hands on: Now playing, a room chosen, the foot row's buttons, the app's pages. */
internal class CollectionNav(val onBack: () -> Unit, val onNowPlaying: (String) -> Unit, val onRoom: (String) -> Unit,
                             val onNav: (String) -> Unit, val onPage: (String, String?) -> Unit)

/** A collection screen's ground and chrome, [content] given the room chosen (it follows the mini player's). */
@Composable
internal fun CollectionFrame(zoneId: String, client: () -> ServerClient?, nav: CollectionNav, content: @Composable BoxScope.(zone: String) -> Unit) {
    var zone by remember { mutableStateOf(zoneId.ifEmpty { NativeShell.zone }) }
    val room = followRoom(zone, client)
    BackHandler(onBack = nav.onBack)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content(zone)
        WallBack(nav.onBack)
        Column(Modifier.align(Alignment.BottomCenter)) {
            MiniPlayer(zone, room, client, onOpen = { nav.onNowPlaying(zone) }, onRoom = { id -> zone = id; nav.onRoom(id) }, onPage = nav.onPage)
            TouchNav("", onTap = nav.onNav)
        }
    }
}

/** Up to four covers as a 2 × 2 mosaic ([keys]' image keys), the raised ground under any gaps; one cover fills it. */
@Composable
internal fun CoverMosaic(keys: List<String>, size: Dp, client: () -> ServerClient?) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(64, 600)
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
        if (keys.size == 1) { Cover(keys[0], px, client, Modifier.fillMaxSize()); return@Box }
        Column {
            for (r in 0..1) Row {
                for (c in 0..1) Box(Modifier.size(size / 2)) { keys.getOrNull(r * 2 + c)?.let { Cover(it, px / 2, client, Modifier.fillMaxSize()) } }
            }
        }
    }
}

@Composable
private fun Cover(key: String, px: Int, client: () -> ServerClient?, modifier: Modifier) {
    rememberTileCover(key, px.coerceAtLeast(64), client)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** A pill: brass for what plays ([primary]), the raised ground for the rest; dimmed and still while [enabled] is false. */
@Composable
internal fun ActPill(words: String, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(modifier.heightIn(min = 38.dp).clip(shape).background(paletteColor(if (primary) Palette.ACCENT else Palette.BG_ELEV_2))
        .border(1.dp, paletteColor(if (primary) Palette.ACCENT else Palette.BORDER), shape)
        .let { if (enabled) it.clickable(onClick = onClick) else it }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(words, color = paletteColor(if (primary) Palette.ON_ACCENT else Palette.TEXT).copy(alpha = if (enabled) 1f else 0.5f),
            fontSize = 14.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

/** A short note on the screen (a toast), as every Mallard screen says what a tap did. */
internal fun collectionToast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** The words while nothing is drawn yet, or why nothing is ([failed]). */
@Composable
internal fun ListNote(text: String, error: Boolean = false) {
    Text(text, Modifier.padding(vertical = 18.dp), color = if (error) paletteColor(Palette.DANGER) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
}

/** The context, for a composable that only needs it to say something. */
@Composable
internal fun rememberToast(): (String) -> Unit { val c = LocalContext.current; return { t -> collectionToast(c, t) } }
