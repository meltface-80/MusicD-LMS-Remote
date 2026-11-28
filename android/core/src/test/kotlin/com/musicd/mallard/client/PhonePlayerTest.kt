package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own player away from home, as /api/phone/hello answers, the stream is asked for and the relay commands it. */
class PhonePlayerTest {
    @Test fun `hello and the stream's address`() {
        val h = PhoneHello.parse(JSONObject("""{"player_id":"02:6d:aa:bb:cc:dd","name":"Pixel 8","away":true}"""))
        assertEquals("02:6d:aa:bb:cc:dd", h.playerId); assertTrue(h.away); assertEquals("pcm", h.mode)
        assertEquals("/api/phone/stream?player_id=02%3A6d%3Aaa%3Abb%3Acc%3Add&q=opus", PhoneStream.path(h.playerId, "opus"))
        assertEquals("opus", PhoneStream.quality("flac"))   // no longer offered (not lossless at 48 kHz): Opus
        assertEquals(listOf("opus", "exact"), PhoneStream.QUALITIES.map { it.first })
        assertEquals("opus", PhoneStream.quality("anything else"))
    }

    @Test fun `untouched is the relay, its events and its tracks`() {
        assertEquals("exact", PhoneStream.quality("exact"))
        assertEquals("exact", PhoneStream.mode("exact"))
        assertEquals("pcm", PhoneStream.mode("flac")); assertEquals("pcm", PhoneStream.mode(null))
        assertEquals("/api/phone/events?player_id=02%3A6d%3Aaa%3Abb%3Acc%3Add", PhoneStream.eventsPath("02:6d:aa:bb:cc:dd"))
        assertEquals("/api/phone/track?player_id=x&seq=7", PhoneStream.trackPath("x", 7))
        assertEquals("exact", PhoneHello.parse(JSONObject("""{"player_id":"x","mode":"exact"}""")).mode)
    }

    @Test fun `the relay's commands, and nothing else`() {
        val add = RelayCommand.parse("""{"cmd":"add","seq":3,"mime":"audio/flac","fresh":true,"play":true}""")!!
        assertEquals(3, add.seq); assertEquals("audio/flac", add.mime); assertTrue(add.fresh); assertTrue(add.play)
        assertFalse(RelayCommand.parse("""{"cmd":"add","seq":4,"fresh":false}""")!!.fresh)
        assertEquals("drop", RelayCommand.parse("""{"cmd":"drop","seq":2}""")!!.cmd)
        for (c in listOf("stop", "pause", "resume")) assertEquals(c, RelayCommand.parse("""{"cmd":"$c"}""")!!.cmd)
        assertNull(RelayCommand.parse("""{"cmd":"add"}"""))          // a track needs its number
        assertNull(RelayCommand.parse("""{"cmd":"format"}"""))       // not a command the phone knows
        assertNull(RelayCommand.parse("not json"))
        assertNull(RelayCommand.parse("""{"cmd":"volume","db":-6.02}"""))   // LMS's volume isn't the phone's
    }
}
