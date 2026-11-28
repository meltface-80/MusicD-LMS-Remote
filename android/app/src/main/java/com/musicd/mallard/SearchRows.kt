package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PitchforkHit
import com.musicd.mallard.client.ServiceAlbum
import com.musicd.mallard.client.ServiceArtist
import com.musicd.mallard.client.WallPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * The search's parts (docs/specs/screens.json, search; style.css's search
 * section, the hifi palette, a phone's sizes as tools/quality/page-metrics.js
 * measures them): Home's greeting above the results, a section's small
 * heading, the library's artists as chips, a service's artists as
 * a strip of portraits and its albums as tiles with their favourite as + or ✓,
 * Pitchfork's reviews as rows with the score. SearchScreen.kt lays them out.
 */

/** Home's greeting, which stays above the search's results (the search is Home's): the date in small brass capitals, the time of day in the serif. */
@Composable
internal fun SearchGreeting() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Date() } }
    val hour = Calendar.getInstance().also { it.time = now }.get(Calendar.HOUR_OF_DAY)
    val words = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    val locale = Locale.getDefault()
    val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(now)
    // Under the bar at the page's 6, and 26 above the results (the grid's own 12 is part of that).
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)) {
        Text(date.uppercase(locale), Modifier.parity("search.greeting-date"), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.W700, letterSpacing = 0.18.em)
        Text(words, Modifier.parity("search.greeting").padding(top = 6.dp).lineBox(34.sp, 37.4.sp, Face.YOUNG_SERIF), color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontWeight = FontWeight.W400,
            fontSize = 34.sp, lineHeight = 37.4.sp, letterSpacing = 0.01.em)
    }
}

/** A section's heading (.search-section-header): small tracked capitals, faint; 4 above the first of them, 14 above the rest, 6 under. */
@Composable
internal fun SearchSection(text: String, first: Boolean) {
    Text(text.uppercase(), Modifier.parity("search.section").fillMaxWidth().padding(top = if (first) 4.dp else 14.dp, bottom = 6.dp), color = paletteColor(Palette.TEXT_FAINT),
        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.W700, letterSpacing = 0.08.em, maxLines = 1)
}

/** The library's artists as chips (.search-chip-row): wrapping, 8 apart, 6 under; each a tap ([onTap]) with its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchChips(names: List<String>, onTap: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in names) {
            Box(Modifier.parity("search.artist", Contract.label("search.artist", 0, n)).clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .clickable { onTap(n) }.padding(horizontal = 14.dp, vertical = 6.dp)) { ChipText(n) }
        }
    }
}

/** A chip's name: 14 in the text colour, one line. */
@Composable
private fun ChipText(n: String) {
    Text(n, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1)
}

/** A service's artists as a strip (.ext-search-artists, the browsers' .qobuz-artist-chip): a portrait of 56 over the name, 68 wide, 12 apart. */
@Composable
internal fun ServiceArtistStrip(artists: List<ServiceArtist>, onTap: (ServiceArtist) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(artists.size) { i ->
            val ar = artists[i]
            Column(Modifier.parity("search.service-artist service-browser.artist", Contract.label("search.service-artist", 0, ar.name)).width(68.dp).clickable { onTap(ar) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val img = rememberWebImage(ar.image)
                Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(ar.name, Modifier.widthIn(max = 68.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A service's album as a tile of the grid (the browsers' .qobuz-tile): the cover with the favourite in its corner, +
 * or ✓ ([fav]; a tap [onFav]), the title with its version faint after it, the artist; a tap on the tile [onOpen].
 */
@Composable
internal fun ServiceAlbumTile(a: ServiceAlbum, fav: Boolean, prefs: WallPrefs, onFav: () -> Unit, onOpen: () -> Unit, client: (() -> com.musicd.mallard.client.ServerClient?)? = null) {
    val name = if (a.service == "tidal") "Tidal" else "Qobuz"
    Column(Modifier.parity("search.service-album service-browser.album", Contract.label("search.service-album", if (a.artist.isEmpty()) 1 else 0, a.title, a.artist)).fillMaxWidth().clickable(onClick = onOpen)) {
        // An address of the service's, or (MusicD LMS) the server's own cover by its image key.
        val cover = rememberWebImage(a.image) ?: (if (client != null) rememberTileCover(a.imageKey, 400, client) else null)
        val corners = RoundedCornerShape(12.dp)
        Box(Modifier.parity("search.service-album-art service-browser.album-art").fillMaxWidth().aspectRatio(1f).clip(corners).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, Color.White.copy(alpha = 0.06f), corners)) {
            if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("♪", Modifier.align(Alignment.Center), color = paletteColor(Palette.TEXT_FAINT), fontSize = 48.sp, lineHeight = 48.sp)
            // The favourite, top right: a dark disc with +, brass with ✓ once it is one (.qobuz-tile-fav).
            Box(Modifier.parity("search.service-fav service-browser.fav", Contract.label("search.service-fav", if (fav) 1 else 0, name)).align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                .background(if (fav) MaterialTheme.colorScheme.primary else Color(0x9E0A0E14)).clickable(onClick = onFav), contentAlignment = Alignment.Center) {
                Text(if (fav) "✓" else "+", color = if (fav) Color(0xFF1A1A1A) else Color.White.copy(alpha = 0.92f), fontSize = if (fav) 14.sp else 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.W600)
            }
        }
        val title = (12 * prefs.text).sp; val titleLine = (15 * prefs.text).sp
        val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        val text = buildAnnotatedString {
            append(a.title)
            if (a.version.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.W400, color = faint)) { append(" " + a.version) }
        }
        Text(text, Modifier.parity("search.service-album-title service-browser.album-title").padding(top = 11.dp).lineBox(title, titleLine), color = MaterialTheme.colorScheme.onSurface,
            fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(a.artist, Modifier.parity("search.service-album-artist service-browser.album-artist").padding(top = 1.dp).lineBox((10.5 * prefs.text).sp, (13.65 * prefs.text).sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = (10.5 * prefs.text).sp, lineHeight = (13.65 * prefs.text).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Pitchfork review as a row across the grid (.ext-search-row): its cover small, the album over the artist, the score in a pill (gold for Best New Music). */
@Composable
internal fun ReviewRow(r: PitchforkHit, onOpen: () -> Unit) {
    Row(Modifier.parity("search.review", Contract.label("search.review", if (r.artist.isEmpty()) 1 else 0, r.album, r.artist)).fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(paletteColor(Palette.BG_ELEV_2)).clickable(onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val cover = rememberWebImage(r.cover)
        Box(Modifier.parity("search.review-art").size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(r.album, Modifier.parity("search.review-title"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.artist, Modifier.parity("search.review-artist"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        r.scoreText?.let { s ->
            Box(Modifier.parity("search.review-score").widthIn(min = 30.dp).clip(RoundedCornerShape(999.dp)).background(if (r.bestNew) Color(0xFFD4A017) else Color(0xB8000000))
                .padding(horizontal = 7.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text(s, color = if (r.bestNew) Color(0xFF1A1000) else Color.White, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.W700, maxLines = 1)
            }
        }
    }
}
