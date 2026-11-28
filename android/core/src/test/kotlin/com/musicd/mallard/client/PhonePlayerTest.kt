package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own player away from home, as /api/phone/hello answers and the stream is asked for. */
class PhonePlayerTest {
    @Test fun `hello and the stream's address`() {
        val h = PhoneHello.parse(JSONObject("""{"player_id":"02:6d:aa:bb:cc:dd","name":"Pixel 8","away":true}"""))
        assertEquals("02:6d:aa:bb:cc:dd", h.playerId); assertTrue(h.away)
        assertEquals("/api/phone/stream?player_id=02%3A6d%3Aaa%3Abb%3Acc%3Add&q=opus", PhoneStream.path(h.playerId, "opus"))
        assertEquals("/api/phone/stream?player_id=x&q=flac", PhoneStream.path("x", "flac"))
        assertEquals("opus", PhoneStream.quality("anything else"))
    }
}
