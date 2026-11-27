package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An act's similar acts (item 137): where from, each with whether the library has them and a picture. */
class ArtistSimilarTest {
    @Test fun `similar acts read, those without a name left out`() {
        val s = ArtistSimilar.parse(JSONObject("""{"source":"Deezer","artists":[
            {"name":"Artist B","in_library":true,"photo_key":"u-eA"},
            {"name":"Somebody Else","in_library":false,"photo_key":null},
            {"name":"","in_library":false}]}"""))
        assertEquals("Deezer", s.source)
        assertEquals(listOf("Artist B", "Somebody Else"), s.artists.map { it.name })
        assertTrue(s.artists[0].inLibrary)
        assertFalse(s.artists[1].inLibrary)
        assertEquals("u-eA", s.artists[0].photoKey)
        assertNull(s.artists[1].photoKey)
        val none = ArtistSimilar.parse(JSONObject("""{"source":null,"artists":[]}"""))
        assertNull(none.source)
        assertTrue(none.artists.isEmpty())
    }

    @Test fun `similar albums, the library's own where it has them`() {
        val s = SimilarAlbums.parse(JSONObject("""{"enabled": true, "albums": [
            {"title": "Album Two", "artist": "Artist B", "url": null, "image_key": "k", "album": {"offset": 7, "title": "Album Two", "subtitle": "Artist B"}},
            {"title": "Far Away", "artist": "Artist Z", "url": null, "image_key": null, "album": null}]}"""))
        assertEquals(2, s.albums.size)
        assertEquals(7L, s.albums[0].album!!.offset)
        assertNull(s.albums[1].album); assertEquals("Far Away", s.albums[1].title)
    }
}
