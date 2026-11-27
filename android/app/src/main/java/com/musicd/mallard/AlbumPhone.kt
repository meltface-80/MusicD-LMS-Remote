package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.AlbumExtras
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.musicd.mallard.client.albumLength

/*
 * An album's page on a phone held upright (item 349), as the page's album view at its width: the cover on top, centred;
 * under it the date, the rate and the length on one centred line; the title across the page in Young Serif, the artists
 * under it; Play Now, Queue, the heart and ⋯; then its tabs, Tracks, Credits, About and, where it has them, Versions.
 * The album's genres, its label and its score are About's. A version chosen on Versions is shown in this page's place,
 * only its tracks, length and rate changing (AlbumScreen.kt): what is the album's own stays as it is.
 */

/** The tabs, by the place their labels have in the contract: Mallard shows Tracks and About. */
internal val ALBUM_TABS = listOf("tracks", "credits", "about", "versions")

/** The date, the rate (its chip, hi-res in brass) and the length, centred under the cover. */
@Composable
internal fun CentredFacts(a: Album, extras: AlbumExtras?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val date = extras?.date().orEmpty()
    val length = albumLength(a.tracks)
    Row(Modifier.parity("album.facts").fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        val parts = mutableListOf<@Composable () -> Unit>()
        if (date.isNotEmpty()) parts += { Text(date, Modifier.parity("album.date"), color = dim, fontSize = 14.sp, lineHeight = 19.sp) }
        a.album.quality?.let { q ->
            parts += {
                val c = if (a.album.hires) MaterialTheme.colorScheme.secondary else dim
                val edge = if (a.album.hires) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                Text(q, Modifier.border(1.dp, edge, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp), color = c, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.W600)
            }
        }
        if (length.isNotEmpty()) parts += { Text(length, color = dim, fontSize = 14.sp, lineHeight = 19.sp) }
        parts.forEachIndexed { i, part -> if (i > 0) Text("·", color = dim, fontSize = 14.sp); part() }
    }
}

/**
 * The head (item 349): the sleeve centred on top ([badges] on it), the date, rate and length centred under it, the title
 * across the page in Young Serif, the artists (a tap: their page) or a classical album's composers, and the notes; all of
 * it centred (item 539), on a phone and in a tablet's left column alike.
 */
@Composable
internal fun PhoneHead(a: Album, cover: ImageBitmap?, width: Dp, extras: AlbumExtras?, onPage: (String, String?) -> Unit,
                       badges: @Composable BoxScope.() -> Unit, onDisc: (NavList) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        CentredCover(cover, a, width, badges)
        CentredFacts(a, extras)
        Text(a.album.baseTitle ?: a.album.title, Modifier.parity("album.title").fillMaxWidth().padding(top = 10.dp), fontFamily = youngSerif, fontSize = 30.sp, lineHeight = 34.sp,
            color = paletteColor(Palette.MODAL_TEXT), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (a.composers.isNotEmpty()) ComposerLines(a, onPage)
        else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            (a.artists.ifEmpty { listOf(a.album.subtitle) }).forEachIndexed { i, name ->
                if (i > 0) Text(" · ", Modifier.parity("album.artist"), color = dim, fontSize = 17.sp)
                Text(name, Modifier.parity("album.artist", Contract.label("album.artist", 0, name)).clickable { onPage("artist", if (i == 0) ArtistAct.of(name, a.album.artistId) else name) },
                    color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HeadNotes(a, onDisc)
    }
}

/** The sleeve on top, centred, as wide as two thirds of the page and no wider than 300. */
@Composable
internal fun CentredCover(cover: ImageBitmap?, a: Album, width: Dp, badges: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val sleeve = RoundedCornerShape(10.dp)
        Box(Modifier.parity("album.cover").size(width).shadow(18.dp, sleeve).clip(sleeve).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, paletteColor(Palette.COVER_RING), sleeve)) {
            cover?.let { Image(it, contentDescription = a.album.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            badges()
        }
    }
}

/** The tabs in their place in the page, under Play Now and Queue; HeldTabs holds them at the top as it scrolls (item 375). */
internal fun LazyListScope.phoneTabs(tab: String, versions: Int, scroll: ScrollState, onTab: (String) -> Unit) {
    item(key = TABS_KEY) { AlbumTabRow(tab, versions, scroll, onTab) }
}

private const val TABS_KEY = "album.tabs"

/** Where the line under the tabs is, in pixels from the list's top, while they are held there; null while they are in
    their place, scrolled down to or not yet reached. */
@Composable
internal fun rememberHeldTabs(list: LazyListState): State<Int?> {
    val seen = remember(list) { intArrayOf(-1, 0) }   // the tabs' place in the list and their height, as last laid out
    return remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val t = info.visibleItemsInfo.firstOrNull { it.key == TABS_KEY }
            if (t != null) { seen[0] = t.index; seen[1] = t.size }
            when {
                t != null -> t.size.takeIf { t.offset <= info.viewportStartOffset && list.canScrollBackward }
                seen[0] >= 0 && list.firstVisibleItemIndex > seen[0] -> seen[1]
                else -> null
            }
        }
    }
}

