package com.musicd.mallard

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.AlbumWords
import com.musicd.mallard.client.ArtistBio
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Review
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The album's page on a tablet held sideways (item 218, the owner: the same three columns as the page's album view,
 * item 214; a phone, either way round, and a tablet upright keep the one column). AlbumScreen lays the columns out:
 * on the left its head, its buttons, its credits and the rows (more by, appears on, similar); in the middle these
 * words; on the right its tracks. [tabletSideways] says when (core's AlbumWords).
 */
@Composable
internal fun tabletSideways(): Boolean {
    val c = LocalConfiguration.current
    return AlbumWords.sideways(c.smallestScreenWidthDp, c.orientation == Configuration.ORIENTATION_LANDSCAPE)
}

private val WORDS = TextStyle(fontSize = 14.sp, lineHeight = 22.4.sp)

/**
 * The middle column: About this album ([review]) and about its artist (/api/artist-bio, read here), fitted to the
 * column as the page's are — each as tall as its words, shortened together when they don't both fit (core's
 * AlbumWords.share) — with Read more under one cut short, which opens the whole of both, the column scrolling; Read
 * less fits them again. [onUrl] opens the review's page.
 */
@Composable
internal fun AlbumWordsColumn(a: Album, review: Review?, client: () -> ServerClient?, onUrl: (String) -> Unit, modifier: Modifier) {
    val artist = a.album.subtitle
    var bio by remember(a.album.title, artist) { mutableStateOf<ArtistBio?>(null) }
    LaunchedEffect(a.album.title, artist) {
        val c = client() ?: return@LaunchedEffect
        if (artist.isBlank()) return@LaunchedEffect
        bio = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.artistBio(artist, a.album.title) }.getOrNull() }
    }
    var reading by remember(a.album.title, artist) { mutableStateOf(false) }
    val reviewText = review?.text.orEmpty()
    val bioText = bio?.text.orEmpty()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        // Each section's own room besides its words: its head over a hairline, where they are from, and Read more.
        val besides = with(density) { 96.dp.toPx() }
        val lineHeight = with(density) { 22.4.sp.toPx() }
        val sections = (if (reviewText.isNotEmpty()) 1 else 0) + (if (bioText.isNotEmpty()) 1 else 0)
        val room = ((constraints.maxHeight - besides * sections) / lineHeight).toInt().coerceAtLeast(0)
        fun lines(t: String) = if (t.isEmpty()) 0 else measurer.measure(t, WORDS, constraints = Constraints(maxWidth = width)).lineCount
        val reviewLines = remember(reviewText, width) { lines(reviewText) }
        val bioLines = remember(bioText, width) { lines(bioText) }
        val (reviewShown, bioShown) = AlbumWords.share(reviewLines, bioLines, room)
        val cut = AlbumWords.cut(reviewLines, reviewShown) || AlbumWords.cut(bioLines, bioShown)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = reading)) {
            if (reviewText.isNotEmpty()) Words("About this album", reviewText, review?.attribution, if (reading) Int.MAX_VALUE else reviewShown,
                Modifier.parity("album.review"), more = if (cut || reading) reading else null, onMore = { reading = !reading })
            if (review != null) {
                val link = review.linkText
                val url = review.url
                if (link != null && url != null) TextButton(onClick = { onUrl(url) }, contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.parity("album.review-source", Contract.label("album.review-source", 0, link))) {
                    Text(link, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            if (bioText.isNotEmpty()) Words("About $artist", bioText, bio?.source?.let { "Bio: $it" }, if (reading) Int.MAX_VALUE else bioShown,
                Modifier.parity("album.artist-bio"), more = if (cut || reading) reading else null, onMore = { reading = !reading })
        }
    }
}

/** A write-up: its head, as the page's section-label, its words in [lines] lines, where they are from, and Read more ([more]: reading, or none). */
@Composable
private fun Words(head: String, text: String, from: String?, lines: Int, modifier: Modifier, more: Boolean?, onMore: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(head.uppercase(), Modifier.padding(bottom = 8.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 0.16.em)
        HorizontalDivider(Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.outline)
        Text(text, color = paletteColor(Palette.MODAL_TEXT), style = WORDS, maxLines = lines.coerceAtLeast(1), overflow = TextOverflow.Ellipsis)
        from?.let { Text(it, Modifier.padding(top = 6.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.5.sp) }
        if (more != null) TextButton(onClick = onMore, contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.parity("album.read-more", Contract.label("album.read-more", if (more) 1 else 0))) {
            Text(Contract.label("album.read-more", if (more) 1 else 0), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, fontWeight = FontWeight.W600)
        }
    }
}
