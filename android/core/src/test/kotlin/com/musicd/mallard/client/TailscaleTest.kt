package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server's built-in Tailscale as /api/tailscale answers (lib/tailscale.js status()). */
class TailscaleTest {
    @Test fun `connected, with its address and name`() {
        val t = TailscaleState.parse(JSONObject("""{"available":true,"enabled":true,"running":true,"state":"Running","auth_url":null,"ips":["100.80.0.5"],"dns_name":"musicd-lms.tail.ts.net","serving":true,"online":true,"health":[],"address":"http://100.80.0.5:3390","error":null,"version":"1.102.5","hostname":"musicd-lms"}"""))
        assertEquals("Connected", t.words)
        assertEquals("http://100.80.0.5:3390", t.address)
        assertNull(t.warning)
        assertNull(t.authUrl)
    }

    @Test fun `waiting to be signed in, off, and unwell`() {
        assertEquals("Not signed in yet", TailscaleState.parse(JSONObject("""{"available":true,"enabled":true,"running":true,"state":"NeedsLogin","auth_url":"https://login.tailscale.com/a/x"}""")).words)
        assertEquals("Off", TailscaleState.parse(JSONObject("""{"available":true,"enabled":false,"running":false,"state":"Stopped"}""")).words)
        val sick = TailscaleState.parse(JSONObject("""{"available":true,"enabled":true,"running":true,"state":"Running","serving":true,"online":false,"health":[]}"""))
        assertEquals("Running, but not online on the tailnet — the server is rebinding it, then starting it afresh", sick.warning)
        assertEquals("Tailscale warns: no DERP", TailscaleState.parse(JSONObject("""{"state":"Running","online":true,"health":["no DERP"]}""")).warning)
    }
}
