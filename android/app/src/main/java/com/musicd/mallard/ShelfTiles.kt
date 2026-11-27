package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ScreenSize
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfChoices
import com.musicd.mallard.client.ShelfTile

/**
 * The tiles of the Shelf's choices (bug list item 207; the page's shelf-pick.js and shelf-pick.css on a phone): a genre's
 * or a label's with a fan of three of its covers, a letter's, a decade's, a kind of release's, an order's with its line;
 * each with how many albums it would bring and ticked in the brass when chosen, faint when it would bring none; on the
 * Order tab, Shuffle again and Spin the shelf; where a tab has nothing to choose, what it says instead.
 */
@Composable
internal fun ShelfTilesGrid(shelf: ShelfControl, tab: String, rem: Dp, client: () -> ServerClient?, modifier: Modifier = Modifier) {
    val lib = shelf.lib ?: return
    shelf.rev   // drawn again when a choice moves the counts
    val tiles = shelf.choices.tiles(lib, tab)
    // genres and formats the size of a decade or a kind (item 209); a label and the Favourites tile with a fan of covers
    // a phone's columns; a wide screen (a tablet, a foldable opened) as many more of the same size as fit (item 221)
    val phoneCols = when (tab) { "artist" -> 6; "year", "type", "genre", "format" -> 3; "order" -> 1; else -> 2 }
    val cols = ScreenSize.shelfColumns(phoneCols, LocalConfiguration.current.screenWidthDp)
    val gap = if (tab == "artist" || tab == "year" || tab == "type" || tab == "genre" || tab == "format") rem * 0.5f else rem * 0.6f
    fun toggle(id: String) = shelf.choose { toggle(tab, id, kotlin.random.Random.nextLong(1, 0xffffffffL)) }
    LazyVerticalGrid(GridCells.Fixed(cols), modifier, contentPadding = PaddingValues(top = rem * 0.5f, bottom = rem * 1.3f),
        verticalArrangement = Arrangement.spacedBy(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
        if (tiles.isEmpty()) shelf.choices.message(tab)?.let { m -> item(span = { GridItemSpan(maxLineSpan) }) {
            Text(m, Modifier.parity("shelf-choices.message").padding(vertical = rem * 0.4f), color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.9f).sp)
        } }
        // the genres' order above them (item 213, as Rouen's v1.9.70-t5): #N, A → Z, Z → A. The order chosen is passed in, not
        // read inside: it isn't Compose state, so the keyed item would be skipped and #N stay lit (item 220).
        val sort = shelf.choices.genreSort
        if (tab == "genre" && tiles.isNotEmpty()) item(key = "genre-sorts", span = { GridItemSpan(maxLineSpan) }) { GenreSorts(shelf, sort, rem) }
        items(tiles, key = { it.id }) { t ->
            val fan = rem * 0.85f
            when (tab) {
                "genre" -> TileBox(Modifier.parity("shelf-choices.genre", Contract.label("shelf-choices.genre", 0, t.said)).clickable { toggle(t.id) }.aspectRatio(1.6f), t, rem, rem * 0.7f) {
                    WordFace(t, rem, 1.6f, serif = false, lines = 2)
                }
                "artist" -> TileBox(Modifier.parity("shelf-choices.letter", Contract.label("shelf-choices.letter", 0, t.id)).clickable { toggle(t.id) }.aspectRatio(1f), t, rem, rem * 0.7f) {
                    WordFace(t, rem, 1f, serif = true)
                }
                "year" -> TileBox(Modifier.parity("shelf-choices.decade", Contract.label("shelf-choices.decade", if (t.id == "none") 1 else 0, t.id)).clickable { toggle(t.id) }.aspectRatio(1.6f), t, rem, rem * 0.7f) {
                    WordFace(t, rem, 1.6f, serif = t.id != "none")
                }
                "type" -> TileBox(Modifier.parity("shelf-choices.type", Contract.label("shelf-choices.type", 0, t.name)).clickable { toggle(t.id) }.aspectRatio(1.6f), t, rem, rem * 0.7f) {
                    WordFace(t, rem, 1.6f, serif = false)
                }
                // item 208: a format's name may run to two lines ("DSD128 Multichannel"); the one Favourites tile as a label's
                "format" -> TileBox(Modifier.parity("shelf-choices.format", Contract.label("shelf-choices.format", 0, t.name)).clickable { toggle(t.id) }.aspectRatio(1.6f), t, rem, rem * 0.7f) {
                    WordFace(t, rem, 1.6f, serif = false, lines = 2)
                }
                "favourite" -> TileBox(Modifier.parity("shelf-choices.favourite", Contract.label("shelf-choices.favourite")).clickable { toggle(t.id) }.aspectRatio(1.15f), t, rem, fan) { FanFace(t, rem, client) }
                else -> TileBox(Modifier.parity("shelf-choices.order", Contract.label("shelf-choices.order", 0, t.name)).clickable { toggle(t.id) }.fillMaxWidth().heightIn(min = rem * 4.6f), t, rem, fan) {
                    OrderFace(t, rem)
                }
            }
        }
        if (tab == "order") {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val on = shelf.choices.order() == "random" && shelf.n >= 2
                Row { Box(Modifier.parity("shelf-choices.reshuffle", Contract.label("shelf-choices.reshuffle")).heightIn(min = rem * 2.9f).clip(CircleShape)
                    .border(1.dp, paletteColor(Palette.BORDER), CircleShape).background(paletteColor(Palette.BG_ELEV)).clickable(enabled = on) { shelf.choices.seed = kotlin.random.Random.nextLong(1, 0xffffffffL); shelf.refresh() }
                    .alpha(if (on) 1f else 0.45f).padding(horizontal = rem * 1.25f), contentAlignment = Alignment.Center) {
                    Text("Shuffle again", color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.92f).sp, fontWeight = FontWeight.W700)
                } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                val shape = RoundedCornerShape(rem * 0.85f)
                Row(Modifier.parity("shelf-choices.spin", Contract.label("shelf-choices.spin")).fillMaxWidth().clip(shape).border(1.dp, SHELF_BRASS_DIM, shape)
                    .background(paletteColor(Palette.ACCENT_SOFT)).clickable { shelf.spin(1, 2.4f) }.padding(horizontal = rem, vertical = rem * 0.85f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.9f)) {
                    SpinDisc(shelf, rem * 2.6f)
                    Column {
                        Text("Spin the shelf", color = paletteColor(Palette.TEXT), fontSize = (rem.value).sp, fontWeight = FontWeight.W700)
                        Text("It lands on something in three seconds", color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.8f).sp)
                    }
                }
            }
        }
    }
}

