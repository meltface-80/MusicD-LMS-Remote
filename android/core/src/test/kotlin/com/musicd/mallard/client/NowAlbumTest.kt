package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Now playing's album found as the page's mini-transport.js finds it (item 111). */
class NowAlbumTest {
    private val results = JSONObject("""{"results":[
        {"offset":1,"title":"Purple Rain","subtitle":"Bonnie \"Prince\" Billy"},
        {"offset":2,"title":"Purple Rain","subtitle":"Prince & The Revolution"},
        {"offset":3,"title":"Kind of Blue","subtitle":"Miles Davis"}]}""")

    @Test fun `the playing track's own album first`() {
        assertEquals(42L, NowAlbum.playing(JSONObject("""{"album":{"offset":42,"title":"X"}}""")))
        assertNull(NowAlbum.playing(JSONObject("""{"album":null}""")))
        assertNull(NowAlbum.playing(null))
    }

    @Test fun `a shared credited name, not a word of one`() {
        assertEquals(2L, NowAlbum.match(results, "Purple Rain", "Prince"))
        assertEquals(1L, NowAlbum.match(results, "purple rain!", "Bonnie \"Prince\" Billy"))
        assertEquals(3L, NowAlbum.match(results, "Kind of Blue", "Miles Davis feat. John Coltrane"))
        assertNull(NowAlbum.match(results, "Kind of Blue", "Bill Evans"))
    }

    @Test fun `no artist, the name alone`() {
        assertEquals(1L, NowAlbum.match(results, "Purple Rain", ""))
        assertNull(NowAlbum.match(results, "Thriller", ""))
        assertEquals("/api/search?q=Kind%20of%20Blue&limit=20", NowAlbum.searchPath("Kind of Blue"))
    }

    @Test fun `the phone's own album playing is known by its key, the library's is not (item 197)`() {
        assertEquals("a1b2c3", NowAlbum.phoneKey("phone:a1b2c3"))
        assertNull(NowAlbum.phoneKey("42"))
        assertNull(NowAlbum.phoneKey("phone:"))
        assertNull(NowAlbum.phoneKey(null))
    }
}
