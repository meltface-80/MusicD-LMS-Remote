package com.musicd.mallard

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

/*
 * The page's type laid out as the browser lays it (bug list item 54). The
 * page's lines are CSS line boxes: a line is as tall as its line-height, and
 * the glyphs sit in the middle of it, which, when the line-height is less
 * than the font's own height (a heading at 1.2, a tile's title at 1.25),
 * means a negative half-leading at each edge. Compose keeps a text's first
 * line at the font's full ascent and its last at the full descent, so the
 * same text stands taller in the app and its neighbours sit lower than the
 * page's: the heading 5dp down, the tiles' rows further apart.
 */

/**
 * A face of the page's: its ascent and descent as a share of the size (its
 * hhea table), which the browser rounds to whole pixels for the line it
 * calls normal.
 */
internal enum class Face(val ascent: Float, val descent: Float) {
    /** public/fonts/manrope.woff2: 2132 and 600 over 2000. */
    MANROPE(1.066f, 0.3f),
    /** public/fonts/young-serif.woff2: 1046 and 366 over 1000. */
    YOUNG_SERIF(1.046f, 0.366f);

    /** The line the browser calls normal for this face at [size] (CSS px): the ascent and the descent, each rounded. */
    fun normal(size: Float): Int = (ascent * size).roundToInt() + (descent * size).roundToInt()
}

/**
 * The text this is on, at [size], in [line]'s line box as the page has it:
 * where [line] is less than [face]'s own height at [size], the difference
 * comes off the text's height, half from each end, so its glyphs sit where
 * the browser puts them and what follows sits where the page's does. Where
 * the line is the font's height or more, Compose already lays it out as
 * the browser does, and this changes nothing. Goes after the padding
 * modifiers (inside them), with the same size and lineHeight the Text has.
 */
internal fun Modifier.lineBox(size: TextUnit, line: TextUnit, face: Face = Face.MANROPE): Modifier = layout { measurable, constraints ->
    val cut = ((face.normal(size.value) - line.value) * density).coerceAtLeast(0f)
    val p = measurable.measure(constraints)
    val up = (cut / 2).roundToInt()
    layout(p.width, (p.height - cut).roundToInt().coerceAtLeast(0)) { p.placeRelative(0, -up) }
}
