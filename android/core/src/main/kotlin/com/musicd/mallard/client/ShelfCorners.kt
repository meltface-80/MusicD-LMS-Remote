package com.musicd.mallard.client

/**
 * The Shelf's corners natively (item 219), as the page has them (shelf-open.css draws the album over the corner
 * pills): the way back to the remote and the choices' tab, gone under an open album, whose own Back takes their place.
 */
object ShelfCorners {
    /** The way back to the remote ("‹ Remote"): on the shelf, not over an album opened from it. */
    fun remote(albumOpen: Boolean): Boolean = !albumOpen

    /** The choices' tab hanging from the top: while the choices are away and no album is open. */
    fun pickTab(albumOpen: Boolean, pickOpen: Boolean): Boolean = !albumOpen && !pickOpen
}
