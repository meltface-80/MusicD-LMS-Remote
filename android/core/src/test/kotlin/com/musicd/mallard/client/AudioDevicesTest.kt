package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Audio Devices: every player as a card (Mandarin's), from /api/zones. */
class AudioDevicesTest {
    private val j = JSONObject("""{"zones":[
        {"zone_id":"00:22:6c:11:22:33","display_name":"WiiM Pro Plus","state":"playing","model":"WiiM Player","power":true,"connected":true},
        {"zone_id":"aa:bb:cc:dd:ee:ff","display_name":"Kitchen","state":"stopped","model":"Squeezebox Radio","power":false,"connected":true},
        {"zone_id":"11:22:33:44:55:66","display_name":"Garage","state":"paused","model":"squeezelite","power":true,"connected":false},
        {"zone_id":"02:6d:f4:f1:26:46","display_name":"This phone","state":"stopped"}]}""")

    @Test fun `each player, its model and what it is doing`() {
        val rows = AudioDevices.parse(j, "02:6d:f4:f1:26:46")
        assertEquals(listOf("WiiM Pro Plus", "Kitchen", "Garage", "This phone"), rows.map { it.name })
        assertEquals(listOf("Playing", "Off", "Offline", "Idle"), rows.map { it.stateShown })
        assertTrue(rows[0].playing); assertFalse(rows[1].playing)
        assertEquals("WiiM Player", rows[0].sub)
    }

    @Test fun `this phone is a phone, and says so`() {
        val phone = AudioDevices.parse(j, "02:6d:f4:f1:26:46").last()
        assertTrue(phone.thisPhone); assertTrue(phone.phone); assertEquals("phone", phone.kind)
        assertEquals("this phone", phone.sub)
        assertEquals("Idle", phone.stateShown)   // not yet running: idle, never "Offline"
        assertEquals("upnp", AudioDevices.parse(j, null)[0].kind)
    }
}
