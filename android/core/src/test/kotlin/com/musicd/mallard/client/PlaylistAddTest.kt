package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Add to playlist's words, as the web app's send() says them. */
class PlaylistAddTest {
    @Test fun `what is sent`() {
        assertEquals("""{"offsets":[3,9]}""", PlaylistAdds.albums(listOf(3L, 9L)).toString())
        assertEquals("""{"offset":4,"tracks":[0,2]}""", PlaylistAdds.tracks(4L, listOf(0, 2)).toString())
        assertEquals("Every track from 1 selected album.", PlaylistAdds.describeAlbums(1))
        assertEquals("3 selected tracks.", PlaylistAdds.describeTracks(3))
    }

    @Test fun `what is said of the answer`() {
        assertEquals("Added 12 tracks to new playlist “Road”", PlaylistAdds.said(JSONObject("""{"ok":true,"created":true,"added":12,"skipped":0}"""), "Road"))
        assertEquals("Added 1 track to existing “Road” (2 skipped)", PlaylistAdds.said(JSONObject("""{"ok":true,"created":false,"added":1,"skipped":2}"""), "Road"))
        assertEquals("Added 5 tracks to playlist", PlaylistAdds.said(JSONObject("""{"ok":true,"created":false,"added":5}"""), null))
    }
}
