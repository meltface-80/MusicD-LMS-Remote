package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.ArtistSimilar
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.SimilarAlbum
import com.musicd.mallard.client.WallPrefs

/**
 * The foot of Overview (bug list item 140; the page's artist-foot.js, .artist-similar): similar artists (item 137), a
 * row of round pictures each opening their page, then similar albums, Home's row of the page's album tiles (TileArt,
 * AlbumTile's type) each opening the album, the library's alone (the server's). A row with nothing in it isn't there.
 * Each heading as Discography's, its content 16 under it.
 */
@Composable
internal fun ArtistFoot(similar: ArtistSimilar?, albums: List<SimilarAlbum>, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, onArtist: (String) -> Unit,
                        onAlbum: (Int) -> Unit) {
    val acts = similar?.artists.orEmpty()
    if (acts.isEmpty() && albums.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
        if (acts.isNotEmpty()) {
            FootTitle("Similar artists")
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 14.dp)) {
                items(acts.size) { i -> SimilarAct(acts[i], client, onArtist) }
            }
        }
        if (albums.isNotEmpty()) {
            FootTitle("Similar albums")
            Spacer(Modifier.height(12.dp))
            ArtistRow(albums.size, key = { it }) { i ->
                val al = albums[i]
                SimilarAlbumTile(al, prefs, coverPx, client) { onAlbum(i) }
            }
        }
    }
}

/** A section's small heading, as every heading of the page (ArtistSectionTitle). */
@Composable
private fun FootTitle(title: String) = ArtistSectionTitle(title)

/** A similar act: their picture (or first letter) in a circle over the name. */
@Composable
private fun SimilarAct(a: ArtistSimilar.Act, client: () -> ServerClient?, onArtist: (String) -> Unit) {
    Column(Modifier.parity("artist.similar", Contract.label("artist.similar", 0, a.name)).width(96.dp).clickable { onArtist(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.parity("artist.similar-art").fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2)), contentAlignment = Alignment.Center) {
            val art = rememberTileCover(a.photoKey, 300, client)
            if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(a.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 26.sp, fontWeight = FontWeight.W600)
        }
        Text(a.name, Modifier.parity("artist.similar-name").padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * A similar album, the library's (the server sends no other), as the page's album tile (the page's buildAlbumTile): the
 * tile's cover and badges (TileArt), the title in two lines and who by in one at UI Settings' size, Home's width.
 */
@Composable
private fun SimilarAlbumTile(al: SimilarAlbum, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, onTap: () -> Unit) {
    val a = al.album ?: AlbumSummary(offset = -1, title = al.title, subtitle = al.artist, imageKey = al.imageKey, quality = null, year = null, discs = null, source = null)
    val cover = rememberTileCover(a.imageKey, coverPx, client)
    val title = (12 * prefs.text).sp; val titleLine = (15 * prefs.text).sp
    val artist = (10.5 * prefs.text).sp; val artistLine = (13.65 * prefs.text).sp
    Column(Modifier.parity("artist.similar-album", Contract.label("artist.similar-album", 0, a.title)).width((144 * prefs.tile).dp).clickable(onClick = onTap)) {
        Box(Modifier.parity("artist.similar-album-art").fillMaxWidth().aspectRatio(1f)) { TileArt(a, cover, false, Modifier.fillMaxSize(), today = null, dl = null) }
        Text(a.title.ifEmpty { "Untitled" }, Modifier.parity("artist.similar-album-title").padding(top = 11.dp).lineBox(title, titleLine), color = MaterialTheme.colorScheme.onSurface,
            fontSize = title, lineHeight = titleLine, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val by = al.artist.ifEmpty { a.subtitle }
        if (by.isNotEmpty()) Text(by, Modifier.parity("artist.similar-album-artist").padding(top = 1.dp).lineBox(artist, artistLine), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = artist, lineHeight = artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
