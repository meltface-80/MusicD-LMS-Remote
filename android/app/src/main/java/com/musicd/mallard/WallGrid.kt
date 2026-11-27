package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlin.math.ceil
import kotlin.math.min

/*
 * A wall of albums as the page draws every one (bug list item 52; style.css
 * .album-grid, the touch layout): the heading at the top with the banner under
 * it when there is one, then the tiles at UI Settings' columns (or its list),
 * in blocks each under its own small heading where a screen has several (an
 * artist's own albums, and those they appear on). Drawn by the Library wall,
 * the walls Home links to, an artist's page and Smart Picks (its head and
 * Back), so the four carry each key.
 */

/** A run of albums under a heading of its own ([title]: none for the only run). */
internal class GridBlock(val title: String?, val albums: List<AlbumSummary>)

/**
 * Tiles of another kind among the blocks, under [title] ahead of block [before] (past the last: after them all): an
 * artist's albums on the default service (item 522). [count] of them, [key] and [tile] each.
 */
internal class GridExtra(val before: Int, val title: String, val count: Int, val key: (Int) -> Any, val tile: @Composable (Int) -> Unit)

/** The page's room for the bar above (its 64 and main's 14) and for the mini player and the row below (main's padding-bottom). */
internal val WALL_PADDING = PaddingValues(start = 14.dp, top = 78.dp, end = 14.dp, bottom = 164.dp)

/** How many columns the screen has at [prefs], and how wide a cover to ask for (the page's tileImgSize: the tile in device pixels to the next 100, within 200 and 500). */
@Composable
internal fun wallColumns(prefs: WallPrefs): Pair<Int, Int> {
    val conf = LocalConfiguration.current
    val cols = prefs.cols(conf.screenWidthDp, conf.screenHeightDp)
    val density = LocalDensity.current.density
    val coverPx = (ceil((conf.screenWidthDp - 28 - 8 * (cols - 1)) / cols.toFloat() * min(2f, density) / 100f) * 100).toInt().coerceIn(200, 500)
    return cols to coverPx
}

/** How many albums a wall of random albums asks for (the page's computeAlbumCount): a phone three screens' worth; a tablet or a desktop a screenful of rows. */
@Composable
internal fun randomWallCount(prefs: WallPrefs): Int {
    val conf = LocalConfiguration.current
    val w = conf.screenWidthDp; val h = conf.screenHeightDp
    if (min(w, h) < 768) return 24
    val cols = prefs.cols(w, h)
    return if (w >= 1200) cols * 5 else cols * (if (w > h) 3 else 4)
}

/**
 * The wall: [head] first across every column, then [blocks]' tiles (or rows, [prefs].list), [loaded] false drawing
 * skeletons after them; a tile's tap [onOpen] with its place among every album, a long press [onLongPress] likewise;
 * [selected] the offsets picked; [foot] after all of them once loaded; [extra] other tiles among the blocks, at the
 * columns in the list as well. [grid] and [list] keep the place for Back.
 */
@Composable
internal fun AlbumGrid(blocks: List<GridBlock>, prefs: WallPrefs, cols: Int, coverPx: Int, client: () -> ServerClient?, loaded: Boolean, selected: Set<Long>,
                       grid: LazyGridState, list: LazyListState, onOpen: (Int) -> Unit, onLongPress: (Int) -> Unit, dl: (AlbumSummary) -> String? = { null },
                       foot: (@Composable () -> Unit)? = null, extra: GridExtra? = null, head: @Composable () -> Unit) {
    // Each album's place among all of them, for the list an album page steps through.
    var at = 0
    if (prefs.list) LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = WALL_PADDING) {
        // A column: a lazy item lays its children over each other, and an artist's picture lay over the heading (item 135).
        item { Column(Modifier.fillMaxWidth()) { head() } }
        for ((n, b) in blocks.withIndex()) {
            if (extra != null && extra.before == n) extraRows(extra, cols)
            if (b.title != null) item { BlockTitle(b.title) }
            val from = at
            items(b.albums.size, key = { i -> b.albums[i].offset }) { i -> val a = b.albums[i]; val p = from + i; AlbumRow(a, prefs, coverPx, client, a.offset in selected, { onLongPress(p) }, dl = dl(a)) { onOpen(p) } }
            at += b.albums.size
        }
        if (extra != null && extra.before >= blocks.size) extraRows(extra, cols)
        if (!loaded) items(12) { SkeletonRow() }
        // What follows all the albums: an artist's similar artists and albums (item 140).
        else if (foot != null) item { Column(Modifier.fillMaxWidth()) { foot() } }
    }
    else LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), state = grid, contentPadding = WALL_PADDING,
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth()) { head() } }
        for ((n, b) in blocks.withIndex()) {
            if (extra != null && extra.before == n) extraTiles(extra)
            if (b.title != null) item(span = { GridItemSpan(maxLineSpan) }) { BlockTitle(b.title) }
            val from = at
            items(b.albums.size, key = { i -> b.albums[i].offset }) { i -> val a = b.albums[i]; val p = from + i; AlbumTile(a, prefs, coverPx, client, a.offset in selected, { onLongPress(p) }, dl = dl(a)) { onOpen(p) } }
            at += b.albums.size
        }
        if (extra != null && extra.before >= blocks.size) extraTiles(extra)
        if (!loaded) items(cols * 8) { SkeletonTile() }
        else if (foot != null) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth()) { foot() } }
    }
}

