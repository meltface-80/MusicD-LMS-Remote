package com.musicd.mallard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.GenreCard
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Pick
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.first

/*
 * Home's own tiles (bug list item 57; the page's home-render.js, home-rows.js
 * and device-power.js, style.css in the hifi palette): the Random Album tile
 * with its turning disc, a Smart Pick's card, a genre
 * card, and the row's one line where it has no tiles. An album's tile is the
 * walls' (AlbumTile.kt), with ★ Today on today's album and a download's
 * badge on the app's own row.
 */

/** How wide a cover Home's tiles ask for (the page's tileImgSize for a 144 tile): in device pixels to the next 100, within 200 and 500. */
@Composable
internal fun homeCoverPx(prefs: WallPrefs): Int {
    val density = LocalDensity.current.density
    return (ceil(144 * prefs.tile * min(2f, density) / 100f) * 100).toInt().coerceIn(200, 500)
}

/** A row's one line where it has no tiles (.home-carousel-empty): faint, 13px, 8 above and below, 2 in from the gutter. */
@Composable
internal fun RowNote(text: String) {
    Text(text, Modifier.parity("home.row-empty").padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp)
}

/**
 * A row's tiles side by side, scrolling sideways (the hifi .home-carousel): 16 apart, from the gutter, bleeding to the
 * screen's edge. [tiles] drawn as they are now, a copy: a tile taken out of the row between two frames (an album off
 * Listen later) is never asked for by a place it no longer has (bug list item 84). [content] has each tile and its place.
 */
@Composable
internal fun <T> Carousel(tiles: List<T>, key: (T) -> Any, content: @Composable (Int, T) -> Unit) {
    val shown = tiles.toList()
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(shown, key = { _, t -> key(t) }) { i, t -> content(i, t) }
    }
}

/**
 * Random Album's disc (the page's .unheard-disc and playUnheard): two slow turns as Home is drawn, then still; while
 * an album is being found it speeds up (seven and a half times, eased over 0.6 s, the page's rampDisc) and turns on,
 * then slows and comes to rest at the end of the turn it is on, so the angle never jumps.
 */
internal class DiscSpin {
    var angle by mutableFloatStateOf(0f)
        private set
    var finding by mutableStateOf(false)
        private set
    private var speed = 0f
    private var from = 0f; private var to = 0f; private var rampAt = 0L
    private var stopAt: Float? = 720f

    init { ramp(SLOW) }
    private fun ramp(target: Float) { from = speed; to = target; rampAt = System.currentTimeMillis() }
    fun start() { finding = true; stopAt = null; ramp(SLOW * 7.5f) }
    fun stop() { finding = false; ramp(SLOW); stopAt = (floor(angle / 360f) + 1) * 360f }

    /** Turns the disc, a frame at a time while it moves. */
    suspend fun run() {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = (now - last).coerceIn(0L, 100L); last = now
            val k = ((System.currentTimeMillis() - rampAt) / 600f).coerceIn(0f, 1f)
            speed = from + (to - from) * (1 - (1 - k).pow(3))
            val stop = stopAt
            // Still: no frames asked for until a Random Album sets it turning again (item 158: ten a second, all the time Home was up).
            if (stop != null && angle >= stop) { angle = stop; speed = 0f; from = 0f; to = 0f; snapshotFlow { finding }.first { it }; last = withFrameMillis { it }; continue }
            angle += speed * dt
            if (stop != null && angle > stop) angle = stop
        }
    }

    companion object { const val SLOW = 360f / 6000f }
}

