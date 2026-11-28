package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.Playlist
import com.musicd.mallard.client.PlaylistAdds
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.addToPlaylist
import com.musicd.mallard.client.playlists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Add to playlist (the web app's shared sheet, public/app.js __addToPlaylistSheet): asked for from an album's ⋯ and
 * from the albums' and tracks' multi-select. The sheet: what is being added, "+ New playlist…" (a name asked for), then
 * Existing, every stored playlist. A choice closes the sheet and the answer is said as a toast. Whatever came of it, the
 * selection is let go ([PlaylistAdd.Ask.after]), as the page does: a failure must not leave the screen stuck selecting.
 */
internal object PlaylistAdd {
    class Ask(val body: JSONObject, val describe: String, val after: () -> Unit)
    val ask = mutableStateOf<Ask?>(null)
    fun open(body: JSONObject, describe: String, after: () -> Unit = {}) { ask.value = Ask(body, describe, after) }
}

/** The sheet, over whichever screen asked (NativeShell's Shell hosts it once). */
@Composable
internal fun PlaylistAddOver(client: () -> ServerClient?) {
    val a = PlaylistAdd.ask.value ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(a) { mutableStateOf<List<Playlist>?>(null) }
    var naming by remember(a) { mutableStateOf(false) }
    LaunchedEffect(a) { val c = client(); if (c != null) lists = withContext(Dispatchers.IO) { runCatching { c.playlists() }.getOrNull() } }
    fun close() { PlaylistAdd.ask.value = null }
    fun send(id: String?, name: String?) {
        close()
        val c = client() ?: run { a.after(); return }
        scope.launch {
            val said = withContext(Dispatchers.IO) { runCatching { PlaylistAdds.said(c.addToPlaylist(a.body, id, name), name) }.getOrElse { Failure.said(it, "Couldn't add those") } }
            setupToast(context, said)
            a.after()
        }
    }
    SheetPanel(onClose = { close(); a.after() }, head = {
        SheetTitle("Add to playlist", Modifier)
        SheetClose(Modifier.clip(CircleShape).clickable { close(); a.after() })
    }, foot = null) {
        Text(a.describe, Modifier.padding(bottom = 10.dp), color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, lineHeight = 18.sp)
        PlaylistRow("+", "New playlist…") { naming = true }
        val l = lists.orEmpty()
        if (l.isNotEmpty()) {
            Text("EXISTING", Modifier.padding(top = 14.dp, bottom = 7.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 11.sp, fontWeight = FontWeight.W700, letterSpacing = 0.08.em)
            for (p in l) PlaylistRow("", p.title.ifEmpty { "Untitled" }) { send(p.id, null) }
        }
    }
    if (naming) NameDialog("Name the new playlist", onCancel = { naming = false }) { name -> naming = false; if (name.isNotBlank()) send(null, name) }
}

/** One row of the sheet (the page's .lib-sort-row): its mark, then its words. */
@Composable
private fun PlaylistRow(mark: String, words: String, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onTap).padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(mark, Modifier.width(22.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp, fontWeight = FontWeight.W700)
        Text(words, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
