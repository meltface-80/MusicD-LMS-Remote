package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
    @Test fun `the version at the list's foot`() {
        assertEquals("MusicD LMS Remote v1.0.85", SettingsLists.version(JSONObject("""{"current": "1.0.85", "available": false}""")))
        assertNull(SettingsLists.version(JSONObject("""{"current": null}""")))
        assertNull(SettingsLists.version(null))
    }

    @Test fun `the sections in the server page's order`() {
        assertEquals(listOf("playback", "player", "labels", "metadata", "homescreen", "smartpicks", "lms", "away", "display", "shelf", "appearance", "system", "connection"),
            SettingsLists.HOME.map { it.pane })
        assertEquals("", SettingsLists.RESCAN_MODES[0].first)
    }
}
