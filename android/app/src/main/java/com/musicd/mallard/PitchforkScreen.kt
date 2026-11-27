package com.musicd.mallard

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PitchforkHit
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The listings read this session (the page's listCache): only a listing with reviews in it, so an empty answer is asked again. */
internal object PitchforkKept {
    val lists = HashMap<String, List<PitchforkHit>>()
}

private val GOLD = Color(0xFFD4A017)
private val ON_GOLD = Color(0xFF1A1000)

/**
 * Pitchfork natively (the group B of old/plan-android-native.md, B3; bug list item 70), drawn as the page's magazine
 * (docs/specs/screens.json, pitchfork and pitchfork-review; pitchfork.js, style.css .pf-*): over the whole screen, its title
 * and ✕ with the tabs Latest Reviews and Best New Music pinned at its head; the reviews as a woven mosaic three across, every
 * third a large one (left, then right), each with its score (gold for Best New Music), BNM, and the album and artist over the
 * foot of its cover. A review's page: ‹ Back, the cover beside the album, the artist and the score, the note that the review
 * is read on pitchfork.com, then Read on Pitchfork (the browser), Open in your library (the album's native page, when the
 * library has it) and Find on Qobuz (the app's Qobuz browser, its search seeded; item 73). [start]: a review opened straight away (the
 * search's), its list read when Back comes to it.
 */
@Composable
internal fun PitchforkScreen(prefs: WallPrefs, start: PitchforkHit?, client: () -> ServerClient?, onClose: () -> Unit, onAlbum: (Long) -> Unit, onQobuz: (String) -> Unit) {
    var tab by remember { mutableStateOf("latest") }
    var review by remember { mutableStateOf(start) }
    var list by remember { mutableStateOf<List<PitchforkHit>?>(null) }
    var failed by remember { mutableStateOf(false) }
    BackHandler { if (review != null) review = null else onClose() }
    // Failed, asked again when the server answers again or in a while (item 157).
    var asks by remember { mutableStateOf(0) }
    AskAgain(failed && review == null) { asks++ }
    LaunchedEffect(tab, review == null, asks) {
        if (review != null) return@LaunchedEffect
        failed = false
        PitchforkKept.lists[tab]?.let { list = it; return@LaunchedEffect }
        list = null
        val c = client() ?: return@LaunchedEffect
        val got = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.pitchforkReviews(tab) }.getOrNull() }
        if (got == null) { failed = true; list = emptyList(); return@LaunchedEffect }
        if (got.isNotEmpty()) PitchforkKept.lists[tab] = got
        list = got
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        // The pinned head: the title and the close disc over a brass hairline, the tabs under it (not over a review).
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 2.dp)) {
            val line = paletteColor(Palette.ACCENT).copy(alpha = 0.12f)
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val size = (24 * prefs.title).sp
                Text("Pitchfork", Modifier.parity("pitchfork.title"), color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = size, lineHeight = size, letterSpacing = 0.01.em)
                Box(Modifier.parity("pitchfork.close", Contract.label("pitchfork.close")).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.np_close), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.padding(top = 11.dp).fillMaxWidth().height(1.dp).background(line))
            if (review == null) Row(Modifier.padding(start = 6.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                PfTab(Modifier.parity("pitchfork.latest", Contract.label("pitchfork.latest")).clip(CircleShape).clickable { tab = "latest" }, "Latest Reviews", tab == "latest")
                PfTab(Modifier.parity("pitchfork.best", Contract.label("pitchfork.best")).clip(CircleShape).clickable { tab = "best" }, "Best New Music", tab == "best")
            } else Spacer(Modifier.height(10.dp))
        }
        if (review == null && list == null) Text("Loading…", Modifier.parity("pitchfork.status").padding(start = 20.dp, top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        val shown = review
        if (shown != null) PfReview(shown, client, onBack = { review = null }, onAlbum = onAlbum, onQobuz = onQobuz)
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            val cell = (maxWidth - 40.dp - 16.dp) / 3
            val items = list.orEmpty()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list != null && items.isEmpty()) item("empty") {
                    Text(if (failed) "Couldn’t load Pitchfork right now. Try again in a little while." else "No reviews to show right now.",
                        Modifier.parity("pitchfork.empty").fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                }
                // Three to a block: the large one (left in one block, right in the next) and two small ones stacked beside it.
                items((items.size + 2) / 3, key = { "b$it" }) { b ->
                    val big = items[b * 3]; val smalls = items.drop(b * 3 + 1).take(2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        @Composable fun Smalls() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (s in smalls) PfCard(s, cell, false) { review = s } }
                        if (b % 2 == 1) Smalls()
                        PfCard(big, cell * 2 + 8.dp, true) { review = big }
                        if (b % 2 == 0) Smalls()
                    }
                }
            }
        }
    }
}

/** A tab (.qobuz-tab): a pill 32 tall, dim on an edge, the one on in brass with a brass edge. */
@Composable
private fun PfTab(modifier: Modifier, word: String, on: Boolean) {
    Box(modifier.height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(word, color = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/** A review's cover, or the faint ♪ where it has none or it won't load. */
@Composable
private fun PfArt(cover: String?, note: Dp) {
    val image = rememberWebImage(cover)
    if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("♪", color = paletteColor(Palette.TEXT_FAINT).copy(alpha = 0.5f), fontSize = note.value.sp) }
}

/** A score chip (.pf-score): white on black at .72, gold for Best New Music; [big] on a large tile and a review's page. */
@Composable
private fun PfScore(modifier: Modifier, text: String, gold: Boolean, big: Boolean) {
    Box(modifier.defaultMinSize(minWidth = 30.dp).clip(CircleShape).background(if (gold) GOLD else Color.Black.copy(alpha = 0.72f))
        .padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 3.dp else 2.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (gold) ON_GOLD else Color.White, fontSize = if (big) 15.sp else 12.sp, lineHeight = if (big) 21.sp else 17.sp, fontWeight = FontWeight.W700)
    }
}

/** The gold BNM badge (.pf-bnm), or its words in full on a review's page. */
@Composable
private fun PfBnm(modifier: Modifier, word: String) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(GOLD).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(word, color = ON_GOLD, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.W800, letterSpacing = 0.04.em)
    }
}

