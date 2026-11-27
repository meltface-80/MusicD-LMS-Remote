package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app on a phone, a tablet and a foldable (item 221): a phone (a foldable folded among them) held upright whatever
 * its rotation setting; a tablet, and a foldable opened, turning freely; and the Shelf's choices as many tiles across as
 * fit at a phone's size, so a wide screen shows more of them rather than bigger ones.
 */
class ScreenSizeTest {
    @Test fun aPhoneIsHeldUprightATabletOrAnOpenedFoldableTurns() {
        assertTrue("a Nothing Phone 2a (412 dp)", ScreenSize.portraitOnly(412))
        assertTrue("a Pixel Fold folded (about 400 dp)", ScreenSize.portraitOnly(400))
        assertTrue("a small phone", ScreenSize.portraitOnly(320))
        assertFalse("a Pixel Fold opened (about 673 dp)", ScreenSize.portraitOnly(673))
        assertFalse("a 7-inch tablet (600 dp)", ScreenSize.portraitOnly(600))
        assertFalse("a 10-inch tablet (800 dp)", ScreenSize.portraitOnly(800))
    }

    @Test fun aPhoneKeepsItsColumns() {
        for (w in listOf(320, 360, 411, 430)) {
            assertEquals(3, ScreenSize.shelfColumns(3, w))
            assertEquals(6, ScreenSize.shelfColumns(6, w))
            assertEquals(2, ScreenSize.shelfColumns(2, w))
            assertEquals(1, ScreenSize.shelfColumns(1, w))
        }
    }

    @Test fun aWideScreenFitsMoreTilesOfAPhonesSize() {
        assertEquals("a Pixel Fold opened, its genres", 5, ScreenSize.shelfColumns(3, 780))
        assertEquals("a tablet held sideways, its genres", 9, ScreenSize.shelfColumns(3, 1280))
        assertEquals("a Pixel Fold opened, its letters", 11, ScreenSize.shelfColumns(6, 780))
        assertEquals("a Pixel Fold opened, its labels", 3, ScreenSize.shelfColumns(2, 780))
        assertEquals("an order a row each up to twice a phone's width", 1, ScreenSize.shelfColumns(1, 780))
    }
}
