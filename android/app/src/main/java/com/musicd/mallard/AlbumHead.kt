package com.musicd.mallard

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Album
import com.musicd.mallard.client.Booklet
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.NavList
import com.musicd.mallard.client.Palette

/*
 * The album page's head beyond its lines (docs/specs/screens.json, album;
 * public/app/album-detail.js): the notes under the facts (named from its
 * folders; why a streamed album is here; a disc of a box set and the other
 * discs), the service's mark on the cover, and previous and next beside
 * Share, the album before and after this one in the list it was opened from.
 */

/**
 * A classical album's head lines (bug list item 49), in the artists' place: the composers, each a tap to
 * their page, and under them who performs it, the conductor named as such (the album's artist where no
 * one is named), as the page's `.modal-composers` and `.modal-performers`.
 */
@Composable
internal fun ComposerLines(a: Album, onPage: (String, String?) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Centred under the title, as the artists are (item 539).
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        a.composers.forEachIndexed { i, name ->
            if (i > 0) Text(" · ", Modifier.parity("album.composer"), color = dim, fontSize = 15.sp)
            Text(name, Modifier.parity("album.composer", Contract.label("album.composer", 0, name)).clickable { onPage("artist", name) },
                color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val who = a.performers + listOfNotNull(a.conductor?.let { "$it, conductor" })
    Text(who.ifEmpty { listOf(a.album.subtitle) }.joinToString(" · "), Modifier.parity("album.performers").fillMaxWidth().padding(top = 2.dp), color = dim, fontSize = 14.sp,
        textAlign = TextAlign.Center)
}

/**
 * The notes at the foot of the head, each only when it has something to say; a disc's tap opens it among the box's
 * discs ([onDisc]). An album's versions are its Versions tab's (item 349: AlbumPhone.kt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeadNotes(a: Album, onDisc: (NavList) -> Unit) {
    val faint = paletteColor(Palette.TEXT_FAINT)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (a.namesFromFolders) Text("Names from its folders — the files carry no tags", Modifier.parity("album.names-note").fillMaxWidth().padding(top = 4.dp),
        color = faint, fontSize = 13.sp, textAlign = TextAlign.Center)
    a.serviceWhy?.let { Text(it.text(), Modifier.parity("album.why-note").fillMaxWidth().padding(top = 4.dp), color = faint, fontSize = 13.sp, textAlign = TextAlign.Center) }
    a.album.box?.let { box ->
        Column(Modifier.parity("album.box-note").fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(box.text, color = dim, fontSize = 13.sp, textAlign = TextAlign.Center)
            val discs = a.boxDiscs
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                discs.forEachIndexed { i, d ->
                    if (d.offset == a.album.offset) return@forEachIndexed
                    val n = d.box?.disc ?: 0
                    Text("Disc $n · ${d.title}", color = paletteColor(Palette.MODAL_TEXT), fontSize = 12.sp,
                        modifier = Modifier.parity("album.box-disc", Contract.label("album.box-disc", 0, n.toString())).clip(RoundedCornerShape(999.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                            .clickable { onDisc(NavList(discs.map { com.musicd.mallard.client.NavItem(it.offset, it.title) }, i)) }
                            .padding(horizontal = 11.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** The book on the cover (bottom right), as the page's: a brass disc; one booklet opens at once ([onOpen]), several are chosen from a list. */
@Composable
internal fun BookletButton(booklets: List<Booklet>, colors: IconButtonColors, modifier: Modifier = Modifier, onOpen: (Booklet) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.padding(8.dp)) {
        FilledIconButton(onClick = { if (booklets.size == 1) onOpen(booklets[0]) else open = true }, colors = colors, shape = CircleShape,
            modifier = Modifier.parity("album.booklet", Contract.label("album.booklet", if (booklets.size == 1) 0 else 1, booklets.size.toString())).size(38.dp)) {
            Icon(painterResource(R.drawable.np_booklet), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (b in booklets) DropdownMenuItem(text = { Text(b.text) }, onClick = { open = false; onOpen(b) },
                modifier = Modifier.parity("album.booklet-pick", Contract.label("album.booklet-pick", 0, b.text)))
        }
    }
}

/** The streaming service's mark on the cover (bottom left), as the page's badge: only a Qobuz or Tidal album wears one. */
@Composable
internal fun SourceBadge(source: String?, modifier: Modifier = Modifier) {
    val icon = when (source) { "qobuz" -> R.drawable.src_qobuz; "tidal" -> R.drawable.src_tidal; else -> return }
    Box(modifier.parity("album.source").padding(8.dp).size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x9E0A0E14)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(16.dp))
    }
}
