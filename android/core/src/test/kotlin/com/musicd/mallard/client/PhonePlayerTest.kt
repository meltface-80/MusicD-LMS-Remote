package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own player away from home, as /api/phone/hello answers, the stream is asked for and the relay commands it. */
class PhonePlayerTest {
    @Test fun `hello, and each track as the file or its Opus`() {
        val h = PhoneHello.parse(JSONObject("""{"player_id":"02:6d:aa:bb:cc:dd","name":"Pixel 8","away":true}"""))
        assertEquals("02:6d:aa:bb:cc:dd", h.playerId); assertTrue(h.away)
        assertEquals("/api/phone/events?player_id=02%3A6d%3Aaa%3Abb%3Acc%3Add", PhoneStream.eventsPath(h.playerId))
        assertEquals("/api/phone/track?player_id=x&seq=7", PhoneStream.trackPath("x", 7))
        assertEquals("/api/phone/track?player_id=x&seq=7&q=opus", PhoneStream.trackPath("x", 7, opus = true))
    }

    @Test fun `a track by id, and its name in the phone's cache`() {
        assertEquals("/api/phone/file?id=42&q=opus", PhoneStream.filePath("42", opus = true))
        assertEquals("/api/phone/file?id=42", PhoneStream.filePath("42", opus = false))
        assertEquals("t42:opus", PhoneStream.cacheKey("/api/phone/file?id=42&q=opus"))
        assertEquals("t42:orig", PhoneStream.cacheKey("/api/phone/file?id=42"))
        assertNull(PhoneStream.cacheKey("/api/phone/track?player_id=x&seq=7"))   // the player's one-off bytes: not kept
        assertEquals("42", RelayCommand.parse("""{"cmd":"add","seq":1,"track_id":"42"}""")!!.trackId)
        assertNull(RelayCommand.parse("""{"cmd":"add","seq":1,"track_id":null}""")!!.trackId)
    }

    @Test fun `two choices, and Opus only away (at home always the file)`() {
        assertEquals(listOf("opus", "exact"), PhoneStream.QUALITIES.map { it.first })
        assertEquals("opus", PhoneStream.quality(null)); assertEquals("opus", PhoneStream.quality("flac")); assertEquals("exact", PhoneStream.quality("exact"))
        assertTrue(PhoneStream.opus(away = true, q = "opus"))
        assertFalse(PhoneStream.opus(away = false, q = "opus"))
        assertFalse(PhoneStream.opus(away = true, q = "exact"))
    }

    @Test fun `the relay's commands, and nothing else`() {
        val add = RelayCommand.parse("""{"cmd":"add","seq":3,"mime":"audio/flac","fresh":true,"play":true,"gain_db":-6.02}""")!!
        assertEquals(3, add.seq); assertEquals("audio/flac", add.mime); assertTrue(add.fresh); assertTrue(add.play)
        assertEquals(-6.02, add.gainDb!!, 1e-9)
        assertNull(RelayCommand.parse("""{"cmd":"add","seq":5,"gain_db":null}""")!!.gainDb)
        assertFalse(RelayCommand.parse("""{"cmd":"add","seq":4,"fresh":false}""")!!.fresh)
        assertEquals("drop", RelayCommand.parse("""{"cmd":"drop","seq":2}""")!!.cmd)
        for (c in listOf("stop", "pause", "resume")) assertEquals(c, RelayCommand.parse("""{"cmd":"$c"}""")!!.cmd)
        assertNull(RelayCommand.parse("""{"cmd":"add"}"""))          // a track needs its number
        assertNull(RelayCommand.parse("""{"cmd":"format"}"""))       // not a command the phone knows
        assertNull(RelayCommand.parse("not json"))
        assertNull(RelayCommand.parse("""{"cmd":"volume","db":-6.02}"""))   // LMS's volume isn't the phone's
    }
}
