package com.musicd.mallard

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.ArtistAbout
import com.musicd.mallard.client.ArtistAct
import com.musicd.mallard.client.ArtistBio
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * An artist's page in tabs (the page's artist-tabs.js): Overview, Discography and About under who
 * they are (ArtistHero), as an album's page has its tabs (AlbumTabRow); every artist's page the same. Overview: their
 * albums as the grid's own tiles (AlbumTile) in Home's row (ArtistRow), See all opening Discography, then similar
 * artists and albums (ArtistFoot). Each section's heading sits as Discography's (BlockTitle in its grid): 30 under the
 * tabs' line, its content 16 under the heading.
 * Discography: the album grid (ArtistScreen's blocks), the default service's albums after the library's own
 * (serviceShelf, item 522). About: the biography, asked for only when the tab is first
 * opened, and a band's members or a person's bands.
 */

/** The tabs, in their order. */
internal val ARTIST_TABS = listOf("overview", "discography", "about")

/** The tabs' row: its words tracked out, the one open in the text's colour over a brass bar, centred. */
@Composable
internal fun ArtistTabRow(tab: String, onTab: (String) -> Unit) {
    val faint = paletteColor(Palette.TEXT_FAINT)
    val brass = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            ARTIST_TABS.forEachIndexed { i, t ->
                val on = t == tab
                Column(Modifier.parity("artist.tab", Contract.label("artist.tab", i)).width(IntrinsicSize.Max).clickable { onTab(t) }.padding(top = 12.dp)) {
                    Text(Contract.label("artist.tab", i).uppercase(), color = if (on) paletteColor(Palette.MODAL_TEXT) else faint, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1)
                    Box(Modifier.padding(top = 10.dp).height(2.dp).fillMaxWidth().background(if (on) brass else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** A section's small heading in capitals, as Discography's (BlockTitle: the page's .artist-section-header, 12 over it, 4 under). */
@Composable
internal fun ArtistSectionTitle(title: String, modifier: Modifier = Modifier) =
    Text(title.uppercase(), modifier.parity("artist.section").padding(top = 12.dp, bottom = 4.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp,
        lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.06.em)

/**
 * A row of the page's album tiles ([count] of them, [tile] each), Home's (Carousel): 16 apart, scrolling sideways across
 * the whole screen, its first tile at the page's gutter; the page's 14 each side given back for it to bleed into.
 */
@Composable
internal fun ArtistRow(count: Int, key: (Int) -> Any, tile: @Composable (Int) -> Unit) {
    val bleed = 14.dp
    LazyRow(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val extra = (bleed * 2).roundToPx()
        val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
    }, contentPadding = PaddingValues(horizontal = bleed), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(count, key = key) { i -> tile(i) }
    }
}

/** Overview's row: [albums] under "Albums" with See all ([onSeeAll]: Discography), each the grid's tile at Home's width; a tap opens it ([onOpen], its place). */
@Composable
internal fun OverviewAlbums(albums: List<AlbumSummary>, prefs: WallPrefs, coverPx: Int, client: () -> ServerClient?, onOpen: (Int) -> Unit, onSeeAll: () -> Unit) {
    if (albums.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp)) {   // the grid's 12 between the tabs and Discography's first heading
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {   // and between that heading and its tiles
            ArtistSectionTitle("Albums", Modifier.weight(1f))
            Text(Contract.label("artist.see-all"), Modifier.parity("artist.see-all", Contract.label("artist.see-all")).clickable(onClick = onSeeAll).padding(top = 12.dp, bottom = 4.dp, start = 12.dp),
                color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600)
        }
        ArtistRow(albums.size, key = { albums[it].offset }) { i -> Box(Modifier.width((144 * prefs.tile).dp)) { AlbumTile(albums[i], prefs, coverPx, client) { onOpen(i) } } }
    }
}

/**
 * Discography's albums on the default service that the library hasn't (item 522; the page's artist-discography.js): asked
 * once the library's are in ([ready]), so the page never waits on the service; nothing for no default service, none of
 * theirs, or the service not answering, and nothing once another artist is open. Each drawn as the search draws a
 * service's album (ServiceAlbumTile, its favourite too); a tap makes it the library's and opens its page ([onAlbum], its
 * offset), as the search's does. Under "More on {service}", ahead of block [before].
 */
@Composable
internal fun serviceShelf(asked: String, ready: Boolean, before: Int, prefs: WallPrefs, client: () -> ServerClient?, onAlbum: (Long) -> Unit): GridExtra? = null

/**
 * About: the biography ([bio]; [bioDone] once it has answered), then a band's members or a person's bands from [about],
 * or a line saying nothing more is known of them.
 */
@Composable
internal fun ArtistAboutPane(about: ArtistAbout?, bio: ArtistBio?, bioDone: Boolean, client: () -> ServerClient?, onArtist: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        bio?.let { BioText(it) }
        about?.let { a ->
            if (a.members.isNotEmpty()) Stints("Members", a.members, client, onArtist)
            if (a.memberOf.isNotEmpty()) Stints("Member of", a.memberOf, client, onArtist)
        }
        if (bioDone && bio == null && about?.members.isNullOrEmpty() && about?.memberOf.isNullOrEmpty())
            Text("Nothing more is known of them yet", Modifier.parity("artist.about-empty").fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
    }
}

/** The biography: clipped to five lines with Show more, and where it is from. */
@Composable
private fun BioText(bio: ArtistBio) {
    var more by remember(bio) { mutableStateOf(false) }
    var clipped by remember(bio) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(bio.text, Modifier.parity("artist.bio").fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 21.6.sp,
            maxLines = if (more) Int.MAX_VALUE else 5, onTextLayout = { if (!more) clipped = it.hasVisualOverflow })
        if (clipped || more) Text(Contract.label("artist.bio-more", if (more) 1 else 0), Modifier.parity("artist.bio-more", Contract.label("artist.bio-more", if (more) 1 else 0)).clickable { more = !more }.padding(top = 6.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W500)
        bio.source?.let { Text("Bio: $it", Modifier.parity("artist.bio-source").padding(top = 2.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 16.sp) }
    }
}

/**
 * A band's members or a person's bands (.artist-stints): the small heading with how many, open (on About,
 * out of the albums' way), a tap folding them (item 140); a row of round pictures that scrolls sideways.
 */
@Composable
private fun Stints(title: String, list: List<ArtistAbout.Stint>, client: () -> ServerClient?, onArtist: (String) -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.parity("artist.stints-toggle", Contract.label("artist.stints-toggle", 0, title)).clickable { open = !open }.padding(top = 12.dp, bottom = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${title.uppercase()} · ${list.size}", color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.06.em)
            Icon(painterResource(R.drawable.dl_chevron), contentDescription = null, tint = paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(16.dp).rotate(if (open) 270f else 90f))
        }
        if (open) LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 14.dp)) {
            items(list.size) { i -> StintTile(list[i], client, onArtist) }
        }
    }
}

/** One of them: their picture (or first letter) in a circle, the name, what they played and when. */
@Composable
private fun StintTile(m: ArtistAbout.Stint, client: () -> ServerClient?, onArtist: (String) -> Unit) {
    Column(Modifier.parity("artist.member", Contract.label("artist.member", 0, m.name)).width(96.dp).clickable { onArtist(m.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.parity("artist.member-art").fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(paletteColor(Palette.BG_ELEV_2)), contentAlignment = Alignment.Center) {
            val art = rememberTileCover(m.photoKey, 300, client)
            if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(m.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 26.sp, fontWeight = FontWeight.W600)
        }
        Text(m.name, Modifier.parity("artist.member-name").padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        m.line?.let { Text(it, Modifier.parity("artist.member-line").padding(top = 1.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, lineHeight = 13.65.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}
