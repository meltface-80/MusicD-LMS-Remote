package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Home Screen as the page lists and saves it (item 81). */
class HomeRowsSettingsTest {
    private fun ids(rows: List<HomeRow>) = rows.joinToString(",") { it.id }

    @Test fun `the layout made whole, the phone's rows at the top where the server doesn't know them`() {
        assertEquals(HomeRowsSettings.TITLES.keys.joinToString(","), ids(HomeRowsSettings.complete(emptyList())))
        val saved = listOf(HomeRow("library", false, null), HomeRow("history", true, null))
        val whole = HomeRowsSettings.complete(saved, listOf("phone", "downloads", "history", "library"))
        assertEquals("phone,downloads,library,history", ids(whole))
        assertFalse(whole[2].on)
    }

    @Test fun `each row listed with its name, a setting off or albums on the phone saying why it can't be switched`() {
        // Label of the week gone with the labels (item 492): an older server's row of it is passed over, as one not known.
        val rows = listOf(HomeRow("downloads", false, null), HomeRow("picks", true, "Smart Picks is off in Settings"), HomeRow("lotw", true, null), HomeRow("history", false, null))
        val none = HomeRowsSettings.listed(rows, 0)
        assertEquals("downloads,picks,history", none.joinToString(",") { it.id })
        assertEquals(ListedRow("downloads", "Downloaded albums", null, false), none[0])
        assertEquals(ListedRow("picks", "Smart Picks", "Smart Picks is off in Settings", false), none[1])
        assertFalse(none[1].switchable)
        assertTrue(none[2].switchable)
        val some = HomeRowsSettings.listed(rows, 2)
        assertEquals(ListedRow("downloads", "Downloaded albums", "On while albums are downloaded to this phone", true), some[0])
    }

    @Test fun `a row moved past its neighbour, one the list doesn't show staying put, and switched`() {
        val rows = listOf(HomeRow("later", true, null), HomeRow("soon", true, null), HomeRow("history", true, null), HomeRow("genres", true, null))
        assertEquals("history,soon,later,genres", ids(HomeRowsSettings.swapped(rows, "later", 1)))
        assertEquals("later,soon,genres,history", ids(HomeRowsSettings.swapped(rows, "history", 1)))
        assertEquals(ids(rows), ids(HomeRowsSettings.swapped(rows, "later", -1)))
        assertEquals(ids(rows), ids(HomeRowsSettings.swapped(rows, "genres", 1)))
        assertFalse(HomeRowsSettings.switched(rows, "history", false)[2].on)
    }

    @Test fun `what is saved`() {
        val body = HomeRowsSettings.body(listOf(HomeRow("later", false, "x"), HomeRow("library", true, null)))
        assertEquals(JSONObject("""{"rows":[{"id":"later","on":false},{"id":"library","on":true}]}""").toString(), body.toString())
    }
}
