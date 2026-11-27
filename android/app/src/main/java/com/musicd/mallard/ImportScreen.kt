package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShareImport
import com.musicd.mallard.client.WallPrefs
import com.musicd.mallard.client.readShare
import com.musicd.mallard.client.saveShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Import a playlist (the web app's side-menu row; public/app.js __openImportSheet): a playlist shared from this app, the
 * web app or the Roon build (MDRP1 text, lib/share.js) pasted in, read against this library — "38 of 45 found", the
 * missing ones listed, never silently dropped — then saved under a name as a stored LMS playlist of what matched.
 */

@Composable
internal fun ImportScreen(zoneId: String, client: () -> ServerClient?, nav: CollectionNav) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var read by remember { mutableStateOf<ShareImport?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun readIt() {
        val c = client() ?: return
        if (text.isBlank()) { toast("Paste a shared playlist first"); return }
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.readShare(text.trim()) } }
                .fold({ r -> read = r; name = r.name; said = null }, { read = null; said = Failure.said(it, "That couldn't be read") to true })
            busy = false
        }
    }
    fun save() {
        val c = client() ?: return
        val r = read ?: return
        if (name.isBlank()) { toast("Give the playlist a name"); return }
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.saveShare(name.trim(), r.resolved) } }.fold({ j ->
                val added = j.optInt("added", 0)
                toast(if (j.optBoolean("created", true)) "Saved “${name.trim()}” — $added tracks" else "Added $added tracks to the existing “${name.trim()}”")
                nav.onBack()
            }, { said = Failure.said(it, "Couldn't save the playlist") to true })
            busy = false
        }
    }
    CollectionFrame(zoneId, client, nav) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 64.dp, bottom = 176.dp)) {
            item { WallHead("Import a playlist", WallPrefs(), said) }
            item {
                Text("Paste a playlist shared from Mallard, the web app or MusicD for Roon. It is matched against your library; what's found is saved as a playlist on your server.",
                    Modifier.padding(top = 10.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp, lineHeight = 20.sp)
                Field(text, "MDRP1:…", 6) { text = it; read = null }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActPill("Paste", false) { clipboard.getText()?.text?.let { text = it; read = null } }
                    ActPill(if (busy && read == null) "Reading…" else "Read it", true, enabled = !busy) { readIt() }
                }
            }
            read?.let { r ->
                item {
                    Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${r.found} of ${r.total} tracks found in your library" + if (r.truncated) " (the first 2,000 were read)" else "",
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.W600)
                        if (r.missing.isNotEmpty()) {
                            Text("Not in your library:", color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
                            for (m in r.missing.take(25)) Text("· $m", color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp)
                            if (r.missing.size > 25) Text("…and ${r.missing.size - 25} more", color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp)
                        }
                        if (r.found > 0) {
                            Text("Save as", Modifier.padding(top = 12.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp)
                            Field(name, "Playlist name", 1) { name = it }
                            ActPill(if (busy) "Saving…" else "Save playlist", true, Modifier.padding(top = 10.dp), enabled = !busy) { save() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, lines: Int, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = (24 + lines * 20).dp).clip(shape)
        .background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape).padding(horizontal = 14.dp, vertical = 10.dp),
        textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 14.sp), singleLine = lines == 1, maxLines = if (lines == 1) 1 else 12,
        cursorBrush = SolidColor(paletteColor(Palette.ACCENT)),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = paletteColor(Palette.TEXT_FAINT), fontSize = 14.sp); inner() } })
}