/** The genres' order, three pills across the top of their tiles (shelf-pick.css .g-sort): the one chosen in the brass. */
@Composable
private fun GenreSorts(shelf: ShelfControl, sort: String, rem: Dp) {
    Row(Modifier.padding(bottom = rem * 0.2f), horizontalArrangement = Arrangement.spacedBy(rem * 0.4f)) {
        ShelfChoices.GENRE_SORTS.forEachIndexed { i, (id, name) ->
            val on = sort == id
            Box(Modifier.parity("shelf-choices.genre-sort", Contract.label("shelf-choices.genre-sort", i)).heightIn(min = rem * 2.2f).clip(CircleShape)
                .then(if (on) Modifier.background(paletteColor(Palette.ACCENT)) else Modifier.border(1.dp, SHELF_BRASS_DIM, CircleShape))
                .clickable { shelf.sortGenres(id) }.padding(horizontal = rem * 0.95f), contentAlignment = Alignment.Center) {
                Text(name, color = paletteColor(if (on) Palette.ON_ACCENT else Palette.TEXT), fontSize = (rem.value * 0.82f).sp, fontWeight = FontWeight.W700)
            }
        }
    }
}

/** A tile's ground: its outline, in the brass with a soft ring when chosen, faint when it would bring nothing, and its tick. */
@Composable
private fun TileBox(modifier: Modifier, t: ShelfTile, rem: Dp, radius: Dp, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    val brass = paletteColor(Palette.ACCENT)
    val soft = paletteColor(Palette.ACCENT_SOFT)
    // chosen: a brass outline with a soft ring round it (shelf-pick.css: 0 0 0 1px, 0 0 0 5px), the ground warmed a little
    val ring = if (!t.chosen) Modifier else Modifier.drawBehind {
        val out = 5.dp.toPx()
        drawRoundRect(soft, Offset(-out, -out), Size(size.width + out * 2, size.height + out * 2), CornerRadius(radius.toPx() + out))
    }
    val ground = if (t.chosen) Modifier.background(lerp(paletteColor(Palette.BG_ELEV), brass, 0.09f), shape).border(1.dp, brass, shape)
    else Modifier.background(paletteColor(Palette.BG_ELEV), shape).border(1.dp, paletteColor(Palette.BORDER), shape)
    Box(modifier.then(ring).clip(shape).then(ground).alpha(if (t.zero && !t.chosen) 0.4f else 1f)) {
        content()
        if (t.chosen) Box(Modifier.align(Alignment.TopEnd).padding(rem * 0.3f).size(rem * 1.1f).clip(CircleShape).background(brass), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.dv_tick), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(rem * 0.7f))
        }
    }
}