/** The Random Album tile (the page's buildUnheardTile, .unheard-art): the dashed brass sleeve with the disc turning in it, its name under it in brass. */
@Composable
internal fun RandomAlbumTile(prefs: WallPrefs, spin: DiscSpin, onTap: () -> Unit) {
    val brass = MaterialTheme.colorScheme.primary
    val edge = brass.copy(alpha = 0.6f)
    val title = (12 * prefs.text).sp; val titleLine = (15 * prefs.text).sp
    Column(Modifier.parity("home.random-album", Contract.label("home.random-album")).width((144 * prefs.tile).dp).clickable(onClick = onTap)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).drawBehind {
            val w = 1.dp.toPx(); val dash = 3.dp.toPx()
            drawRoundRect(edge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(12.dp.toPx()), Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))))
        }, contentAlignment = Alignment.Center) {
            // The page's disc: a 24-unit drawing, its rings and two short arcs, at 52% of the sleeve.
            Canvas(Modifier.fillMaxSize(0.52f).graphicsLayer { rotationZ = spin.angle }) {
                val u = size.width / 24f
                val stroke = Stroke(1.6f * u, cap = StrokeCap.Round)
                val c = Offset(12 * u, 12 * u)
                drawCircle(brass, 10 * u, c, style = stroke)
                drawCircle(brass, 3.2f * u, c, style = stroke)
                drawCircle(brass, 0.9f * u, c)
                val r = 6.6f * u
                val arc = Size(2 * r, 2 * r); val at = Offset(c.x - r, c.y - r)
                drawArc(brass, 213.7f, 33.2f, false, at, arc, style = stroke)
                drawArc(brass, 33.7f, 33.2f, false, at, arc, style = stroke)
            }
        }
        Text("Random Album", Modifier.padding(top = 11.dp).lineBox(title, titleLine), color = MaterialTheme.colorScheme.secondary, fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A Smart Pick on Home (the page's smartPickCard, not full): the cover with the artist over the album under it; a tap opens the album, or Smart Picks. */
@Composable
internal fun PickCardHome(p: Pick, prefs: WallPrefs, client: () -> ServerClient?, onTap: () -> Unit) {
    val cover = if (p.imageKey != null) rememberTileCover(p.imageKey, 400, client) else rememberImage(p.image, client)
    val corners = RoundedCornerShape(12.dp)
    Column(Modifier.parity("home.pick", Contract.label("home.pick", 0, p.artist, p.album)).width((156 * prefs.tile).dp).clickable(onClick = onTap)) {
        Box(Modifier.parity("home.pick-art").fillMaxWidth().aspectRatio(1f).clip(corners).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), corners)) {
            cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        // The page's .pick-artist and .pick-album: 13px at 600 and 12px dim, each at the font's own line, 6 under the cover.
        Text(p.artist, Modifier.parity("home.pick-artist").padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = (13 * prefs.text).sp, lineHeight = (18 * prefs.text).sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.album, Modifier.parity("home.pick-album"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (12 * prefs.text).sp, lineHeight = (17 * prefs.text).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The six earthy grounds of the genre cards and their discs (the hifi .home-genre-card:nth-child), in the page's order. */
private val GENRE_GROUNDS = listOf(0xFF3B2A22L to 0xFFC97B5AL, 0xFF26382FL to 0xFF7FA38AL, 0xFF3A3324L to 0xFFC9A45CL, 0xFF2A3140L to 0xFF7D8FB3L, 0xFF402A2CL to 0xFFB87A86L, 0xFF2F3A27L to 0xFF9FAE6BL)

/** A genre card (the page's renderHomeGenres): the name in the serif at the card's foot, a soft disc off its top right corner, each card the next of six grounds. */
@Composable
internal fun GenreCardView(card: GenreCard, index: Int, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val (ground, disc) = GENRE_GROUNDS[index % 6]
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.parity("home.genre", Contract.label("home.genre", 0, card.label)).defaultMinSize(minHeight = 88.dp).clip(shape).background(paletteColor(ground))
        .border(1.dp, Color(0x1AEFE6D4), shape).clickable(onClick = onTap).drawBehind {
            drawCircle(paletteColor(disc).copy(alpha = 0.5f), 32.dp.toPx(), Offset(size.width + 14.dp.toPx(), 14.dp.toPx()))
        }.padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 18.dp), contentAlignment = Alignment.BottomStart) {
        Text(card.label, color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontWeight = FontWeight.W400, fontSize = 18.sp, lineHeight = 25.sp)
    }
}

/** The genre cards two to a row, 10 apart (the page's .home-genre-grid on a phone). */
@Composable
internal fun GenreGrid(cards: List<GenreCard>, onTap: (GenreCard) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in cards.indices step 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in r until minOf(r + 2, cards.size)) GenreCardView(cards[i], i, { onTap(cards[i]) }, Modifier.weight(1f))
            if (r + 1 >= cards.size) Spacer(Modifier.weight(1f))
        }
    }
}
