package com.musicd.mallard.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shelf's corners natively (item 219): the way back to the remote and the choices' tab, as the page has them —
 * gone under an open album, whose own Back takes their place.
 */
class ShelfCornersTest {
    @Test fun theWayBackOnTheShelfAndNotOverAnAlbum() {
        assertTrue(ShelfCorners.remote(albumOpen = false))
        assertFalse(ShelfCorners.remote(albumOpen = true))
    }

    @Test fun theChoicesTabWhileTheChoicesAreAwayAndNoAlbumIsOpen() {
        assertTrue(ShelfCorners.pickTab(albumOpen = false, pickOpen = false))
        assertFalse(ShelfCorners.pickTab(albumOpen = false, pickOpen = true))
        assertFalse(ShelfCorners.pickTab(albumOpen = true, pickOpen = false))
    }
}
