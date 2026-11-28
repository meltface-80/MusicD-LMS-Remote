package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Away, every screen's room is this phone's (Mandarin's AwayRoom): asked of and sent to it, whatever room a screen was opened in. */
class AwayRoomsTest {
    private val own = "02:6d:aa:bb:cc:dd"
    private val wiim = "00:22:6c:11:22:33"

    @Test fun `a room named in the address is the phone's`() {
        assertEquals("/api/zone-state?zone=02%3A6d%3Aaa%3Abb%3Acc%3Add", AwayRooms.path("/api/zone-state?zone=" + java.net.URLEncoder.encode(wiim, "UTF-8"), own))
        assertEquals("/api/apps/browse?token=t&zone=02%3A6d%3Aaa%3Abb%3Acc%3Add&start=0", AwayRooms.path("/api/apps/browse?token=t&zone=x&start=0", own))
        assertEquals("/api/lms/player/02%3A6d%3Aaa%3Abb%3Acc%3Add/mode", AwayRooms.path("/api/lms/player/" + java.net.URLEncoder.encode(wiim, "UTF-8") + "/mode", own))
        assertEquals("/api/library/albums?sort=title", AwayRooms.path("/api/library/albums?sort=title", own))   // names no room
    }

    @Test fun `a room named in the body is the phone's, in a copy`() {
        val sent = JSONObject().put("zone_or_output_id", wiim).put("command", "pause")
        val out = AwayRooms.body("/api/control", sent, own)!!
        assertEquals(own, out.getString("zone_or_output_id")); assertEquals("pause", out.getString("command"))
        assertEquals(wiim, sent.getString("zone_or_output_id"))   // the caller's own as it was
        assertEquals(own, AwayRooms.body("/api/radio", JSONObject().put("zone", wiim).put("enabled", true), own)!!.getString("zone"))
        val none = JSONObject().put("offset", 3)
        assertSame(none, AwayRooms.body("/api/favourites/toggle", none, own))
    }

    @Test fun `what is kept for home is left as it is`() {
        val move = JSONObject().put("from_zone", wiim).put("to_zone", "x")
        assertSame(move, AwayRooms.body("/api/transfer-zone", move, own))
        assertEquals("/api/mute-all?zone=x", AwayRooms.path("/api/mute-all?zone=x", own))
    }
}