/** A review's tile: a square of [side], its cover, its score and BNM at the top corners, its album and artist over its foot. */
@Composable
private fun PfCard(r: PitchforkHit, side: Dp, big: Boolean, onTap: () -> Unit) {
    val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 4f))
    Box(Modifier.parity("pitchfork.review", Contract.label("pitchfork.review", 0, r.album)).size(side).clip(RoundedCornerShape(10.dp)).background(paletteColor(Palette.BG_ELEV_2)).clickable(onClick = onTap)) {
        PfArt(r.cover, 32.dp)
        r.scoreText?.let { PfScore(Modifier.parity("pitchfork.score").align(Alignment.TopStart).padding(6.dp), it, r.bestNew, big) }
        if (r.bestNew) PfBnm(Modifier.parity("pitchfork.bnm").align(Alignment.TopEnd).padding(6.dp), "BNM")
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.62f), 0.7f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black.copy(alpha = 0.88f)))
            .padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 7.dp)) {
            val album = if (big) 16.sp else 12.5.sp
            Text(r.album, color = Color.White, fontSize = album, lineHeight = album * 1.2f, fontWeight = FontWeight.W700, maxLines = 2, overflow = TextOverflow.Ellipsis, style = shadow)
            Text(r.artist, Modifier.padding(top = 1.dp), color = Color.White.copy(alpha = 0.85f), fontSize = if (big) 12.5.sp else 11.sp, lineHeight = if (big) 17.sp else 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow)
        }
    }
}

/** A review's page: its head, the note, and what can be done with it (the library's album looked for as it opens). */
@Composable
private fun PfReview(r: PitchforkHit, client: () -> ServerClient?, onBack: () -> Unit, onAlbum: (Long) -> Unit, onQobuz: (String) -> Unit) {
    val context = LocalContext.current
    var match by remember(r.url) { mutableStateOf<Long?>(null) }
    LaunchedEffect(r.url) {
        val c = client() ?: return@LaunchedEffect
        match = withContext(Dispatchers.IO) { runCatching { LibraryClient { c.getJson(it) }.pitchforkMatch(r) }.getOrNull() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("back") {
            Text("‹ Back", Modifier.parity("pitchfork-review.back", Contract.label("pitchfork-review.back")).clickable(onClick = onBack).padding(horizontal = 2.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondary, fontSize = 15.sp, lineHeight = 21.sp)
        }
        item("head") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.parity("pitchfork-review.art").size(120.dp).clip(RoundedCornerShape(10.dp)).background(paletteColor(Palette.BG_ELEV_2))) { PfArt(r.cover, 32.dp) }
                Column(Modifier.weight(1f)) {
                    Text(r.album, Modifier.parity("pitchfork-review.album"), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.W700)
                    Text(r.artist, Modifier.parity("pitchfork-review.artist").padding(top = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        r.scoreText?.let { PfScore(Modifier.parity("pitchfork-review.score"), it, r.bestNew, true) }
                        if (r.bestNew) PfBnm(Modifier.parity("pitchfork-review.bnm"), "Best New Music")
                    }
                }
            }
        }
        // The written review is never shown in the app (UK law, as on the page): it is read on pitchfork.com.
        item("note") {
            Text(buildAnnotatedString {
                append("The written review can’t be shown here — tap ")
                withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append("Read on Pitchfork") }
                append(" to read it on pitchfork.com.")
            }, Modifier.parity("pitchfork-review.note").padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 24.sp, fontStyle = FontStyle.Italic)
        }
        item("actions") {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PfAction(Modifier.parity("pitchfork-review.read", Contract.label("pitchfork-review.read")).clip(ACTION)
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } }, "Read on Pitchfork ↗", MaterialTheme.colorScheme.secondary, Color.Transparent, Color.Transparent)
                match?.let { offset ->
                    PfAction(Modifier.parity("pitchfork-review.open", Contract.label("pitchfork-review.open")).clip(ACTION).clickable { onAlbum(offset) },
                        "▶ Open in your library", MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, FontWeight.W600)
                }
                PfAction(Modifier.parity("pitchfork-review.qobuz", Contract.label("pitchfork-review.qobuz")).clip(ACTION).clickable { onQobuz(r.query) },
                    "Find on Qobuz", MaterialTheme.colorScheme.onSurface, paletteColor(Palette.BG_ELEV_2), MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private val ACTION = RoundedCornerShape(10.dp)

/** A review's action (.pf-action): the page's width, 11 by 14, corners of 10, 15px in the middle. */
@Composable
private fun PfAction(modifier: Modifier, word: String, ink: Color, ground: Color, edge: Color, weight: FontWeight = FontWeight.W400) {
    Box(modifier.fillMaxWidth().background(ground).border(1.dp, edge, ACTION).padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(word, color = ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = weight, maxLines = 1)
    }
}
