package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A cover put right on an album's page shows in the lists kept from before (bug list item 123): two albums given their
 * covers in the editor (21 October, 13:25) still showed the drawn placeholders in the Library wall, kept as it was loaded.
 */
class CoverFixTest {
    private fun a(offset: Long, key: String?) = AlbumSummary(offset, "Once", "Nightwish", key, "16/44.1", 2004, null, null)

    @Test fun `a tile showing an older cover is put right, and only that`() {
        val wall = listOf(a(2889, "al-2889-b7c884339d"), a(2610, "al-2610-6004f8711c"), a(1860, "al-1860-9b278d26fe"))
        assertEquals(listOf(0 to a(2889, "al-2889-e488d2994356f")), AlbumCovers.fixes(wall, 2889, "al-2889-e488d2994356f"))
        assertEquals("the same cover: nothing", emptyList<Pair<Int, AlbumSummary>>(), AlbumCovers.fixes(wall, 1860, "al-1860-9b278d26fe"))
        assertEquals("not on the wall: nothing", emptyList<Pair<Int, AlbumSummary>>(), AlbumCovers.fixes(wall, 7, "al-7-x"))
    }
}
