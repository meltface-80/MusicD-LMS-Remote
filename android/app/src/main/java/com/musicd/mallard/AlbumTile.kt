package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs

/*
 * An album's tile, as the page's (bug list items 51 and 52; album-tiles.js
 * buildAlbumTile, style.css .album), on the Library wall, the walls Home
 * links to, an artist's page and Home's rows (each tile carries the four
 * screens' keys; on Home, ★ Today on today's album and a download's badge):
 * the cover square with its corners, the raised ground under it and "♪"
 * where there is none; the rate on it bottom left (brass when better than
 * CD), the service's mark top right, two discs bottom right; the title under
 * it in two lines at most, the artist in one, each at UI Settings' text
 * size. In UI Settings' List layout the same as a row: a 52 cover, the title
 * over the artist, a chevron. A tap opens the album; a long press starts
 * selecting, with this tile the first pick, and a selected tile wears the
 * page's ring and tick.
 */

/** The album [a] as a tile of the grid: its cover [coverPx] wide, its text at [prefs]' size; [selected] with the ring and tick; [badge] ★ Today on today's album, [dl] a download's state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumTile(a: AlbumSummary, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, selected: Boolean = false, onLongPress: () -> Unit = {}, badge: String? = null, dl: String? = null, fourLines: Boolean = false, onOpen: () -> Unit) {
    val cover = rememberTileCover(a.imageKey, coverPx, client)
    Column(Modifier.parity("library.album wall.album artist.album home.album search.album", Contract.label("library.album", if (a.subtitle.isEmpty()) 1 else 0, a.title.ifEmpty { "Untitled" }, a.subtitle))
        .fillMaxWidth().combinedClickable(onLongClick = onLongPress, onClick = onOpen)) {
        TileArt(a, cover, selected, Modifier.fillMaxWidth().aspectRatio(1f), today = badge, dl = dl)
        // The page's phone sizes (style.css, max-width 767px, and the hifi palette's .album-meta and .album-title): 11 under the
        // cover, the title 12px at 600 in two lines of 1.25, a 1 gap, the artist 10.5px in one line of 1.3; each in its line box (Type.kt).
        val title = (12 * prefs.text).sp; val titleLine = (15 * prefs.text).sp
        val artist = (10.5 * prefs.text).sp; val artistLine = (13.65 * prefs.text).sp
        // On Home's rows ([fourLines], item 536): room kept for the most words a tile has, so a row is one height whichever tiles are on screen.
        Box {
            if (fourLines) FourLines(title, titleLine, artist, artistLine)
            Column {
                Text(a.title.ifEmpty { "Untitled" }, Modifier.parity("library.album-title wall.album-title artist.album-title home.album-title search.album-title").padding(top = 11.dp).lineBox(title, titleLine), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (a.subtitle.isNotEmpty()) Text(a.subtitle, Modifier.parity("library.album-artist wall.album-artist artist.album-artist home.album-artist search.album-artist").padding(top = 1.dp).lineBox(artist, artistLine), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = artist, lineHeight = artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The room a tile's words take at most, as its own are drawn (a title of two lines, the artist, its versions): unseen and unspoken, behind them. */
@Composable
private fun FourLines(title: TextUnit, titleLine: TextUnit, artist: TextUnit, artistLine: TextUnit) {
    Column(Modifier.alpha(0f).clearAndSetSemantics {}) {
        Text("M\nM", Modifier.padding(top = 11.dp).lineBox(title, titleLine), fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2)
        Text("M", Modifier.padding(top = 1.dp).lineBox(artist, artistLine), fontSize = artist, lineHeight = artistLine, maxLines = 1)
        Text("M", Modifier.padding(top = 1.dp).lineBox(artist, artistLine), fontSize = artist, lineHeight = artistLine, maxLines = 1)
    }
}

