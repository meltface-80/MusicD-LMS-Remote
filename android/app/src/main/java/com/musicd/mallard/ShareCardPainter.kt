package com.musicd.mallard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.musicd.mallard.client.CardLayout
import com.musicd.mallard.client.CardLayout.ART
import com.musicd.mallard.client.CardLayout.ART_R
import com.musicd.mallard.client.CardLayout.ART_X
import com.musicd.mallard.client.CardLayout.CARD_W
import com.musicd.mallard.client.CardLayout.CONTENT_W
import com.musicd.mallard.client.CardLayout.INSET
import com.musicd.mallard.client.CardLayout.PANE_W
import com.musicd.mallard.client.CardLayout.TEXT_X
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The share card drawn, as public/sharecard.js draws it (its layout measured by core's [CardLayout]): the
 * cover again, softened (drawn 24 wide and scaled back up) and darkened, as the ground; the glass pane on it; the sharp
 * cover with its hairline; the release, the title and the artist beside it; the review under a hairline with whose
 * words they are; Pitchfork's score (and Best New Music) on the cover's corner; the duck tile in the pane's corner.
 * Manrope, as the page's canvas draws in; a word's top where the canvas's "top" baseline puts it (the em box's top).
 */
internal class ShareCardPainter(context: Context) {
    private val faces = mapOf(400 to R.font.manrope_regular, 600 to R.font.manrope_semibold, 700 to R.font.manrope_bold)
        .mapValues { (_, id) -> runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT }

    private fun paint(weight: Int, size: Int, color: Int = 0xFFFFFFFF.toInt()) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = faces[weight] ?: faces.getValue(400); textSize = size.toFloat(); this.color = color
    }

    /** A text's width at a weight and a size, for [CardLayout.measure]. */
    fun width(text: String, weight: Int, size: Int): Float = paint(weight, size).measureText(text)

    /** [text] with its em box's top at [top] (the canvas's textBaseline "top"); centred on [x] when [center]. */
    private fun Canvas.words(text: String, x: Float, top: Float, p: Paint, center: Boolean = false) {
        val fm = p.fontMetrics
        val ascent = -fm.ascent
        val baseline = top + p.textSize * ascent / (ascent + fm.descent)
        drawText(text, if (center) x - p.measureText(text) / 2 else x, baseline, p)
    }

    /** The card for [title] by [artist]: the release, the review and whose words, the score; [cover] and [logo] where there are. */
    fun draw(title: String, artist: String, releaseRaw: String, review: String, reviewSource: String, score: Double?, bestNew: Boolean,
             cover: Bitmap?, logo: Bitmap?): Bitmap {
        val l = CardLayout.measure({ t, w, s -> width(t, w, s) }, title, artist, releaseRaw, review, reviewSource)
        val h = l.cardH
        val bmp = Bitmap.createBitmap(CARD_W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The ground: the cover again, softened, and the two scrims over it.
        c.drawColor(0xFF12151A.toInt())
        if (cover != null) {
            val small = Bitmap.createBitmap(24, maxOf(1, (24f * h / CARD_W).roundToInt()), Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(cover, crop(cover, small.width.toFloat() / small.height), Rect(0, 0, small.width, small.height), fill)
            val over = (CARD_W * 0.06f).roundToInt().toFloat()
            c.drawBitmap(small, null, RectF(-over, -over, CARD_W + over, h + over), fill)
            c.drawColor(0x700C0E12)   // rgba(12,14,18,.44)
            c.drawRect(0f, 0f, CARD_W.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), 0x00080A0D, 0x8C080A0D.toInt(), Shader.TileMode.CLAMP)
            })
        }
        // The pane.
        val pane = RectF(INSET.toFloat(), INSET.toFloat(), (INSET + PANE_W).toFloat(), (INSET + l.paneH).toFloat())
        c.drawRoundRect(pane, CardLayout.PANE_R.toFloat(), CardLayout.PANE_R.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8012151A.toInt() })
        // No edge: the pane is the card (item 387), and its edge would be a line round it.
        // The sharp cover, clipped to its corners, and its hairline.
        val art = RectF(ART_X.toFloat(), l.artY.toFloat(), (ART_X + ART).toFloat(), (l.artY + ART).toFloat())
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(art, ART_R.toFloat(), ART_R.toFloat(), android.graphics.Path.Direction.CW) })
        if (cover != null) c.drawBitmap(cover, crop(cover, 1f), art, fill) else c.drawRect(art, Paint().apply { color = 0x0FFFFFFF })
        c.restore()
        c.drawRoundRect(RectF(art.left + 0.5f, art.top + 0.5f, art.right - 0.5f, art.bottom - 0.5f), ART_R.toFloat(), ART_R.toFloat(), stroke(0x59000000))
        // The words beside it.
        var y = l.textY.toFloat()
        l.metaText?.let {
            c.words(it.uppercase(), TEXT_X.toFloat(), y, paint(600, CardLayout.META_SIZE, 0xFFC2CAD3.toInt()))
            y += CardLayout.META_H + CardLayout.META_GAP
        }
        val tp = paint(700, l.title.size)
        l.title.lines.forEachIndexed { i, line -> c.words(line, TEXT_X.toFloat(), y + i * l.title.lh, tp) }
        y += l.title.lines.size * l.title.lh + CardLayout.BLOCK_GAP
        val ap = paint(400, l.artist.size, 0xFFCDD3D9.toInt())
        l.artist.lines.forEachIndexed { i, line -> c.words(line, TEXT_X.toFloat(), y + i * l.artist.lh, ap) }
        // The hairline across the pane, the review under it, and whose words they are.
        l.desc?.let { d ->
            val rule = l.ruleY + 0.5f
            c.drawLine(ART_X.toFloat(), rule, (ART_X + CONTENT_W).toFloat(), rule, stroke(0x29FFFFFF))
            var dy = l.ruleY + 1f + CardLayout.RULE_GAP
            val dp = paint(400, d.size, 0xFFC2CAD3.toInt())
            d.lines.forEachIndexed { i, line -> c.words(line, ART_X.toFloat(), dy + i * d.lh, dp) }
            dy += d.lines.size * d.lh
            if (l.srcText.isNotEmpty()) c.words(l.srcText, ART_X.toFloat(), dy + CardLayout.SRC_GAP, paint(400, CardLayout.SRC_SIZE, 0xFFC2CAD3.toInt()))
        }
        // Pitchfork's score on the cover's top-right corner, on its own opaque ground; Best New Music under it.
        if (score != null && score.isFinite()) {
            val scoreText = if (score % 1.0 == 0.0) score.toLong().toString() else score.toString()
            val sp = paint(700, CardLayout.SCORE_SIZE)
            val sw = ceil(sp.measureText(scoreText)) + 30
            val sx = ART_X + ART - CardLayout.SCORE_PAD - sw
            val sy = (l.artY + CardLayout.SCORE_PAD).toFloat()
            val badge = RectF(sx, sy, sx + sw, sy + CardLayout.SCORE_H)
            c.drawRoundRect(badge, CardLayout.SCORE_R.toFloat(), CardLayout.SCORE_R.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C0E12.toInt() })
            c.drawRoundRect(badge, CardLayout.SCORE_R.toFloat(), CardLayout.SCORE_R.toFloat(), stroke(0x2EFFFFFF))
            c.words(scoreText, sx + sw / 2, sy + Math.round((CardLayout.SCORE_H - CardLayout.SCORE_SIZE) / 2.0) - 2, sp, center = true)
            if (bestNew) {
                val bp = paint(700, CardLayout.BNM_SIZE)
                val bw = ceil(bp.measureText("BEST NEW MUSIC")) + 22
                val bx = ART_X + ART - CardLayout.SCORE_PAD - bw
                val by = sy + CardLayout.SCORE_H + 8
                c.drawRoundRect(RectF(bx, by, bx + bw, by + CardLayout.BNM_H), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8482B.toInt() })
                c.words("BEST NEW MUSIC", bx + bw / 2, by + Math.round((CardLayout.BNM_H - CardLayout.BNM_SIZE) / 2.0) - 1, bp, center = true)
            }
        }
        // The duck tile, inside the pane's bottom-right corner, a soft shadow under it.
        if (logo != null) {
            val d = CardLayout.LOGO_D.toFloat()
            val x = pane.right - CardLayout.LOGO_PAD - d
            val ty = pane.bottom - CardLayout.LOGO_PAD - d
            val tile = RectF(x, ty, x + d, ty + d)
            c.drawRoundRect(tile, d * 0.2f, d * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF17181B.toInt(); setShadowLayer(7f, 0f, 4f, 0x73000000) })
            c.drawBitmap(logo, null, tile, fill)
        }
        return bmp
    }

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; this.color = color }

    /** The part of [img] that fills a box of [ratio] (width over height), cropped from the middle, as the page's drawCover. */
    private fun crop(img: Bitmap, ratio: Float): Rect {
        val ir = img.width.toFloat() / img.height
        return if (ir > ratio) {
            val sw = (img.height * ratio).roundToInt(); val sx = (img.width - sw) / 2
            Rect(sx, 0, sx + sw, img.height)
        } else {
            val sh = (img.width / ratio).roundToInt(); val sy = (img.height - sh) / 2
            Rect(0, sy, img.width, sy + sh)
        }
    }
}