/** The page cut off at the line under the tabs while they are held (item 375): what scrolls up past it isn't drawn, so
    the tracks never show through the tabs, and above the line, the whole width, is the page's own background and the
    cover's glow, not a bar painted over them. */
internal fun Modifier.clipUnderTabs(line: State<Int?>): Modifier = drawWithContent {
    val l = line.value
    if (l == null) drawContent() else clipRect(top = l.toFloat()) { this@drawWithContent.drawContent() }
}

/** The tabs held at the top of the page, over the line it is cut off at: the same row as in its place, scrolled sideways
    as it is, there only while held. */
@Composable
internal fun HeldTabs(line: State<Int?>, tab: String, versions: Int, scroll: ScrollState, onTab: (String) -> Unit) {
    if (line.value == null) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { AlbumTabRow(tab, versions, scroll, onTab) }
}

/** The tabs' row: its words tracked out, the one open in the text's colour over a brass bar; Versions with how many.
    Centred, with room at each side for Back at the corner (item 373), never a bar behind them; across the row a step
    sideways where a narrow phone hasn't room for them all. */
@Composable
internal fun AlbumTabRow(tab: String, versions: Int, scroll: ScrollState, onTab: (String) -> Unit) {
    val faint = paletteColor(Palette.TEXT_FAINT)
    val brass = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 44.dp).horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            ALBUM_TABS.forEachIndexed { i, t ->
                // MusicD LMS has no credits or versions to list: Tracks and About.
                if (t == "credits" || t == "versions") return@forEachIndexed
                val on = t == tab
                // As wide as its words, its bar under them as wide (item 355: a bar filling the row took the row, the tabs after it squeezed into a letter a line).
                Column(Modifier.parity("album.tab", Contract.label("album.tab", i)).width(IntrinsicSize.Max).clickable { onTab(t) }.padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Contract.label("album.tab", i).uppercase(), color = if (on) paletteColor(Palette.MODAL_TEXT) else faint, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.06.em)
                        if (t == "versions") Text("$versions", Modifier.clip(RoundedCornerShape(999.dp)).background(paletteColor(Palette.ACCENT_SOFT)).padding(horizontal = 7.dp, vertical = 1.dp),
                            color = brass, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.padding(top = 10.dp).height(2.dp).fillMaxWidth().background(if (on) brass else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** About's head: the album's genres (a tap: the genre's albums) and its score. */
internal fun LazyListScope.aboutHead(a: Album, extras: AlbumExtras?, onPage: (String, String?) -> Unit) {
    if (a.genres.isEmpty() && extras?.scoreText == null) return
    item {
        Column(Modifier.parity("album.about-facts").fillMaxWidth().padding(top = 18.dp)) {
            if (a.genres.isNotEmpty()) GenreChips(a.genres, "album", center = false, modifier = Modifier.fillMaxWidth()) { onPage("genre", it) }
            val e = extras ?: return@Column
            if (e.scoreText != null) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                e.scoreText?.let { Text(it + if (e.bestNewMusic) " BNM" else "", color = dim, fontSize = 14.sp, lineHeight = 19.sp) }
            }
        }
    }
}
