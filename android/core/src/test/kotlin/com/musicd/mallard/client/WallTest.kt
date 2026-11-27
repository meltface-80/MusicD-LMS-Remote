package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's UI Settings read for the native Library wall (Wall.kt), as ui-settings.js reads them. */
class WallTest {
    @Test fun `the page's settings read, the defaults where it says nothing or nonsense`() {
        val p = WallPrefs.parse("""{"layout":"list","tile":"1.25","text":"1.5","title":"1.1"}""")
        assertEquals(WallPrefs("list", 1.25, 1.5, 1.1), p)
        assertTrue(p.list)
        assertEquals(WallPrefs(), WallPrefs.parse(null))
        assertEquals(WallPrefs(), WallPrefs.parse("not json"))
        assertEquals("a value the page's selects don't offer is the default", WallPrefs(), WallPrefs.parse("""{"layout":"7","tile":"3","text":"x"}"""))
        assertFalse(WallPrefs.parse("""{"layout":"3"}""").list)
        assertEquals("the menu's size (item 67)", 1.75, WallPrefs.parse("""{"menu":"1.75"}""").menu, 0.0)
        assertEquals(1.0, WallPrefs.parse("""{"menu":"3"}""").menu, 0.0)
    }

    @Test fun `Home's greeting and Random Album, each on unless the page says off`() {
        assertTrue(WallPrefs.parse("{}").greeting); assertTrue(WallPrefs.parse("{}").random)
        val p = WallPrefs.parse("""{"greeting":"off","random":"on"}""")
        assertFalse(p.greeting); assertTrue(p.random)
        assertFalse(WallPrefs.parse("""{"random":"off"}""").random)
    }

    @Test fun `Album of the Day, on unless the page says off (item 236)`() {
        assertTrue(WallPrefs.parse("{}").aotd)
        assertFalse(WallPrefs.parse("""{"aotd":"off"}""").aotd)
        assertTrue(WallPrefs.parse("""{"aotd":"on","random":"off"}""").aotd)
    }

    @Test fun `the columns are the page's breakpoints divided by the tile size, or fixed`() {
        val auto = WallPrefs()
        assertEquals("a phone", 3, auto.cols(412, 915))
        assertEquals("a tablet upright", 5, auto.cols(800, 1280))
        assertEquals("a tablet on its side", 7, auto.cols(1024, 768))
        assertEquals("a desktop", 9, auto.cols(1400, 900))
        assertEquals("bigger tiles, fewer columns", 2, WallPrefs(tile = 1.5).cols(412, 915))
        assertEquals("smaller tiles, more", 6, WallPrefs(tile = 0.5).cols(412, 915))
        assertEquals("fixed", 2, WallPrefs(layout = "2", tile = 0.5).cols(412, 915))
        assertEquals("a list asks the same count as Auto", 3, WallPrefs(layout = "list").cols(412, 915))
    }

    @Test fun `the wall's route and the client's query carry the page's view`() {
        assertEquals("library?view=sort%3Dalbum%26dir%3Dasc&prefs=%7B%22tile%22%3A%221%22%7D&zone=z%201", ShellRoute.library("sort=album&dir=asc", """{"tile":"1"}""", "z 1"))
        assertEquals("library?view={view}&prefs={prefs}&zone={zone}", ShellRoute.LIBRARY)
        val asked = ArrayList<String>()
        val client = LibraryClient { path -> asked += path; org.json.JSONObject("""{"albums":[],"offset":0,"total":0}""") }
        client.albums(view = "sort=year&dir=desc&genre=Rock")
        client.albums(offset = 60, view = "")
        assertEquals(listOf("/api/library/albums?count=60&sort=year&dir=desc&genre=Rock", "/api/library/albums?offset=60&count=60"), asked)
    }
}
