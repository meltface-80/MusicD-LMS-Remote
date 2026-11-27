package com.musicd.mallard.client

/**
 * The album's page on a tablet held sideways (item 218, the owner: as the page's album view in three columns, item
 * 214): when the app lays it out so, and how its middle column's two write-ups — About this album and about its
 * artist — share the screen, as the page's album-wide.js shares them: each as tall as its words; when they don't both
 * fit, the shorter whole if it takes no more than half, the longer the rest, else half each; Read more under one cut
 * short opens the whole of both.
 */
object AlbumWords {
    /**
     * Three columns: a tablet ([smallestWidthDp], the screen's shorter side, 600 or more — Android's own line between a
     * phone and a tablet) held sideways. A phone, either way round, and a tablet upright keep the one column.
     */
    fun sideways(smallestWidthDp: Int, landscape: Boolean): Boolean = landscape && smallestWidthDp >= ScreenSize.TABLET_DP

    /**
     * The lines the review ([review] long) and the biography ([bio] long) are shown in, [room] lines between them;
     * one at least for a write-up there is (its head and Read more say there is more).
     */
    fun share(review: Int, bio: Int, room: Int): Pair<Int, Int> {
        if (review + bio <= room) return review to bio
        val half = room / 2
        val (r, b) = when {
            review <= half -> review to room - review
            bio <= half -> room - bio to bio
            else -> half to room - half
        }
        return (if (review > 0) maxOf(1, r) else 0) to (if (bio > 0) maxOf(1, b) else 0)
    }

    /** Cut short: its words ([lines] long) run on past the [shown] lines it has. */
    fun cut(lines: Int, shown: Int): Boolean = lines > shown
}
