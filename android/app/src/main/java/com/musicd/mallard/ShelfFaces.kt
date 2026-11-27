package com.musicd.mallard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.musicd.mallard.client.ShelfCase
import com.musicd.mallard.client.ShelfPlaying

/*
 * What a case on the native Shelf shows (bug list item 207), painted as the page's shelf-faces.js and shelf.css draw it:
 * its spine in the cover's own colour, one line along it — reading down the artist, the album and its version, at its foot
 * the format, the year and the catalogue number — with a divider card standing on it where a new letter, label or decade
 * begins; its front, the cover (or its names on a plain sleeve), over a tall card with the same names on a stage taller
 * than the cover is wide; and its back for the moment of the turn. Each face is painted in its own flat coordinates
 * (0 to its width, 0 to its height); the stage (ShelfStage.kt) puts it in place. Nothing here is Compose.
 */
internal class ShelfFaces(context: Context, private val density: Float) {
    private fun font(context: Context, id: Int) = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
    private val regular = font(context, R.font.manrope_regular)
    private val medium = font(context, R.font.manrope_medium)
    private val bold = font(context, R.font.manrope_bold)
    private val heavy = font(context, R.font.manrope_extrabold)
    private val serif = font(context, R.font.young_serif)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** The page's root size on this screen (1rem), in pixels: 14px on a phone, growing with the screen to 20px. */
    var rem = 14f * density

