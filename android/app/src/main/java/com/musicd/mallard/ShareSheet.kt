package com.musicd.mallard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.musicd.mallard.client.CardFields
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShareCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Copy image's words while nothing has been copied (the contract's share-card.copy, its first). */
private const val COPY = "Copy image"

/**
 * The Share card, natively, as the page's (public/share-sheet.js, public/sharecard.js, style.css .share-*); ported
 * from Mandarin: a panel over the screen, centred above the
 * mini player's room ([reserve]), ✕ in its corner; the card drawn (ShareCardPainter.kt) from what the server has to
 * hand, drawn again if the full answer says more; Copy image and Download; the hint. The links and the acts suggested
 * under it went with item 336.
 */
@Composable
internal fun ShareSheet(title: String, artist: String, imageKey: String?, reserve: Dp, client: () -> ServerClient?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(title, artist) { ShareState(title, artist, imageKey, client, scope, context) }
    LaunchedEffect(title, artist) { s.load() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.55f) }
        Box(Modifier.parity("share-card.close", Contract.label("share-card.close")).fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .systemBarsPadding().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + reserve), contentAlignment = Alignment.Center) {
            val panel = RoundedCornerShape(20.dp)
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(panel).background(paletteColor(Palette.BG_VEIL)).border(1.dp, paletteColor(Palette.GLASS_EDGE), panel)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, top = 24.dp, end = 18.dp, bottom = 18.dp)) {
                    ShareFrame(s)
                    ShareActions(s)
                    Text(ShareCard.HINT, Modifier.parity("share-card.hint").padding(start = 4.dp, top = 10.dp, end = 4.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp, lineHeight = 18.sp)
                    s.err?.let { Text(it, Modifier.parity("share-card.err").padding(start = 4.dp, top = 10.dp, end = 4.dp), color = paletteColor(Palette.DANGER), fontSize = 13.sp, lineHeight = 18.sp) }
                }
                Box(Modifier.parity("share-card.close", Contract.label("share-card.close")).align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(40.dp).clip(CircleShape)
                    .background(paletteColor(Palette.ACCENT)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.np_close), contentDescription = null, tint = paletteColor(Palette.ON_ACCENT), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** The card's frame (.share-frame): 26 under the panel's top, its edge and corners, the card dark behind; the card, or the spinner and what is said. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareFrame(s: ShareState) {
    val frame = RoundedCornerShape(16.dp)
    Box(Modifier.parity("share-card.card").padding(top = 26.dp).fillMaxWidth().clip(frame).background(paletteColor(0xFF12151AL)).border(1.dp, paletteColor(Palette.GLASS_EDGE), frame),
        contentAlignment = Alignment.Center) {
        val card = s.card
        if (card != null) Image(card.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.parity("share-card.image", Contract.label("share-card.image")).fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.64f).dp)
                .combinedClickable(onLongClick = { s.download() }, onClick = {}))
        else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!s.failed) CircularProgressIndicator(Modifier.padding(bottom = 10.dp).size(26.dp), color = paletteColor(Palette.ACCENT_TEXT), trackColor = paletteColor(Palette.BORDER), strokeWidth = 3.dp)
            Text(if (s.failed) ShareCard.FAILED else ShareCard.GENERATING, color = paletteColor(Palette.TEXT_FAINT), fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Copy image in the accent and Download (.share-actions): side by side, 10 apart, 14 under the card, each 45 tall, 10
 * round, an 18 icon before the words. Copy image in place of Share… (item 224, as Rouen's app): the card on the clipboard,
 * pasted where it is wanted, a forum's post among them, rather than an app chosen from Android's share sheet.
 */
@Composable
private fun ShareActions(s: ShareState) {
    if (s.card == null) return
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ShareButton(s.copied, R.drawable.sc_copy, true, Modifier.parity("share-card.copy", Contract.label("share-card.copy", if (s.copied == COPY) 0 else 1)).weight(1f).clickable { s.copy() })
        ShareButton(s.saved, R.drawable.sc_download, false, Modifier.parity("share-card.download", Contract.label("share-card.download")).weight(1f).clickable { s.download() })
    }
}

/** One of the card's buttons: the accent for [primary], else the raised ground with the glass's edge. */
@Composable
private fun ShareButton(words: String, icon: Int, primary: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val ink = paletteColor(if (primary) Palette.ON_ACCENT else Palette.TEXT)
    Row(modifier.clip(shape).background(paletteColor(if (primary) Palette.ACCENT else Palette.BG_ELEV_2))
        .border(1.dp, if (primary) androidx.compose.ui.graphics.Color.Transparent else paletteColor(Palette.GLASS_EDGE), shape).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(words, color = ink, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.W500, maxLines = 1)
    }
}

/**
 * What the card holds and does, as the page's share-card.js: the card drawn (and its PNG, for Copy image and Download),
 * what each says for a moment after, what went wrong.
 */
internal class ShareState(private val title: String, private val artist: String, private val imageKey: String?, private val client: () -> ServerClient?,
                          private val scope: CoroutineScope, private val context: Context) {
    var card by mutableStateOf<Bitmap?>(null)
    var failed by mutableStateOf(false)
    var saved by mutableStateOf("Download")
    var copied by mutableStateOf(COPY)
    var err by mutableStateOf<String?>(null)
    private var png: ByteArray? = null
    private val bridge get() = (context as? Activity)?.let { ShareBridge(it) }

    /** The card at once from what is to hand (title, artist, cover), then again with the year, the review and the score. */
    suspend fun load() {
        val c = client() ?: return
        val lib = LibraryClient { c.getJson(it) }
        val painter = ShareCardPainter(context)
        try {
            withContext(Dispatchers.IO) {
                val fields = async { runCatching { lib.shareCard(title, artist) }.getOrNull() }
                val cover = async { imageKey?.let { k -> runCatching { c.bytes(c.imageUrl(k, 800)).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() } }
                val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.duck_tile) }.getOrNull()
                paint(painter, CardFields.NONE, cover.await(), logo)
                val later = fields.await() ?: return@withContext
                if (later.signature != CardFields.NONE.signature) paint(painter, later, cover.await(), logo)
            }
        } catch (e: Exception) {
            if (card == null) failed = true
            err = e.message ?: e.toString()
        }
    }

    private suspend fun paint(painter: ShareCardPainter, f: CardFields, cover: Bitmap?, logo: Bitmap?) {
        val bmp = painter.draw(title, artist, f.releaseRaw, f.reviewText, f.reviewSource, f.score, f.bestNew, cover, logo)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        withContext(Dispatchers.Main) { png = bytes; card = bmp }
    }

    /** On the clipboard as a picture; Copy image says Copied! for a moment, or what went wrong is said under the card. */
    fun copy() {
        val b = png ?: return
        val br = bridge ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { br.copyImage(b, ShareCard.fileName(title, artist)) }
            if (!ok) { err = "Couldn’t copy the card"; return@launch }
            err = null; copied = "Copied!"
            delay(2000)
            copied = COPY
        }
    }

    /** Saved to Pictures/Mallard; Download says how it went for a moment. */
    fun download() {
        val b = png ?: return
        val br = bridge ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { br.save(b, ShareCard.fileName(title, artist), "image/png") }
            saved = if (ok) "Saved to Pictures" else "Couldn’t save"
            delay(2500)
            saved = "Download"
        }
    }
}
