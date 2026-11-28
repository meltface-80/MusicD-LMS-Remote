package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The side menu's collections as index.js answers them (its handlers' own field names). */
class CollectionsTest {
    @Test fun `a switch is off only when the server says false`() {
        val f = Features.parse(JSONObject("""{"features":{"labels":false,"smartPicks":true,"shelf":false}}"""))
        assertFalse(f.on("labels"))
        assertTrue(f.on("smartPicks"))
        assertFalse(f.on("shelf"))
        assertTrue(f.on("something new"))
        assertTrue(Features.parse(null).on("labels"))
    }

    @Test fun `merges, with one whose albums have left`() {
        val m = AlbumMerge.list(JSONObject("""{"total":2,"merges":[
            {"id":"m1","title":"Sandinista!","artist":"The Clash","at":1,"parts":[{"title":"Sandinista! (Disc 1)","artist":"The Clash"},{"title":"Sandinista! (Disc 2)","artist":"The Clash"}],"part_count":2,"offset":12,"image_key":"ab12cd34","present":2},
            {"id":"m2","title":"Gone","artist":"X","at":2,"parts":[],"part_count":3,"offset":null,"image_key":null,"present":0}]}"""))
        assertEquals(listOf("Sandinista! (Disc 1)", "Sandinista! (Disc 2)"), m[0].parts)
        assertEquals(12L, m[0].offset)
        assertNull(m[1].offset)
        assertEquals(0, m[1].present)
    }

    @Test fun `only the owner's own playlist file can be deleted`() {
        fun pl(url: String?, extid: String? = null, remote: Int = 0) = Playlist.parse(JSONObject().put("id", "1").put("title", "t")
            .put("url", url ?: JSONObject.NULL).put("extid", extid ?: JSONObject.NULL).put("remote", remote))
        assertTrue(pl("file:///music/Playlists/Mine.m3u").own)
        assertTrue(pl("/music/Playlists/Mine.m3u8").own)
        assertFalse(pl("qobuz://playlist/123").own)
        assertFalse(pl("file:///music/Playlists/Imported.m3u", extid = "qobuz:playlist:9").own)
        assertFalse(pl("file:///x.m3u", remote = 1).own)
        assertFalse(pl(null).own)
        assertFalse(pl("/music/notes.txt").own)
    }

    @Test fun `playlists, their covers and counts`() {
        val l = Playlist.list(JSONObject("""{"total":2,"playlists":[{"id":"7","title":"Road","url":"file:///p/Road.m3u","art":["a","b",null,"c","d","e"],"tracks":31},{"id":"","title":"x"},{"id":"9","title":"Unknown","art":[],"tracks":null}]}"""))
        assertEquals(listOf("7", "9"), l.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), l[0].art)
        assertEquals(31, l[0].tracks)
        assertNull(l[1].tracks)
    }

    @Test fun `a playlist's tracks`() {
        val t = PlaylistTrack.list(JSONObject("""{"total":2,"tracks":[{"index":0,"title":"Angel","subtitle":"Massive Attack","album":"Mezzanine","duration":379.6,"image_key":"a3f9c210"},{"index":1,"title":"Stream","subtitle":"","album":"","duration":null,"image_key":null}]}"""))
        assertEquals("Massive Attack · Mezzanine", t[0].line)
        assertEquals("", t[1].line)
        assertNull(t[1].duration)
        assertEquals(1, t[1].index)
    }

    @Test fun `dynamic playlists say what they deliver`() {
        val l = LivePlaylist.list(JSONObject("""{"max":50,"playlists":[{"id":"lp1","name":"Late 90s","view":{},"limit":100,"order":"view","total":100,"matched":1179,"art":["k1"]},{"id":"lp2","name":"One","limit":100,"order":"random","total":1,"matched":1,"art":[]}]}"""))
        assertEquals("100 of 1,179 albums", l[0].count)
        assertEquals("1 album", l[1].count)
        assertEquals("random", l[1].order)
    }

    @Test fun `an import keeps what matched for Save and names what didn't`() {
        val i = ShareImport.parse(JSONObject("""{"ok":true,"name":"Road trip","total":3,"truncated":false,"resolved":[{"offset":4,"title":"Angel","artist":"Massive Attack","album_title":"Mezzanine","track_no":1}],"missing":[{"title":"Song","artist":"Band","album":"LP"},{"title":"Only title","artist":"","album":""}]}"""))
        assertEquals(1, i.found)
        assertEquals(listOf("Song — Band", "Only title"), i.missing)
        assertEquals(4, i.resolved.getJSONObject(0).getInt("offset"))
    }
}
