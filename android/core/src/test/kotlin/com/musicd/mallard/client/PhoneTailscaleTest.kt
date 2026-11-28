package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PhoneTailscaleTest {
    @Test fun `the forward target is the server's tailnet address without its scheme`() {
        assertEquals("100.64.1.2:3390", PhoneTailscale.target("http://100.64.1.2:3390"))
        assertEquals("100.64.1.2:3390", PhoneTailscale.target(" http://100.64.1.2:3390/ "))
        assertNull(PhoneTailscale.target(null))
        assertNull(PhoneTailscale.target("http://musicd-lms.tailnet.ts.net:3390"))
        assertNull(PhoneTailscale.target("http://100.64.1.2"))
    }

    @Test fun `the server says its tailnet address in its status`() {
        val h = Health.parse(JSONObject("""{"app":"musicd-lms-remote","paired":true,"tailscale":"http://100.64.1.2:3390"}"""))
        assertEquals("http://100.64.1.2:3390", h.tailscale)
        assertNull(Health.parse(JSONObject("""{"paired":true,"tailscale":null}""")).tailscale)
        assertNull(Health.parse(JSONObject("""{"paired":true}""")).tailscale)
    }

    @Test fun `the phone's engine in words`() {
        val st = PhoneEngineState.parse(JSONObject("""{"state":"Running","ips":["fd7a:115c::1","100.70.0.9"],"dns_name":"mallard-phone.tail.ts.net."}"""))
        assertTrue(st.signedIn)
        assertEquals("Signed in", st.words)
        assertEquals("100.70.0.9", st.address)
        assertEquals("mallard-phone.tail.ts.net", st.dnsName)
        val login = PhoneEngineState.parse(JSONObject("""{"state":"NeedsLogin","auth_url":"https://login.tailscale.com/a/x"}"""))
        assertEquals("Not signed in yet", login.words)
        assertEquals("https://login.tailscale.com/a/x", login.authUrl)
    }

    @Test fun `away goes to the engine's own port, not Mandarin's`() {
        assertEquals("http://127.0.0.1:34390", PhoneTailscale.AWAY.baseUrl)
    }
}
