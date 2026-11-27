package com.musicd.mallard.client

import org.json.JSONObject

/*
 * The share card, typed: what the card says (from /api/album/extras, as public/share-sheet.js's cardFields reads it),
 * and the card's layout, measured as public/sharecard.js measures it before it draws: the height is a result of the
 * words, so everything is wrapped first. Ported from Mandarin, whose web and Android cards are twins; here too the page
 * (public/sharecard.js) and this file are TWO COPIES OF ONE LAYOUT — change one, change both (CLAUDE.md, Mallard).
 */

/** What the card shows: the release (raw), the review and whose words they are, Pitchfork's score and flag. */
data class CardFields(val releaseRaw: String, val reviewText: String, val reviewSource: String, val score: Double?, val bestNew: Boolean) {
    /** What decides whether the card must be drawn again once the full answer comes. */
    val signature: String get() = listOf(releaseRaw, reviewText, reviewSource, score?.let(::num).orEmpty(), bestNew.toString()).joinToString("|")

    companion object {
        val NONE = CardFields("", "", "", null, false)

        /** From an extras answer (none: the card with title and artist only); the review always, where there is one (item 336). */
        fun parse(j: JSONObject?): CardFields {
            if (j == null) return NONE
            val a = j.optJSONObject("album")
            val release = text(j, "year") ?: a?.let { text(it, "year") }.orEmpty()
            val score = a?.takeIf { it.has("score") && !it.isNull("score") }?.optDouble("score")?.takeIf { !it.isNaN() }
            val review = a?.let { text(it, "description") }?.let(ShareCard::trimReview).orEmpty()
            // This server names it descriptionSource; Mandarin's, description_source.
            val source = if (review.isNotEmpty()) a?.let { text(it, "descriptionSource") ?: text(it, "description_source") }.orEmpty() else ""
            return CardFields(release, review, source, score, a?.optBoolean("isBestNewMusic") ?: false)
        }
    }
}

/** The card's rules beside its drawing: the review trimmed, the file's name, the hint. */
object ShareCard {
    const val GENERATING = "Generating card…"
    const val FAILED = "Could not generate the card."
    const val HINT = "Tap a button above, or long-press the card to save."

    /** The review as the card carries it: ten sentences at most, and 1,400 characters, cut at a word with "…". */
    fun trimReview(desc: String): String {
        var t = desc.trim()
        val sentences = Regex("[^.!?]+[.!?]+").findAll(t).map { it.value }.toList()
        if (sentences.size > 10) t = sentences.take(10).joinToString(" ").trim()
        if (t.length > 1400) t = t.take(1398).replace(Regex("\\s+\\S*$"), "") + "…"
        return t
    }

    /** The saved card's name: the artist and the title, anything but letters and digits made "_". */
    fun fileName(title: String, artist: String): String =
        artist.ifEmpty { "artist" }.replace(Regex("[^a-zA-Z0-9]+"), "_") + "-" + title.ifEmpty { "card" }.replace(Regex("[^a-zA-Z0-9]+"), "_") + ".png"
}

/**
 * The card's layout, as sharecard.js lays it out (1200 wide, 600 to 1800 tall): the pane on the softened cover, the
 * 424 cover, the release line, the title and the artist (each up to four lines, the size stepped down until it fits),
 * and the review under a hairline across the whole pane, with whose words they are. [measure] is a font's width of a
 * text at a weight and a size (sharecard.js's ctx.measureText), so the arithmetic is held here without a canvas.
 */
object CardLayout {
    const val CARD_W = 1200
    const val MIN_CARD_H = 600
    const val MAX_CARD_H = 2100   // 1800 until the review was a fifth bigger (item 336)
    const val INSET = 0   // the pane the whole card, as the page's sharecard.js (item 387; it was inset 48)
    const val PANE_W = CARD_W - INSET * 2
    const val PANE_R = 0
    const val PANE_PAD = 56
    const val ART = 464   // 424 before item 387
    const val ART_R = 18
    const val ART_X = INSET + PANE_PAD
    const val TEXT_X = ART_X + ART + 44
    const val TEXT_W = INSET + PANE_W - PANE_PAD - TEXT_X   // as far from the right edge as the cover from the left (item 387; 44)
    const val CONTENT_W = PANE_W - PANE_PAD * 2
    const val LOGO_D = 96
    const val LOGO_PAD = 32
    const val SCORE_PAD = 14
    const val SCORE_H = 54
    const val SCORE_R = 12
    const val SCORE_SIZE = 30
    const val BNM_H = 26
    const val BNM_SIZE = 15
    const val DESC_SIZE = 33   // 31 until the pane was the whole card (item 387), as the page's sharecard.js
    const val DESC_LH = 49
    const val DESC_MAX = 27
    const val RULE_GAP = 30
    const val SRC_SIZE = 20
    const val SRC_H = SRC_SIZE + 4
    const val SRC_GAP = 26
    const val META_SIZE = 26
    const val META_H = META_SIZE + 4
    const val META_GAP = 24
    const val BLOCK_GAP = 18
    private val TITLE_SIZES = listOf(56, 48, 42, 36, 31, 27)
    private val ARTIST_SIZES = listOf(37, 32, 28, 24, 21)
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Lines of text at one size, and their height each. */
    data class Fit(val lines: List<String>, val size: Int, val lh: Int)

