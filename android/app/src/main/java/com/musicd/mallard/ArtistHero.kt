package com.musicd.mallard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.ArtistAbout
import com.musicd.mallard.client.ArtistBio
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.ServerClient
import kotlin.math.min

/**
 * The head of an artist's page (bug list item 131; the page's artist-about.js, .artist-bio-head), over its tabs
 * (ArtistTabs): their picture, round and centred (their own once the server has found one, else the
 * biography's portrait); what they are, where from and when; their genres; Play and Shuffle. No links (the
 * owner never used them). The biography and a band's members or a person's bands are About's (ArtistAboutPane);
 * similar artists are at the foot of Overview (ArtistFoot).
 * [kept] is the picture the page had when last open, drawn at once (item 140); [onPortrait] is told the one drawn.
 */
@Composable
internal fun ArtistHero(about: ArtistAbout?, bio: ArtistBio?, client: () -> ServerClient?, onPlay: ((String) -> Unit)? = null,
                        onGenre: (String) -> Unit = {}, kept: ImageBitmap? = null, onPortrait: (ImageBitmap) -> Unit = {}) {
    val own = rememberTileCover(about?.photoKey, 600, client)
    val portrait = own ?: rememberImage(bio?.image, client) ?: kept
    LaunchedEffect(portrait) { if (portrait != null && portrait !== kept) onPortrait(portrait) }
    val side = min(200, (LocalConfiguration.current.screenWidthDp * 0.46).toInt()).dp
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        portrait?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.parity("artist.avatar").padding(bottom = 14.dp).size(side).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) }
        about?.facts?.let { Text(it, Modifier.parity("artist.facts").padding(bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.W500, textAlign = TextAlign.Center) }
        about?.genres?.let { GenreChips(it, "artist", center = true, modifier = Modifier.padding(bottom = 12.dp), onGenre = onGenre) }   // item 139
        onPlay?.let { PlayRow(it) }
    }
}

/** Play and Shuffle (item 138): the brass pill and the outlined one, side by side in the middle; their albums, in order or shuffled. */
@Composable
private fun PlayRow(onPlay: (String) -> Unit) {
    Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.parity("artist.play", Contract.label("artist.play")).height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { onPlay("play_now") }
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.np_play), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            Text(Contract.label("artist.play"), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W700)
        }
        Row(Modifier.parity("artist.shuffle", Contract.label("artist.shuffle")).height(40.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { onPlay("shuffle") }
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.np_shuffle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            Text(Contract.label("artist.shuffle"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600)
        }
    }
}
