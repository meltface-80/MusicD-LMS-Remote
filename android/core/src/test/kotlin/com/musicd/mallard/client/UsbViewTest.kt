package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** This phone's USB DAC as Settings → Away from home says it. */
class UsbViewTest {
    @Test fun a_DAC_allowed_and_streaming() {
        val u = UsbView(JSONObject("""{"attached":true,"permission":true,"direct":true,"fixed":false,"limit":60,"volume":30,
            "device":{"name":"DragonFly Cobalt","manufacturer":"AudioQuest"},
            "info":{"uac":2,"speed":"high","sync":"async","rates":[44100,96000],"bits":[24],"volume_control":true,"current_rate":96000},
            "stream":{"rate":96000,"bits":24,"level":30,"software_volume":false,"volume_db":{"min":-80,"max":0},"stats":{"running":true,"feedback":6.0004,"underruns":1,"errors":0}}}"""))
        assertEquals("AudioQuest DragonFly Cobalt", u.name)
        assertEquals("USB Audio Class 2 · high speed · async", u.classLine)
        assertEquals("44.1 · 96 kHz", u.ratesLine)
        assertEquals("24-bit", u.bitsLine)
        assertEquals("96 kHz", u.clock)
        assertEquals(60, u.limit)
        assertEquals(30, u.volume)
        assertEquals("96 kHz · 24-bit · volume 30% (DAC range -80.0 to 0.0 dB) · feedback 6.000 frames/packet · 1 underrun", u.streaming)
        assertTrue(u.fixedNote.startsWith("Off. The DAC volume below and the phone’s volume buttons drive the DAC’s own control"))
    }

    @Test fun more_than_one_USB_audio_device_the_choices_and_the_one_in_use() {
        val two = UsbView(JSONObject("""{"attached":true,"permission":true,"device":{"name":"SU-1","manufacturer":"SMSL","key":"1234:5678"},
            "devices":[{"key":"10615:1","name":"FiiO R7","manufacturer":"FiiO","own":true,"chosen":false},{"key":"1234:5678","name":"SU-1","manufacturer":"SMSL","own":false,"chosen":true}]}"""))
        assertEquals(listOf("10615:1", "1234:5678"), two.devices.map { it.value })
        assertEquals(listOf("FiiO R7 (this device’s own)", "SMSL SU-1"), two.devices.map { it.words })
        assertEquals("1234:5678", two.chosenDevice)
        val one = UsbView(JSONObject("""{"attached":false,"permission":false}"""))
        assertEquals(emptyList<Choice>(), one.devices)
        assertEquals("", one.chosenDevice)
    }

    @Test fun nothing_on_the_port_and_one_not_yet_allowed() {
        val none = UsbView(JSONObject("""{"attached":false,"permission":false,"direct":false,"error":null}"""))
        assertFalse(none.attached)
        assertNull(none.error)
        assertNull(none.streaming)
        assertEquals("not stated", none.ratesLine)
        val ask = UsbView(JSONObject("""{"attached":true,"permission":false,"device":{"name":"USB Audio","manufacturer":null},"error":"Permission refused — tap Allow to ask again"}"""))
        assertEquals("USB Audio", ask.name)
        assertEquals("Permission refused — tap Allow to ask again", ask.error)
        assertEquals("Off. The phone plays through Android’s mixer, at the mixer’s rate.", ask.directNote)
        assertTrue(UsbView(JSONObject("""{"fixed":false,"info":{"volume_control":false}}""")).fixedNote.contains("no longer bit-perfect"))
    }
}
