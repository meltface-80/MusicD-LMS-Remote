package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album's page on a tablet held sideways (item 218): the middle column's two write-ups, About this album and
 * about its artist, fitted to the screen as the page's album-wide.js fits them — each as tall as its words, the two
 * shortened together when they don't both fit, the shorter whole when it takes no more than half — and when a
 * tablet counts as one held sideways.
 */
class AlbumWordsTest {
    @Test fun bothWholeWhenTheyFit() {
        assertEquals(10 to 8, AlbumWords.share(10, 8, 30))
        assertEquals(10 to 0, AlbumWords.share(10, 0, 30))
        assertEquals(0 to 12, AlbumWords.share(0, 12, 12))
    }

    @Test fun theShorterWholeAndTheLongerTheRest() {
        assertEquals(4 to 16, AlbumWords.share(4, 40, 20))
        assertEquals(16 to 5, AlbumWords.share(50, 5, 21))
    }

    @Test fun halfEachWhenBothAreLong() {
        assertEquals(10 to 10, AlbumWords.share(30, 40, 20))
        assertEquals(10 to 11, AlbumWords.share(30, 40, 21))
    }

    @Test fun aLineEachAtLeastWhereThereIsAnything() {
        assertEquals(1 to 1, AlbumWords.share(30, 40, 0))
        assertEquals(1 to 0, AlbumWords.share(30, 0, 0))
    }

    @Test fun cutWhereTheWordsRunOnPastTheirShare() {
        assertTrue(AlbumWords.cut(30, 10))
        assertFalse(AlbumWords.cut(10, 10))
        assertFalse(AlbumWords.cut(0, 0))
    }

    @Test fun aTabletHeldSidewaysOnly() {
        assertTrue(AlbumWords.sideways(800, landscape = true))
        assertTrue(AlbumWords.sideways(600, landscape = true))
        assertFalse(AlbumWords.sideways(800, landscape = false), "a tablet upright")
        assertFalse(AlbumWords.sideways(411, landscape = true), "a phone held sideways")
    }

    private fun assertFalse(value: Boolean, message: String) = org.junit.Assert.assertFalse(message, value)
}