    private fun mix(c: Long, keep: Float, with: Long): Int {
        fun ch(v: Long, s: Int) = ((v shr s) and 0xff).toInt()
        fun m(s: Int) = (ch(c, s) * keep + ch(with, s) * (1 - keep)).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun alpha(c: Long, a: Float): Int = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (c and 0xffffff).toInt()

    private fun TextPaint.set(face: Typeface, size: Float, colour: Int, spacingEm: Float = 0f, skew: Float = 0f) {
        typeface = face; textSize = size; color = colour; letterSpacing = spacingEm; textSkewX = skew
    }

    private fun fit(s: String, width: Float): String = if (width <= 0f) "" else TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString()

    /**
     * The spine, [w] wide and [h] tall, in [bg] with its writing in [fg]; [tab]: the divider card standing on it (a
     * letter, or a label's or decade's name), raised ([up]) where the spine before has one too.
     */
    fun spine(c: Canvas, a: ShelfCase, w: Float, h: Float, bg: Long, fg: Long, tab: String, up: Boolean) {
        fill.shader = null; fill.color = bg.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        // the edge's shading: darker at both sides, as a jewel case's
        shade.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x57000000, 0, 0, 0x66000000), floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, shade)
        val size = 0.33f * w
        val top = 0.09f * w; val foot = 0.06f * w
        val len = h - top - foot
        val gap = 0.7f * size
        // the foot, never cut: the format, the year, the catalogue number
        val footSize = size * 0.84f
        val parts = listOfNotNull(a.format?.let { it to 0.8f }, a.year?.toString()?.let { it to 0.8f }, a.catno?.let { it to 1f })
        text.set(regular, footSize, fg.toInt(), 0.04f)
        val widths = parts.mapIndexed { i, (s, _) -> if (i == parts.lastIndex && a.catno != null) { text.set(bold, footSize, fg.toInt(), 0.08f); text.measureText(s).also { text.set(regular, footSize, fg.toInt(), 0.04f) } } else text.measureText(s) }
        val footW = widths.sum() + gap * (parts.size - 1).coerceAtLeast(0)
        // the names, giving way (the version first) so the foot keeps its room
        val ar = a.artist.uppercase(); val ti = a.baseTitle; val vr = a.versionText
        text.set(heavy, size, fg.toInt(), 0.05f); val arW = text.measureText(ar)
        text.set(medium, size, fg.toInt()); val tiW = text.measureText(ti)
        text.set(regular, size * 0.88f, fg.toInt(), 0f, -0.2f); val vrW = if (vr.isEmpty()) 0f else text.measureText(vr)
        val room = len - footW - (if (parts.isEmpty()) 0f else gap)
        val names = listOf(arW, tiW, vrW).filter { it > 0 }
        var over = names.sum() + gap * (names.size - 1) - room
        var vrFit = vrW; var arFit = arW; var tiFit = tiW
        if (over > 0 && vrW > 0) { val take = minOf(vrW, over); vrFit = vrW - take; over -= take; if (vrFit < size) { over += 0f; vrFit = 0f } }
        if (over > 0) { val sum = arW + tiW; arFit = (arW - over * arW / sum).coerceAtLeast(0f); tiFit = (tiW - over * tiW / sum).coerceAtLeast(0f) }
        c.save()
        c.translate(w / 2, top)
        c.rotate(90f)
        // along the spine now: x down it, the line's middle at y 0
        var x = 0f
        fun put(s: String, fitW: Float, face: Typeface, sz: Float, op: Float, spacing: Float = 0f, skew: Float = 0f) {
            if (fitW <= 0f || s.isEmpty()) return
            text.set(face, sz, alpha(fg, op), spacing, skew)
            val shown = fit(s, fitW)
            val fm = text.fontMetrics
            c.drawText(shown, x, -(fm.ascent + fm.descent) / 2, text)
            x += text.measureText(shown) + gap
        }
        put(ar, arFit, heavy, size, 1f, 0.05f)
        put(ti, tiFit, medium, size, 0.9f)
        put(vr, vrFit, regular, size * 0.88f, 0.7f, 0f, -0.2f)
        // the foot from the far end
        x = len - footW
        parts.forEachIndexed { i, (s, op) ->
            val last = i == parts.lastIndex && a.catno != null
            text.set(if (last) bold else regular, footSize, alpha(fg, op), if (last) 0.08f else 0.04f)
            val fm = text.fontMetrics
            c.drawText(s, x, -(fm.ascent + fm.descent) / 2, text)
            x += widths[i] + gap
        }
        c.restore()
        if (tab.isNotEmpty()) dividerCard(c, tab, w, up)
    }

    /** A divider card where a run begins: a letter's small and in the serif, a label's or a decade's wider, tiered when two stand side by side. */
    private fun dividerCard(c: Canvas, tab: String, w: Float, up: Boolean) {
        val wide = tab.length > 1
        if (wide) text.set(heavy, 0.24f * w, 0xFF17191C.toInt(), 0.04f) else text.set(serif, 0.52f * w, 0xFF17191C.toInt())
        val words = if (wide) tab.uppercase() else tab
        val padX = if (wide) 0.28f * w else 0.2f * w
        val padTop = if (wide) 0.18f * w else 0.16f * w; val padBottom = if (wide) 0.12f * w else 0.1f * w
        val shown = if (wide) fit(words, 3.8f * w - padX * 2) else words
        val tw = maxOf(text.measureText(shown) + padX * 2, if (wide) 0f else 1.3f * w)
        val fm = text.fontMetrics
        val th = (fm.descent - fm.ascent) * (if (wide) 1f else 0.82f) + padTop + padBottom
        val bottom = if (wide && up) -0.58f * w else 0f
        val left = w / 2 - tw / 2
        fill.shader = null; fill.color = 0xFFC9A45C.toInt()
        val r = 0.16f * w
        rect.set(left, bottom - th, left + tw, bottom + r)
        c.save(); c.clipRect(left, bottom - th, left + tw, bottom)
        c.drawRoundRect(rect, r, r, fill)
        c.restore()
        c.drawText(shown, w / 2 - text.measureText(shown) / 2, bottom - padBottom - fm.descent * (if (wide) 1f else 0.6f), text)
    }

    /**
     * The front, [w] wide and [h] tall: the cover ([art], or the album's names on a plain sleeve in [bg]) at its top, the
     * card's names under it where the case is a tall card; a brass ring round it for a moment after a spin lands ([ring]).
     */
    fun front(c: Canvas, a: ShelfCase, w: Float, h: Float, bg: Long, art: Bitmap?, ring: Float) {
        val coverH = if (h > w) w else h
        fill.shader = null; fill.color = 0x8C000000.toInt()
        fill.setShadowLayer(1.8f * rem * 0.5f, 0f, 0.7f * rem, 0x8C000000.toInt())
        c.drawRect(0f, 0f, w, h, fill)
        fill.clearShadowLayer()
        if (art != null) {
            rect.set(0f, 0f, w, coverH)
            c.drawBitmap(art, null, rect, image)
        } else blank(c, a, w, coverH, bg)
        if (h > w) card(c, a, w, coverH, h, bg)
        if (ring > 0f) {
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 0.3f * rem; fill.color = alpha(0xFFC9A45C, ring)
            c.drawRect(-0.15f * rem, -0.15f * rem, w + 0.15f * rem, h + 0.15f * rem, fill)
            fill.style = Paint.Style.FILL
        }
    }

    /** A cover with no picture: its own name on a plain sleeve, in the cover's colour. */
    private fun blank(c: Canvas, a: ShelfCase, w: Float, h: Float, bg: Long) {
        fill.shader = LinearGradient(0f, 0f, w * 0.34f, h, mix(bg, 0.7f, 0xFF000000), bg.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val pad = 0.08f * w
        text.set(heavy, 0.042f * w, alpha(0xFFF2EFE6, 0.8f), 0.14f)
        val artist = fit(a.artist.uppercase(), w - pad * 2)
        c.drawText(artist, pad, h - pad - text.fontMetrics.descent, text)
        text.set(serif, 0.08f * w, 0xFFF2EFE6.toInt())
        val lines = wrap(a.title, w - pad * 2, 4)
        val lh = 0.08f * w * 1.08f
        var y = h - pad - 0.042f * w * 1.4f - 0.02f * w - lh * (lines.size - 1)
        for (l in lines) { c.drawText(l, pad, y, text); y += lh }
    }

    /** The tall card's names under the cover: the artist, the album and its version at the top; the format, the year and the catalogue number at its foot. */
    private fun card(c: Canvas, a: ShelfCase, w: Float, top: Float, h: Float, bg: Long) {
        fill.shader = LinearGradient(0f, top, 0f, h, mix(bg, 0.55f, 0xFF000000), mix(bg, 0.3f, 0xFF000000), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w, h, fill); fill.shader = null
        val padX = 0.9f * rem; val padY = 1f * rem; val gap = 0.3f * rem
        val inner = w - padX * 2
        var y = top + padY
        text.set(heavy, 0.74f * rem, alpha(0xFFF2EFE6, 0.85f), 0.12f)
        y += -text.fontMetrics.ascent
        c.drawText(fit(a.artist.uppercase(), inner), padX, y, text)
        y += text.fontMetrics.descent + gap
        text.set(serif, 1.3f * rem, 0xFFF2EFE6.toInt())
        val lh = 1.3f * rem * 1.12f
        for (l in wrap(a.baseTitle, inner, 3)) { c.drawText(l, padX, y + lh * 0.8f, text); y += lh }
        if (a.versionText.isNotEmpty()) {
            y += gap
            text.set(regular, 0.8f * rem, alpha(0xFFF2EFE6, 0.72f), 0f, -0.2f)
            c.drawText(fit(a.versionText, inner), padX, y - text.fontMetrics.ascent, text)
        }
        val foot = listOfNotNull(a.format, a.year?.toString(), a.catno)
        var fy = h - padY
        foot.reversed().forEachIndexed { i, s ->
            val last = i == 0
            text.set(if (last) bold else regular, 0.74f * rem, alpha(0xFFF2EFE6, if (last) 1f else 0.75f), 0.04f)
            c.drawText(fit(s, inner), padX, fy - text.fontMetrics.descent, text)
            fy -= text.fontMetrics.descent - text.fontMetrics.ascent + 0.15f * rem
        }
    }

    /** The back, for the moment of the turn: the album's name, its line, a barcode and "Tap to turn back". */
    fun back(c: Canvas, a: ShelfCase, line: String, w: Float, h: Float, bg: Long) {
        fill.shader = null; fill.color = mix(bg, 0.22f, 0xFF121315)
        c.drawRect(0f, 0f, w, h, fill)
        val pad = 0.065f * w; val inner = w - pad * 2
        var y = pad
        text.set(heavy, 0.038f * w, 0xFFD4B06A.toInt(), 0.16f)
        y -= text.fontMetrics.ascent
        c.drawText(fit(a.artist.uppercase(), inner), pad, y, text)
        y += text.fontMetrics.descent + 0.016f * w
        text.set(serif, 0.064f * w, 0xFFE9E6DE.toInt())
        val lh = 0.064f * w * 1.08f
        for (l in wrap(a.title, inner, 3)) { c.drawText(l, pad, y + lh * 0.8f, text); y += lh }
        if (line.isNotEmpty()) {
            text.set(bold, 0.032f * w, 0x9EE9E6DE.toInt(), 0.08f)
            c.drawText(fit(line.uppercase(), inner), pad, y + 0.012f * w - text.fontMetrics.ascent, text)
        }
        // the barcode, white bars on black inside a white border, and the way back
        val bw = 0.2f * w; val bh = 0.07f * w; val bx = pad; val by = h - pad - bh
        fill.color = 0xFFFFFFFF.toInt(); c.drawRect(bx - 0.01f * w, by - 0.01f * w, bx + bw + 0.01f * w, by + bh + 0.01f * w, fill)
        var x = 0f
        ShelfPlaying.barcode(a).forEachIndexed { i, f ->
            fill.color = if (i % 2 == 0) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
            c.drawRect(bx + x * bw, by, bx + minOf(1f, x + f) * bw, by + bh, fill); x += f
        }
        text.set(regular, 0.031f * w, 0x99E9E6DE.toInt(), 0.14f)
        val say = "Tap to turn back"
        c.drawText(say, w - pad - text.measureText(say), h - pad - text.fontMetrics.descent, text)
    }

    /** [s] in up to [max] lines of [width], the last cut with an ellipsis. */
    private fun wrap(s: String, width: Float, max: Int): List<String> {
        val out = ArrayList<String>(); var rest = s.trim()
        while (rest.isNotEmpty() && out.size < max) {
            if (out.size == max - 1 || text.measureText(rest) <= width) { out += fit(rest, width); break }
            var cut = text.breakText(rest, true, width, null)
            val space = rest.lastIndexOf(' ', cut)
            if (space > 0) cut = space
            out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
        }
        return out
    }
}
