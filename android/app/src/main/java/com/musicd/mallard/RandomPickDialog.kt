package com.musicd.mallard

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The album Random Album chose, offered (docs/specs/screens.json, random-pick;
 * the page's play-unheard.js and style.css .random-pick-box): the yes-or-no
 * sheet's box with the cover large, the title and the artist under it, and
 * Play now, Play next or Queue stacked, ✕ in the corner; the screen behind
 * under a veil. One send per choice: the buttons go dim while it is on its
 * way, and the box closes once the room has it.
 */
@Composable
internal fun RandomPickDialog(album: AlbumSummary, zoneId: String, client: () -> ServerClient?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val cover = rememberCover(album.imageKey, 600, client)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // To the room (the page's sendPick): what came of it said as the page says it, but for Play now, which the mini player shows (bug list item 58).
    fun send(kind: String) {
        val c = client() ?: return
        if (zoneId.isEmpty()) { toast("Pick a player first"); return }
        sending = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.playAlbum(album.offset, zoneId, kind) } }
            r.onSuccess { if (kind != "play_now") toast((if (kind == "play_next") "Playing next: " else "Queued: ") + album.title.ifEmpty { "random album" }); onClose() }
                .onFailure { toast(com.musicd.mallard.client.Failure.said(it, "Could not start playback")); sending = false }
        }
    }
    // The box at the page's 360 at most, in from the screen's edges by its overlay's room; not the platform's dialog width, which is narrower.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.55f) }
        val box = RoundedCornerShape(18.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().widthIn(max = 360.dp).clip(box).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, box)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The cover: 62% of the box's room, with the page's shadow under it; "♪" where the album has none.
                Box(Modifier.parity("random-pick.art").padding(top = 6.dp, bottom = 14.dp).fillMaxWidth(0.62f).aspectRatio(1f).shadow(20.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(paletteColor(Palette.BG_ELEV_2))) {
                    if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text("♪", Modifier.align(Alignment.Center), color = paletteColor(Palette.TEXT_FAINT), fontSize = 48.sp, lineHeight = 48.sp)
                }
                Text(album.title.ifEmpty { "Untitled" }, Modifier.parity("random-pick.title").fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.W600, textAlign = TextAlign.Center)
                Text(album.subtitle, Modifier.parity("random-pick.artist").fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                val shape = RoundedCornerShape(12.dp)
                val dim = Modifier.alpha(if (sending) 0.6f else 1f)
                Box(Modifier.parity("random-pick.play-now", Contract.label("random-pick.play-now")).then(dim).fillMaxWidth().height(44.dp).clip(shape).background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !sending) { send("play_now") }, contentAlignment = Alignment.Center) {
                    Text(Contract.label("random-pick.play-now"), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.parity("random-pick.play-next", Contract.label("random-pick.play-next")).then(dim).fillMaxWidth().height(44.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape).clickable(enabled = !sending) { send("play_next") }, contentAlignment = Alignment.Center) {
                    Text(Contract.label("random-pick.play-next"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                }
                Spacer(Modifier.height(8.dp))
                // The end of the queue: the page's list-and-arrow before the word.
                Row(Modifier.parity("random-pick.queue", Contract.label("random-pick.queue")).then(dim).fillMaxWidth().height(44.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape).clickable(enabled = !sending) { send("queue") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(painterResource(R.drawable.rp_queue), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Queue", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                }
            }
            // ✕, top right, as the page's: 32 across, the glyph dim.
            Box(Modifier.parity("random-pick.close", Contract.label("random-pick.close")).align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(32.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rp_close), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        }
    }
}