/** A genre's or a label's tile: a fan of three of its covers, its name and how many it would bring. */
@Composable
private fun BoxScope.FanFace(t: ShelfTile, rem: Dp, client: () -> ServerClient?) {
    Box(Modifier.padding(rem * 0.8f).fillMaxWidth(0.4f).aspectRatio(1f)) {
        t.fan.forEachIndexed { n, k ->
            val cover = rememberTileCover(k, 300, client)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { translationX = size.width * 0.42f * n; rotationZ = listOf(-7f, -1f, 6f)[n] }.shadow(4.dp, RoundedCornerShape(2.dp))
                .clip(RoundedCornerShape(2.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
                cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
            }
        }
    }
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = rem * 0.8f, vertical = rem * 0.75f), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.name, Modifier.weight(1f), color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.95f).sp, fontWeight = FontWeight.W800, lineHeight = (rem.value * 1.1f).sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(t.count?.toString().orEmpty(), color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.76f).sp, fontWeight = FontWeight.W600)
    }
}

/** A letter's, a decade's, a kind's, a genre's or a format's tile: its word in the middle (the serif for a letter or a decade; a genre's or a format's on up to [lines]), its count in the corner. */
@Composable
private fun BoxScope.WordFace(t: ShelfTile, rem: Dp, ratio: Float, serif: Boolean, lines: Int = 1) {
    Text(t.name, Modifier.align(Alignment.Center).padding(horizontal = rem * 0.5f), color = paletteColor(Palette.TEXT), fontFamily = if (serif) youngSerif else null,
        fontSize = (rem.value * if (!serif) 0.95f else if (ratio > 1f) 1.3f else 1.55f).sp, fontWeight = if (serif) FontWeight.W400 else FontWeight.W800, maxLines = lines,
        lineHeight = (rem.value * 1.1f).sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    Text(t.count?.toString().orEmpty(), Modifier.align(Alignment.BottomEnd).padding(end = rem * 0.4f, bottom = rem * 0.3f), color = paletteColor(Palette.TEXT_FAINT),
        fontSize = (rem.value * 0.62f).sp, fontWeight = FontWeight.W600)
}

/** An order's tile: its name, and its line on what it does; Random's shuffle mark until it is chosen. */
@Composable
private fun BoxScope.OrderFace(t: ShelfTile, rem: Dp) {
    Column(Modifier.align(Alignment.BottomStart).padding(start = rem * 0.8f, top = rem * 0.75f, end = rem * 2.4f, bottom = rem * 0.75f)) {
        Text(t.name, color = paletteColor(Palette.TEXT), fontSize = (rem.value * 0.95f).sp, fontWeight = FontWeight.W800)
        Text(t.note, Modifier.padding(top = rem * 0.15f), color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.8f).sp, lineHeight = (rem.value * 1.08f).sp)
    }
    if (t.id == "random" && !t.chosen) Icon(painterResource(R.drawable.tn_reshuffle), contentDescription = null, tint = paletteColor(Palette.ACCENT_TEXT),
        modifier = Modifier.align(Alignment.TopEnd).padding(rem * 0.8f).size(rem * 1.3f))
}
