package com.musicd.mallard

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfCase
import com.musicd.mallard.client.ShelfPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The native Shelf's covers (bug list item 207), as the page's shelf.js fetches and reads them: a cover asked for once
 * the shelf slows (none for an album flying past in a spin), kept as the app's tiles keep theirs (Covers), and its colour
 * read off it — a touch deeper than its average, as a printed spine reads — for its spine, its card and its back. Before a
 * cover is read, its spine is one of eight stand-in colours, the same each time for that album. Nothing is drawn here.
 */
internal class ShelfArt(private val context: Context, private val client: () -> ServerClient?, private val scope: CoroutineScope) {
    private val art = HashMap<String, Bitmap>()
    private val colours = HashMap<String, Pair<Long, Long>>()
    private val asked = HashSet<String>()
    /** A cover came: the stage draws again. */
    var rev by mutableIntStateOf(0); private set

    /** [a]'s cover, once fetched; asked for now unless the shelf is flying past ([fast]). */
    fun cover(a: ShelfCase, fast: Boolean): Bitmap? {
        val k = a.imageKey ?: return null
        art[k]?.let { return it }
        if (!fast && asked.add(k)) fetch(k)
        return null
    }

    /** [a]'s spine colours: its cover's, once read, else its stand-in; the writing dark or light to suit. */
    fun colours(a: ShelfCase): Pair<Long, Long> = a.imageKey?.let { colours[it] } ?: (ShelfPlaying.fallbackColour(a) to 0xFFF2EFE6L)

    private fun fetch(k: String) {
        val c = client() ?: run { asked.remove(k); return }
        scope.launch {
            val img = Covers.server(context, c, k, SIZE)?.asAndroidBitmap()
            if (img == null) { asked.remove(k); return@launch }
            val col = withContext(Dispatchers.Default) { average(img) }
            art[k] = img
            if (col != null) colours[k] = col
            rev++
        }
    }

    /** The cover's colour, as the page reads it off a 6 by 6 copy; null for one that can't be read (a hardware bitmap). */
    private fun average(b: Bitmap): Pair<Long, Long>? = runCatching {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val small = Bitmap.createScaledBitmap(soft, 6, 6, true)
        var r = 0; var g = 0; var bl = 0
        for (y in 0 until 6) for (x in 0 until 6) { val p = small.getPixel(x, y); r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bl += p and 0xff }
        ShelfPlaying.spineColours(r / 36, g / 36, bl / 36)
    }.getOrNull()

    companion object {
        /** The size a shelf's cover is asked for: the page's 500, at the server's next step. */
        const val SIZE = 600
    }
}
