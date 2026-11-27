package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 158: covers kept on the phone by key and size, so a start away doesn't fetch them all again. */
class CoverDiskTest {
    @Test fun `a cover's file is its key and size, safe as a file name`() {
        assertEquals("al-11710-242798ade3@300.img", CoverDisk.name("al-11710-242798ade3", 300))
        assertNotEquals(CoverDisk.name("al-1-a", 120), CoverDisk.name("al-1-a", 300))
        val odd = CoverDisk.name("../ar/x y?z", 120)
        assertTrue(odd, !odd.contains('/') && !odd.contains(' ') && !odd.contains('?'))
    }

    @Test fun `past the limit the least recently used go first`() {
        val files = listOf(CoverDisk.Kept("new", 40, 300), CoverDisk.Kept("old", 40, 100), CoverDisk.Kept("mid", 40, 200))
        assertEquals(listOf("old"), CoverDisk.trim(files, limit = 80))
        assertEquals(listOf("old", "mid"), CoverDisk.trim(files, limit = 40))
        assertEquals(emptyList<String>(), CoverDisk.trim(files, limit = 120))
    }
}
