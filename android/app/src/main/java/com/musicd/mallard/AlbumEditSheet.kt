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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.AlbumEdits
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.ArtCandidate
import com.musicd.mallard.client.EditedAlbum
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.artCandidates
import com.musicd.mallard.client.artThumbPath
import com.musicd.mallard.client.removeAlbumEdits
import com.musicd.mallard.client.saveAlbumEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Edit album (the web app's #album-edit-sheet, public/app.js initAlbumEdit), from the pencil at an album page's top
 * right: Title, Artist, Year; Artwork — Find artwork lists the covers found (a tap picks one) or an address is pasted;
 * then Remove edits (only when it carries some), Cancel, Save. Kept in the app's database; files and LMS untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumEditSheet(album: AlbumSummary, edited: Boolean, client: () -> ServerClient?, onClose: () -> Unit, onSaved: (EditedAlbum) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(album.title) }
    var artist by remember { mutableStateOf(album.subtitle) }
    var year by remember { mutableStateOf(album.year?.toString().orEmpty()) }
    var url by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<ArtCandidate>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun find() {
        val c = client() ?: return
        busy = true; status = AlbumEdits.SEARCHING; found = emptyList(); picked = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.artCandidates(album.offset, title.ifBlank { album.title }, artist.ifBlank { album.subtitle }) } }
                .fold({ found = it; status = if (it.isEmpty()) AlbumEdits.NONE else AlbumEdits.PICK }, { status = "Artwork search failed: " + Failure.said(it, "no answer") })
            busy = false
        }
    }
    fun save() {
        val c = client() ?: return
        val art = url.trim().ifEmpty { picked }
        busy = true; status = if (art != null) "Saving (downloading artwork)…" else "Saving…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.saveAlbumEdit(AlbumEdits.body(album.offset, title, artist, year, art)) } }
                .fold({ onSaved(it); setupToast(context, AlbumEdits.SAVED); onClose() }, { status = "Save failed: " + Failure.said(it, "no answer"); busy = false })
        }
    }
    fun remove() {
        val c = client() ?: return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.removeAlbumEdits(album.offset) } }
                .fold({ onSaved(it); setupToast(context, AlbumEdits.REMOVED); onClose() }, { status = "Remove failed: " + Failure.said(it, "no answer"); busy = false })
        }
    }

    SheetPanel(onClose = onClose, head = {
        SheetTitle("Edit album", Modifier)
        SheetClose(Modifier.clip(CircleShape).clickable(onClick = onClose))
    }, foot = {
        if (edited) Button(onClick = { if (!busy) remove() }, colors = footColors(false)) { Text("Remove edits", color = paletteColor(Palette.DANGER)) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onClose, colors = footColors(false)) { Text("Cancel") }
        Button(onClick = { if (!busy) save() }, colors = footColors(true)) { Text("Save") }
    }) {
        EditField("Title", title) { title = it }
        EditField("Artist", artist) { artist = it }
        EditField("Year", year, KeyboardType.Number) { year = it.filter(Char::isDigit).take(4) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Artwork", Modifier.weight(1f), color = paletteColor(Palette.TEXT), fontSize = 15.sp)
            Button(onClick = { if (!busy) find() }, colors = footColors(false)) { Text("Find artwork") }
        }
        if (status.isNotEmpty()) Text(status, Modifier.padding(bottom = 8.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, lineHeight = 18.sp)
        if (found.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (cand in found) Candidate(cand, picked == cand.url, client) { picked = cand.url; url = "" }
        }
        EditField("…or paste an image URL", url, KeyboardType.Uri, "https://…") { url = it; if (it.isNotBlank()) picked = null }
        Text(AlbumEdits.NOTE, Modifier.padding(top = 12.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** A labelled field (the page's .album-edit-field): its name over the box. */
@Composable
private fun EditField(label: String, value: String, type: KeyboardType = KeyboardType.Text, hint: String = "", onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, Modifier.padding(bottom = 5.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp), singleLine = true, textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 15.sp),
            cursorBrush = SolidColor(paletteColor(Palette.ACCENT)), keyboardOptions = KeyboardOptions(keyboardType = type),
            decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = paletteColor(Palette.TEXT_FAINT), fontSize = 15.sp); inner() } })
    }
}

/** One cover found: its picture (through the server) and where it came from; the one picked edged in brass. */
@Composable
private fun Candidate(c: ArtCandidate, picked: Boolean, client: () -> ServerClient?, onPick: () -> Unit) {
    val img = rememberImage(client()?.absoluteUrl(client()?.artThumbPath(c.url) ?: ""), client)
    Column(Modifier.width(96.dp).clickable(onClick = onPick)) {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.size(96.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
            .border(if (picked) 3.dp else 1.dp, paletteColor(if (picked) Palette.ACCENT else Palette.BORDER), shape)) {
            if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
        }
        Text(c.source, Modifier.padding(top = 4.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
