package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** This phone's screen settings kept by the app, and a backup's page part under the page's own names (item 111). */
class ScreenPrefsTest {
    private val prefs = """{"layout":"list","text":"1.25","greeting":"off","labelOrder":"random","labelMin":"3","appOnly":"kept",
        "view":"sort=year&dir=desc&genre=Jazz","rra-filter":"x"}"""

    @Test fun `a backup under the page's names`() {
        val p = ScreenPrefs.backup(prefs, "RINCON_1")
        assertEquals("list", p.getString("rra-ui-cols"))
        assertEquals("1.25", p.getString("rra-ui-text"))
        assertEquals("off", p.getString("rra-ui-greeting"))
        assertFalse("a label's order and minimum gone with the labels (item 492)", p.has("rra-label-order") || p.has("rra-label-min"))
        assertEquals("RINCON_1", p.getString("rra-zone"))
        assertEquals("x", p.getString("rra-filter"))
        assertFalse("what the app alone keeps is not in a backup", p.has("appOnly"))
        val view = JSONObject(p.getString("rra-library-view"))
        assertEquals("year", view.getString("sort"))
        assertEquals(2, view.getInt("v"))
        assertEquals("Jazz", view.getJSONArray("genre").getString(0))
    }

    @Test fun `restored as the page restores, each held set and each not taken out`() {
        val page = JSONObject("""{"rra-ui-cols":"2","rra-zone":"RINCON_2","rra-np-reduced":"1",
            "rra-library-view":"{\"v\":2,\"sort\":\"artist\",\"dir\":\"desc\",\"seed\":1,\"played\":\"any\",\"label\":[\"ECM\"],\"prefix\":\"\"}"}""")
        val (json, zone) = ScreenPrefs.restore(page, prefs)
        val j = JSONObject(json)
        assertEquals("RINCON_2", zone)
        assertEquals("2", j.getString("layout"))
        assertFalse(j.has("text"))
        assertFalse(j.has("rra-filter"))
        assertEquals("1", j.getString("rra-np-reduced"))
        assertEquals("what the app alone keeps, kept", "kept", j.getString("appOnly"))
        assertEquals("a label kept in a view: MusicD LMS filters by label", "sort=artist&dir=desc&label=ECM", j.getString("view"))
        assertEquals("list", WallPrefs.parse(ScreenPrefs.backup(prefs, null).let { ScreenPrefs.restore(it, null).first }).layout)
    }

    @Test fun `a setting set or taken out`() {
        assertEquals("on", JSONObject(ScreenPrefs.with(prefs, "random", "on")).getString("random"))
        assertFalse(JSONObject(ScreenPrefs.with(prefs, "view", null)).has("view"))
        assertNull(ScreenPrefs.restore(JSONObject(), null).second)
    }
}
