package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Saving a Dynamic Playlist, as the web app's saveLivePlaylistPrompt does it. */
class LiveSaveTest {
    @Test fun `a name from the rules on`() {
        assertEquals("My Dynamic Playlist", LiveSaves.suggest(LibraryView()))
        val v = LibraryView(played = "never", facets = mapOf("decade" to listOf("2000", "1990"), "genre" to listOf("Jazz", "!Pop")))
        assertEquals("Never played 1990s + 2000s Jazz", LiveSaves.suggest(v))
        assertEquals("Unplayed 6m", LiveSaves.suggest(LibraryView(played = "6")))
        assertEquals("Qobuz", LiveSaves.suggest(LibraryView(facets = mapOf("source" to listOf("Qobuz")))))
    }

    @Test fun `new takes the server's size, an edit keeps its own`() {
        val b = LiveSaves.body("Jazz", LibraryView(), null)
        assertFalse(b.has("id")); assertFalse(b.has("limit")); assertEquals("album", b.getJSONObject("view").getString("sort"))
        val e = LiveSaves.body("Jazz", LibraryView(), LiveEdit("lp1", "Old", 200, "random", LibraryView()))
        assertEquals("lp1", e.getString("id")); assertEquals(200, e.getInt("limit")); assertEquals("random", e.getString("order"))
    }

    @Test fun `what it plays, said`() {
        assertEquals("Saved “Jazz” — 12 albums right now", LiveSaves.said("Jazz", JSONObject("""{"ok":true,"playlist":{"total":12,"matched":12}}""")))
        assertEquals("Saved “Jazz” — it plays 100 of the 1,179 albums that match. Change that with Edit.",
            LiveSaves.said("Jazz", JSONObject("""{"ok":true,"playlist":{"total":100,"matched":1179}}""")))
    }
}
