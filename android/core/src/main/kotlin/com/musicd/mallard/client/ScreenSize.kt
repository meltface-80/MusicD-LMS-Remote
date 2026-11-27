package com.musicd.mallard.client

/**
 * The app on a phone, a tablet and a foldable (item 221, the owner): Android's own line between a phone and a tablet, a
 * screen whose shorter side is 600 dp or more ([TABLET_DP]). Below it, a phone (a foldable folded among them) is held
 * upright whatever its rotation setting; at or above it, a tablet, and a foldable opened, turns freely and is laid out as
 * a tablet. AlbumWords.sideways draws the album's three columns by the same line.
 */
object ScreenSize {
    const val TABLET_DP = 600

    /** The phone a Shelf's tiles are sized for: a phone held upright, 411 dp across. */
    private const val PHONE_DP = 411

    /** Held upright, whatever the device's rotation setting: a screen whose shorter side ([smallestWidthDp]) is under a tablet's. */
    fun portraitOnly(smallestWidthDp: Int): Boolean = smallestWidthDp < TABLET_DP

    /**
     * The Shelf's choices, as many across a screen [widthDp] wide as fit at the size a phone shows them [phone] across:
     * a phone keeps its own, a wide screen more of the same size rather than bigger ones.
     */
    fun shelfColumns(phone: Int, widthDp: Int): Int = maxOf(phone, phone * widthDp / PHONE_DP)
}
