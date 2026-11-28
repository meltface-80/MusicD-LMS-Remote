package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The album editor's request, as the web app's Save sends it. */
class AlbumEditTest {
    @Test fun `save sends every field, an empty year as null, art only when chosen`() {
        val b = AlbumEdits.body(7L, "Tungl", "Eivør", "2016", null)
        assertEquals(7L, b.getLong("offset")); assertEquals("Tungl", b.getString("title")); assertEquals(2016, b.getInt("year"))
        assertFalse(b.has("art_url"))
        val c = AlbumEdits.body(7L, "", "", " ", " https://x/y.jpg ")
        assertTrue(c.isNull("year")); assertEquals("https://x/y.jpg", c.getString("art_url")); assertEquals("", c.getString("title"))
        assertNull(AlbumEdits.year("abc")); assertNull(AlbumEdits.year("4000"))
    }

    @Test fun `the album answer carries edited`() {
        val a = Album.parse(JSONObject("""{"album":{"offset":3,"title":"T","subtitle":"A","edited":true},"tracks":[]}"""))
        assertTrue(a.edited)
        assertFalse(Album.parse(JSONObject("""{"album":{"offset":3,"title":"T","subtitle":"A"},"tracks":[]}""")).edited)
        val e = EditedAlbum.parse(JSONObject("""{"album":{"offset":3,"title":"New","subtitle":"B","year":1999,"image_key":"art-1","edited":true}}"""))
        assertEquals("New", e.album.title); assertEquals("art-1", e.album.imageKey); assertTrue(e.edited)
    }
}
