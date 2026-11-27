package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server's keyFor, case for case (lib/favourites.test.js): the hearts Mallard paints depend on agreeing with it. */
class FavouriteKeyTest {
    @Test fun `titles and artists fold as the server folds them`() {
        assertEquals("mezzanine|massive attack", FavouriteKey.of("Mezzanine", "Massive Attack"))
        assertEquals("homogenic|bjork", FavouriteKey.of("Homogenic", "Björk"))
        assertEquals("vol 1|x", FavouriteKey.of("Vol. 1", "X"))
        assertEquals("ok computer|radiohead", FavouriteKey.of("  OK   Computer ", "Radiohead"))
    }

    @Test fun `a title of symbols alone keeps its symbols, the artist never does`() {
        assertEquals("<|°_°|>|caravan palace", FavouriteKey.of("<|°_°|>", "Caravan Palace"))
        assertEquals("+|ed sheeran", FavouriteKey.of("+", "Ed Sheeran"))
        assertEquals("myth takes|", FavouriteKey.of("Myth Takes", "!!!"))
        assertNull(FavouriteKey.of("", "Anyone"))
        assertNull(FavouriteKey.of("   ", "Anyone"))
    }

    @Test fun `what LMS's mirror is said as`() {
        assertEquals("Added to Favourites", FavouriteKey.said(true, "ok"))
        assertEquals("Added to Favourites · not in LMS until the library has it", FavouriteKey.said(true, "no-address"))
        assertEquals("Removed from Favourites", FavouriteKey.said(false, "failed"))
    }
}
