package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The side menu's words (item 67), as the page's menu-drawer.js and power.js write them. */
class MenuTest {
    private val now = 1_000_000_000_000L

    @Test fun `the Rescan row's line says what the last check was, never what is happening now`() {
        assertEquals("", Menu.rescanSub(null, now))
        assertEquals("Server not ready", Menu.rescanSub(JSONObject("""{"paired":false}"""), now))
        assertEquals("3 albums · checked just now", Menu.rescanSub(JSONObject("""{"paired":true,"index_count":3,"index_built_at":${now - 60_000}}"""), now))
        assertEquals("3 albums · scanning the music folder now", Menu.rescanSub(JSONObject("""{"paired":true,"index_count":3,"library_importing":true,"index_built_at":1}"""), now))
        assertEquals("3 albums · the library moved, checking again shortly", Menu.rescanSub(JSONObject("""{"paired":true,"index_count":3,"library_recheck_pending":true}"""), now))
        assertEquals("No snapshot yet", Menu.rescanSub(JSONObject("""{"paired":true,"index_count":0}"""), now))
    }

    @Test fun `how long ago, rounded as the page rounds`() {
        assertEquals("just now", Menu.ageText(now - 89_000, now))
        assertEquals("2 min ago", Menu.ageText(now - 90_000, now))
        assertEquals("59 min ago", Menu.ageText(now - 59 * 60_000, now))
        assertEquals("1 hour ago", Menu.ageText(now - 60 * 60_000, now))
        assertEquals("47 hours ago", Menu.ageText(now - 47 * 3_600_000L, now))
        assertEquals("2 days ago", Menu.ageText(now - 48 * 3_600_000L, now))
    }

    @Test fun `a rescan's answer, for its toast`() {
        val fresh = Menu.rescan(JSONObject("""{"status":"unchanged","count":12,"services":["Qobuz","Tidal"]}"""))
        assertEquals("Library already up to date — 12 albums · now Qobuz, then Tidal", fresh.text)
        assertFalse(fresh.error); assertFalse(fresh.going)
        assertEquals("Library rescanned — 12 albums (1 new track)", Menu.rescan(JSONObject("""{"status":"updated","albums":12,"added":1}""")).text)
        assertEquals("Library rescanned — 12 albums (4 new tracks)", Menu.rescan(JSONObject("""{"status":"rebuilt","count":12,"added":4}""")).text)
        val scanning = Menu.rescan(JSONObject("""{"status":"scanning"}"""))
        assertEquals("Scanning in the background — new albums appear as it goes", scanning.text)
        assertTrue(scanning.going); assertFalse(scanning.error)
        assertEquals("A scan is already running — you'll hear when it's done", Menu.rescan(JSONObject("""{"status":"busy"}""")).text)
        val gone = Menu.rescan(JSONObject("""{"status":"no-music"}"""))
        assertEquals(Menu.NO_MUSIC, gone.text); assertTrue(gone.error)
        assertEquals("Rescan failed", Menu.rescan(JSONObject("{}")).text)
        assertEquals("importing", Menu.rescan(JSONObject("""{"status":"importing","error":"importing"}""")).text)
    }

    @Test fun `a scan waited for, ended or not`() {
        assertNull(Menu.scanEnded(JSONObject("""{"scan":{"running":true}}""")))
        assertNull(Menu.scanEnded(JSONObject("{}")))
        assertEquals("Library rescanned — 7 albums (2 new tracks)", Menu.scanEnded(JSONObject("""{"index_count":7,"scan":{"running":false,"last":{"status":"updated","added":2}}}"""))!!.text)
        assertTrue(Menu.scanEnded(JSONObject("""{"scan":{"running":false,"last":{"status":"no-music"}}}"""))!!.error)
    }

    @Test fun `the rows that come and go, an answer missing leaving its row as it was`() {
        assertEquals(Menu.Items(qobuz = false, tidal = false, shelf = true), Menu.items(null, null, null))
        assertEquals(Menu.Items(qobuz = true, tidal = true, shelf = false),
            Menu.items(JSONObject("""{"connected":true}"""), JSONObject("""{"connected":true}"""), JSONObject("""{"enabled":false}""")))
    }

    @Test fun `the menu opens as it was last seen, not as the page's markup has it`() {
        // Item 122: opened, the menu drew Labels and Shelf (the markup's), then took them away as the server answered
        // they were off, and the rescan line came in under them; at 11:58 on 21 October, at each opening.
        val seen = Menu.Items(qobuz = false, tidal = false, shelf = false)
        val status = JSONObject("""{"paired":true,"index_count":7,"index_built_at":1000}""")
        val kept = Menu.keep(seen, status)
        assertEquals(seen, Menu.keptItems(kept))
        assertEquals("7 albums · checked just now", Menu.rescanSub(Menu.keptStatus(kept), 2000))
        // Never opened: the rows that come and go not drawn until the server says (shown, not taken away).
        assertEquals(Menu.Items(qobuz = false, tidal = false, shelf = false), Menu.keptItems(null) ?: Menu.UNKNOWN)
        assertNull(Menu.keptItems("not json"))
        assertNull(Menu.keptStatus(null))
        // An answer missing leaves the row as it was last seen, not as the markup has it.
        assertEquals(seen, Menu.items(null, null, null, seen))
    }

    @Test fun `offline's line, and Shut down only where the server can`() {
        // One line, as the owner asked (item 98): the row's name with On or Off after it.
        assertEquals("Offline Mode - On", Menu.offlineSub(true))
        assertEquals("Offline Mode - Off", Menu.offlineSub(false))
        assertTrue(Menu.canShutDown(null))
        assertFalse(Menu.canShutDown(JSONObject("""{"shutdown":false}""")))
    }
}