/** The album [a] as a row of the list (UI Settings' List): the cover small, the title over the artist, a chevron; a hairline under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumRow(a: AlbumSummary, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, selected: Boolean = false, onLongPress: () -> Unit = {}, dl: String? = null, onOpen: () -> Unit) {
    val cover = rememberTileCover(a.imageKey, coverPx, client)
    val faint = paletteColor(Palette.TEXT_FAINT)
    Column {
        Row(Modifier.parity("library.album wall.album artist.album home.album search.album", Contract.label("library.album", if (a.subtitle.isEmpty()) 1 else 0, a.title.ifEmpty { "Untitled" }, a.subtitle))
            .fillMaxWidth().combinedClickable(onLongClick = onLongPress, onClick = onOpen).padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TileArt(a, cover, selected, Modifier.size(52.dp), today = null, dl = dl)
            Column(Modifier.weight(1f)) {
                Text(a.title.ifEmpty { "Untitled" }, Modifier.parity("library.album-title wall.album-title artist.album-title home.album-title search.album-title"), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = (12 * prefs.text).sp, lineHeight = (15 * prefs.text).sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a.subtitle.isNotEmpty()) Text(a.subtitle, Modifier.parity("library.album-artist wall.album-artist artist.album-artist home.album-artist search.album-artist").padding(top = 1.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (10.5 * prefs.text).sp, lineHeight = (13.6 * prefs.text).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // The chevron that says the row opens something: two sides of a 7px square, turned (the page's ::after).
            Canvas(Modifier.padding(end = 4.dp).size(7.dp)) {
                val w = 2.dp.toPx()
                drawLine(faint, Offset(0f, 0f), Offset(size.width, size.height / 2), w, StrokeCap.Square)
                drawLine(faint, Offset(size.width, size.height / 2), Offset(0f, size.height), w, StrokeCap.Square)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The cover square: [cover] once fetched, "♪" where the album has none; the badges on it; [selected], the page's ring and tick; [today] ★ Today top left, [dl] a download's state bottom left over a dimmed cover. */
@Composable
internal fun TileArt(a: AlbumSummary, cover: ImageBitmap?, selected: Boolean, modifier: Modifier, today: String?, dl: String?) {
    val badge = Color(0x9E0A0E14)   // the page's rgba(10, 14, 20, .62)
    val onBadge = Color.White.copy(alpha = 0.92f)
    // The hifi palette's sleeve: corners of 12 and a faint white ring just inside the edge, no shadow (style.css, [data-palette="hifi"] .album-art-wrap).
    val corners = RoundedCornerShape(12.dp)
    Box(modifier.parity("library.album-art wall.album-art artist.album-art home.album-art search.album-art").clip(corners).background(paletteColor(Palette.BG_ELEV_2))
        .border(if (selected) 2.5.dp else 1.dp, Color.White.copy(alpha = if (selected) 0.35f else 0.06f), corners)) {
        if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (dl != null) 0.55f else 1f))
        else if (a.imageKey == null) Text("♪", Modifier.align(Alignment.Center), color = paletteColor(Palette.TEXT_FAINT), fontSize = 48.sp, lineHeight = 48.sp)
        // The service's mark, top right: only a Qobuz or Tidal album wears one (the page's .album-source).
        when (a.source) { "qobuz" -> R.drawable.src_qobuz; "tidal" -> R.drawable.src_tidal; else -> null }?.let { icon ->
            Box(Modifier.parity("library.album-source wall.album-source artist.album-source home.album-source search.album-source").align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(badge), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = onBadge, modifier = Modifier.size(13.dp))
            }
        }
        // The files' rate, bottom left, in brass when better than CD (the page's .album-quality).
        a.quality?.let { q ->
            Box(Modifier.parity("library.album-quality wall.album-quality artist.album-quality home.album-quality search.album-quality").align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(badge).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(q, color = if (a.hires) MaterialTheme.colorScheme.secondary else onBadge, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.W700, letterSpacing = 0.02.em, maxLines = 1)
            }
        }
        // More than one disc, bottom right (the page's .album-discs).
        if ((a.discs ?: 0) > 1) Box(Modifier.parity("library.album-discs wall.album-discs artist.album-discs home.album-discs search.album-discs").align(Alignment.BottomEnd).padding(6.dp).size(24.dp, 19.dp).clip(RoundedCornerShape(5.dp)).background(badge), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.tile_discs), contentDescription = null, tint = onBadge, modifier = Modifier.size(15.dp))
        }
        // Today's album: ★ Today in a brass pill, top left (the page's .aotd-badge).
        if (today != null) Box(Modifier.parity("home.aotd-badge").align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(today, color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.W800, letterSpacing = 0.02.em, maxLines = 1)
        }
        // A download on its way, or on a card that is out: its state in a dark pill, bottom left (android.css .dl-badge), the cover dimmed under it.
        if (dl != null) Box(Modifier.parity("home.dl-badge").align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xB8000000)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(dl, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.W700, maxLines = 1, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        }
        // Selected: the tick in a white disc, top right (the page's .is-selected ::after).
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
        }
    }
}
