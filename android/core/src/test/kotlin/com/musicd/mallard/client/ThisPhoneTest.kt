package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** This phone's own player: its volume is the phone's, never LMS's (fixed at 100%); every other room's is LMS's. */
class ThisPhoneTest {
    private val sent = ArrayList<String>()
    private val client = ServerClient(ServerAddress("192.168.1.2", 3390), transport = object : Transport {
        override fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject {
            sent += "$method $path ${body ?: ""}".trim()
            return JSONObject("""{"revision":1,"zone":{"zone_id":"02:6d:aa:bb:cc:dd","display_name":"This phone","state":"playing",
                "outputs":[{"output_id":"02:6d:aa:bb:cc:dd","display_name":"This phone","is_muted":false,"volume":{"value":100,"min":0,"max":100,"step":1}}]}}""")
        }
        override fun bytes(path: String, timeout: Int) = ByteArray(0)
    })
    private class Phone : LocalVolume {
        var level: Int? = 40; var muted = false; val did = ArrayList<String>()
        override fun owns(zoneId: String) = zoneId == "02:6d:aa:bb:cc:dd"
        override fun level() = level
        override fun muted() = muted
        override fun set(value: Int) { did += "set $value" }
        override fun step(direction: Int) { did += "step $direction" }
        override fun mute(on: Boolean) { did += "mute $on" }
    }
    private val phone = Phone()

    @After fun clear() { ThisPhone.volume = null }

    @Test fun `this phone's volume is the phone's, and never reaches LMS`() {
        ThisPhone.volume = phone
        client.zoneVolume("02:6d:aa:bb:cc:dd", 63.4)
        client.zoneVolumeStep("02:6d:aa:bb:cc:dd", -1)
        client.volumeRelative("02:6d:aa:bb:cc:dd", 2)
        client.mute("02:6d:aa:bb:cc:dd", true)
        assertEquals(listOf("set 63", "step -1", "step 1", "mute true"), phone.did)
        assertTrue("nothing sent to LMS", sent.isEmpty())
    }

    @Test fun `another room's volume is LMS's as before`() {
        ThisPhone.volume = phone
        client.zoneVolume("00:04:20:12:34:56", 30.0)
        client.zoneVolumeStep("00:04:20:12:34:56", 1)
        assertEquals(2, sent.size); assertTrue(sent.all { it.startsWith("POST /api/volume") })
        assertTrue(phone.did.isEmpty())
    }

    @Test fun `this phone's player id is the server's macFor`() {
        assertEquals("02:6d:f4:f1:26:46", ThisPhone.playerId("abc123"))
        assertEquals("02:6d:e5:ce:73:a6", ThisPhone.playerId("pixel-0001"))
    }

    @Test fun `the screens show the phone's level, not LMS's fixed 100`() {
        ThisPhone.volume = phone
        val out = client.zoneState("02:6d:aa:bb:cc:dd").zone!!.outputs[0]
        assertEquals(40, out.volume); assertEquals(40.0, out.scale!!.value!!, 0.0)
        phone.level = null   // a USB DAC at fixed volume: the screens say it is fixed
        assertNull(client.zoneState("02:6d:aa:bb:cc:dd").zone!!.outputs[0].scale)
        ThisPhone.volume = null
        assertEquals(100, client.zoneState("02:6d:aa:bb:cc:dd").zone!!.outputs[0].volume)
    }
}