/** [x] in the grid: its heading across the columns, then its tiles. */
private fun LazyGridScope.extraTiles(x: GridExtra) {
    item(span = { GridItemSpan(maxLineSpan) }) { BlockTitle(x.title) }
    items(x.count, key = x.key) { i -> x.tile(i) }
}

/** [x] in the list: its heading, then its tiles [cols] to a row, as the search's service albums are drawn beside the library's rows. */
private fun LazyListScope.extraRows(x: GridExtra, cols: Int) {
    item { BlockTitle(x.title) }
    items((x.count + cols - 1) / cols, key = { r -> "x" + x.key(r * cols) }) { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in 0 until cols) Box(Modifier.weight(1f)) { if (r * cols + c < x.count) x.tile(r * cols + c) }
        }
    }
}

/** The bar's buttons in the hifi palette (style.css, `.topbar .icon-btn`): a brass disc of 40, the glyph in the charcoal. */
@Composable
internal fun barDisc() = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary, disabledContentColor = MaterialTheme.colorScheme.onPrimary)

/**
 * Back, as the bar's button: the brass disc at the bar's padding, top left, its glyph 20 as the bar draws it. A filled
 * button, not a plain one with a background: a plain button's 48 touch target lies inside the background modifier, which
 * drew the disc 48 wide (bug list item 54); the filled button's disc is its own 40.
 */
@Composable
internal fun BoxScope.WallBack(onBack: () -> Unit) {
    FilledIconButton(onClick = onBack, colors = barDisc(), modifier = Modifier.parity("library.back wall.back artist.back picks.back artists.back", Contract.label("library.back")).align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp).size(40.dp)) {
        Icon(painterResource(R.drawable.np_back), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** A block's small heading, as the artist page's (.artist-section-header): tracked out, faint. */
@Composable
private fun BlockTitle(title: String) {
    Text(title.uppercase(), Modifier.parity("artist.section").padding(top = 12.dp, bottom = 4.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.06.em)
}

/**
 * The heading over a wall ([title], in the page's serif at UI Settings' title size; style.css `#tnav-heading #album-count`
 * and the hifi palette's `.album-count`), and the banner under it when there is one ([banner]: what it says, and whether
 * as an error). Its 14 below is the page's margin; in the grid the rows' own 12 is part of it.
 */
@Composable
internal fun WallHead(title: String, prefs: WallPrefs, banner: Pair<String, Boolean>?) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (prefs.list) 14.dp else 2.dp)) {
        // In the page's 1.2 line box (Type.kt), its figures tabular as the page's are (.album-count), so "1" is as wide as "3".
        val size = (24 * prefs.title).sp; val line = (28.8 * prefs.title).sp
        Text(title, Modifier.parity("library.title wall.title artist.title picks.title artists.title").lineBox(size, line, Face.YOUNG_SERIF), color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontWeight = FontWeight.W400,
            fontSize = size, lineHeight = line, letterSpacing = 0.01.em, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        banner?.let { (said, error) -> Banner(said, error, Modifier.padding(top = 14.dp)) }
    }
}

/** The page's banner (.status-banner): a note across the wall, its edge red when it is an error. */
@Composable
internal fun Banner(said: String, error: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().border(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(said, Modifier.parity("library.banner wall.banner artist.error picks.banner search.banner artists.banner"), color = if (error) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** A tile's shape while the first page comes (the page's skeletons): the cover's square and two lines of nothing. */
@Composable
private fun SkeletonTile() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(paletteColor(Palette.BG_ELEV_2)))
        Spacer(Modifier.height(11.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(paletteColor(Palette.BG_ELEV_2)))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(paletteColor(Palette.BG_ELEV_2)))
    }
}

/** A row's shape while the first page comes. */
@Composable
private fun SkeletonRow() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 7.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(paletteColor(Palette.BG_ELEV_2)))
    }
}
