package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browse Qobuz as the server's /api/qobuz routes answer, and the page's words. */
class QobuzTest {
    @Test fun `a level of nodes and albums`() {
        val l = QobuzLevel.parse(JSONObject("""{"title":"Browse Qobuz","total":2,"start":0,"notice":null,"items":[
            {"kind":"node","item_id":"0.1","title":"New releases"},
            {"kind":"album","token":"t1","title":"Tungl","subtitle":"Eivør","source":"qobuz","image_key":"url-x","can_queue":true,"can_favorite":true,"fav_key":"k"}]}"""))
        assertTrue(l.items[0].node); assertEquals("0.1", l.items[0].itemId)
        assertFalse(l.items[1].node); assertEquals("t1", l.items[1].token); assertEquals("url-x", l.items[1].imageKey); assertTrue(l.items[1].canFavorite)
        assertEquals(null, l.notice)
    }

    @Test fun `an album's tracks and favourite`() {
        val a = QobuzAlbum.parse(JSONObject("""{"favorite":true,"can_favorite":true,"tracks":[{"title":"A","artist":"B","duration":201.5,"token":"tt","can_queue":false}],"notice":null}"""))
        assertTrue(a.favorite); assertEquals(201.5, a.tracks[0].duration!!, 0.0); assertFalse(a.tracks[0].canQueue)
        assertEquals("✓ In Qobuz", QobuzWords.favourite(true)); assertEquals("＋ Add to Qobuz", QobuzWords.favourite(false))
        assertEquals("Queued → Kitchen", QobuzWords.played("queue", "Kitchen"))
    }
}
