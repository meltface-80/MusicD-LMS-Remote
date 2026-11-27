package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Home Screen as the page lists and saves it (item 81). */
class HomeRowsSettingsTest {
    private fun ids(rows: List<HomeRow>) = rows.joinToString(",") { it.id }

    @Test fun `the layout made whole, a row the saved layout lacks at the top`() {
        assertEquals(HomeRowsSettings.TITLES.keys.joinToString(","), ids(HomeRowsSettings.complete(emptyList())))
        val saved = listOf(HomeRow("library", false, null), HomeRow("history", true, null))
        val whole = HomeRowsSettings.complete(saved, listOf("unplayed", "picks", "history", "library"))
        assertEquals("unplayed,picks,library,history", ids(whole))
        assertFalse(whole[2].on)
    }

    @Test fun `each row listed with its name, a setting off saying why it can't be switched`() {
        val rows = listOf(HomeRow("picks", true, "Smart Picks is off in Settings"), HomeRow("lotw", true, null), HomeRow("downloads", true, null), HomeRow("history", false, null))
        val listed = HomeRowsSettings.listed(rows, 0)
        assertEquals("a row this server doesn't know is passed over", "picks,lotw,history", listed.joinToString(",") { it.id })
        assertEquals(ListedRow("picks", "Smart Picks", "Smart Picks is off in Settings", false), listed[0])
        assertFalse(listed[0].switchable)
        assertEquals(ListedRow("lotw", "Label of the week", null, true), listed[1])
        assertTrue(listed[2].switchable)
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
