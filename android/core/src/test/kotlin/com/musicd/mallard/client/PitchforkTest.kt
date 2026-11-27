package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pitchfork natively (item 70): its listings, a review handed about, the library's album of it, the route. */
class PitchforkTest {
    @Test fun `a listing's reviews, a review handed about, and the library's album of it`() {
        val l = PitchforkHit.list(JSONObject("""{"type":"best","items":[{"url":"https://pitchfork.com/reviews/albums/b/","album":"Hi Res","artist":"Artist B","cover":null,"score":9.1,"isBestNewMusic":true,"date":"d"},
            {"url":"https://pitchfork.com/reviews/albums/c/","album":"Feed Only","cover":"https://x/c.jpg"}]}"""))
        assertEquals(2, l.size)
        assertEquals("9.1", l[0].scoreText)
        assertTrue(l[0].bestNew)
        assertNull("the feed's reviews have no score", l[1].scoreText)
        assertEquals("Artist B Hi Res", l[0].query)
        assertEquals("Feed Only", l[1].query)
        assertEquals(l[0], PitchforkHit.parse(l[0].json()))
        assertNull(PitchforkHit.parse("not json"))
        assertNull(PitchforkHit.parse("""{"album":"no url"}"""))
        assertEquals(3L, PitchforkHit.match(JSONObject("""{"review":null,"match":{"offset":3,"title":"Album One"}}""")))
        assertNull(PitchforkHit.match(JSONObject("""{"review":null,"match":null}""")))
    }

    @Test fun `the client's paths, and the route`() {
        val asked = ArrayList<String>()
        val c = LibraryClient { asked += it; JSONObject("""{"items":[],"match":null}""") }
        c.pitchforkReviews("latest")
        c.pitchforkMatch(PitchforkHit("https://pitchfork.com/reviews/albums/a/", "Album One", "Artist A", null, null, false, ""))
        assertEquals(listOf("/api/pitchfork/reviews?type=latest",
            "/api/pitchfork/review?url=https%3A%2F%2Fpitchfork.com%2Freviews%2Falbums%2Fa%2F&album=Album%20One&artist=Artist%20A"), asked)
        assertEquals("pitchfork?review=&zone=z", ShellRoute.pitchfork("", "z"))
        assertTrue(ShellRoute.PITCHFORK.startsWith("pitchfork?"))
    }
}
