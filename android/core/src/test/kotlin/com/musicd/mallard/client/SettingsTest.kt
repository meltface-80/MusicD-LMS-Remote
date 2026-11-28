package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
    @Test fun `the version at the list's foot`() {
        assertEquals("Mallard server v1.0.85", SettingsLists.version(JSONObject("""{"current": "1.0.85", "available": false}""")))
        assertNull(SettingsLists.version(JSONObject("""{"current": null}""")))
        assertNull(SettingsLists.version(null))
    }

    @Test fun `the sections in the server page's order`() {
        assertEquals(listOf("playback", "labels", "metadata", "homescreen", "smartpicks", "lms", "away", "display", "appearance", "system"),
            SettingsLists.HOME.map { it.pane })
        // Playback and Player settings are one page, Audio Devices, as Mandarin's (owner request).
        assertEquals("Audio Devices", SettingsLists.HOME.first().title)
        assertEquals("", SettingsLists.RESCAN_MODES[0].first)
    }
}