    /** Everything measured, and where it goes: the card's height, the pane's, where its block, the cover and the words start. */
    data class Layout(val metaText: String?, val title: Fit, val artist: Fit, val desc: Fit?, val srcText: String, val headerTextH: Int, val headerH: Int,
                      val contentH: Int, val cardH: Int) {
        val paneH: Int get() = cardH - INSET * 2
        val contentY: Int get() = INSET + maxOf(PANE_PAD, Math.round((paneH - contentH) / 2.0).toInt())
        val artY: Int get() = contentY + Math.round((headerH - ART) / 2.0).toInt()
        val textY: Int get() = contentY + Math.round((headerH - headerTextH) / 2.0).toInt()
        /** Where the hairline is, over the review. */
        val ruleY: Int get() = contentY + headerH + RULE_GAP
    }

    /** A release as the card writes it: "20 Nov 2006", "Nov 2006", "2006", or as it came. */
    fun releaseDate(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(s)?.destructured?.let { (y, mo, d) ->
            if (mo.toInt() in 1..12 && d.toInt() in 1..31) return "${d.toInt()} ${MONTHS[mo.toInt() - 1]} $y"
        }
        Regex("^(\\d{4})-(\\d{1,2})$").find(s)?.destructured?.let { (y, mo) -> if (mo.toInt() in 1..12) return "${MONTHS[mo.toInt() - 1]} $y" }
        return s
    }

    /** [text] wrapped to [maxWidth] in [maxLines] at most, and whether it didn't all fit (or a word is wider than the column). */
    fun wrap(width: (String) -> Float, text: String, maxWidth: Int, maxLines: Int): Pair<List<String>, Boolean> {
        if (text.isEmpty()) return emptyList<String>() to false
        val lines = mutableListOf<String>()
        var cur = ""
        var overflow = false
        for (w in text.split(Regex("\\s+"))) {
            val candidate = if (cur.isNotEmpty()) "$cur $w" else w
            if (width(candidate) <= maxWidth) { cur = candidate; continue }
            if (cur.isNotEmpty()) lines.add(cur)
            if (lines.size >= maxLines) { cur = ""; overflow = true; break }
            cur = w
            if (width(w) > maxWidth) overflow = true
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines.add(cur) else if (cur.isNotEmpty()) overflow = true
        return lines to overflow
    }

    /** [text] fitted in [maxLines]: each of [sizes] tried until it fits; at the smallest, the last line cut with "…". */
    fun fit(measure: (String, Int, Int) -> Float, text: String, maxWidth: Int, maxLines: Int, weight: Int, sizes: List<Int>, lhRatio: Double): Fit {
        var r: Pair<List<String>, Boolean> = emptyList<String>() to false
        var size = sizes[0]
        for (s in sizes) {
            size = s
            r = wrap({ measure(it, weight, s) }, text, maxWidth, maxLines)
            if (!r.second) break
        }
        val lines = r.first.toMutableList()
        if (r.second && lines.isNotEmpty()) {
            var last = lines.last()
            while (last.isNotEmpty() && measure("$last…", weight, size) > maxWidth) last = last.dropLast(1)
            lines[lines.size - 1] = last.trimEnd() + "…"
        }
        return Fit(lines, size, Math.round(size * lhRatio).toInt())
    }

    /** The card measured: [measure] a width at a weight and a size; the review dropped below two lines' room. */
    fun measure(measure: (String, Int, Int) -> Float, title: String, artist: String, releaseRaw: String, review: String, reviewSource: String): Layout {
        val metaText = releaseDate(releaseRaw)?.let { "Released $it" }
        val t = fit(measure, title, TEXT_W, 4, 700, TITLE_SIZES, 68.0 / 56)
        val a = fit(measure, "by $artist", TEXT_W, 4, 400, ARTIST_SIZES, 48.0 / 37)
        val headerTextH = (if (metaText != null) META_H + META_GAP else 0) + t.lines.size * t.lh + BLOCK_GAP + a.lines.size * a.lh
        val headerH = maxOf(ART, headerTextH)
        val src = reviewSource.trim()
        val srcH = if (src.isNotEmpty()) SRC_GAP + SRC_H else 0
        val room = MAX_CARD_H - INSET * 2 - PANE_PAD * 2 - headerH - (RULE_GAP * 2 + 1) - srcH
        val maxDesc = minOf(DESC_MAX, Math.floorDiv(room, DESC_LH))
        val desc = if (review.isNotEmpty() && maxDesc >= 2) fit(measure, review, CONTENT_W, maxDesc, 400, listOf(DESC_SIZE), DESC_LH.toDouble() / DESC_SIZE) else null
        val descH = if (desc != null) RULE_GAP + 1 + RULE_GAP + desc.lines.size * desc.lh + srcH else 0
        val contentH = headerH + descH
        val cardH = maxOf(MIN_CARD_H, minOf(MAX_CARD_H, contentH + PANE_PAD * 2 + INSET * 2))
        return Layout(metaText, t, a, desc, if (desc != null) src else "", headerTextH, headerH, contentH, cardH)
    }
}

private fun num(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** A field's words: a number as the page writes it (2002, not 2002.0); none when it is missing, null or empty. */
private fun text(j: JSONObject, key: String): String? {
    if (!j.has(key) || j.isNull(key)) return null
    val v = j.opt(key)
    val s = if (v is Number) num(v.toDouble()) else v.toString()
    return s.ifEmpty { null }
}

