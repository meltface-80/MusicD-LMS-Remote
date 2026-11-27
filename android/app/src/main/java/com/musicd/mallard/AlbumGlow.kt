package com.musicd.mallard

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.musicd.mallard.client.GlowBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The album page's glow (item 471): [cover] made GlowBlur.WIDTH wide, its height in proportion, and blurred once, in
 * the background (GlowBlur), to be drawn stretched and faint, never a live blur worked out again on each frame. A
 * cover held on the graphics chip (HARDWARE) is copied to be read. Null where it can't be made.
 */
internal suspend fun albumGlowOf(cover: ImageBitmap): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        val src = cover.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        val w = GlowBlur.WIDTH
        val h = GlowBlur.heightFor(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        Bitmap.createBitmap(GlowBlur.blur(px, w, h), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
