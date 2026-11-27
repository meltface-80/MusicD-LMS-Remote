package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun `a bare address takes the default port`() {
        assertEquals(ServerAddress("192.168.1.10", 3390), ServerAddress.parse("192.168.1.10", ""))
    }

    @Test fun `the port box is used`() {
        assertEquals(ServerAddress("nas.local", 4000), ServerAddress.parse("nas.local", "4000"))
    }

    @Test fun `a pasted url is trimmed to host and port`() {
        assertEquals(ServerAddress("10.0.0.5", 3500), ServerAddress.parse(" http://10.0.0.5:3500/display ", ""))
    }

    @Test fun `a port in both boxes must agree`() {
        assertEquals(ServerAddress("10.0.0.5", 3600), ServerAddress.parse("10.0.0.5:3600", "3600"))
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("10.0.0.5:3600", "3500") }
    }

    @Test fun `bad ports and addresses are refused`() {
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("10.0.0.5", "70000") }
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("10.0.0.5", "abc") }
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("my server", "") }
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("  ", "") }
    }

    @Test fun `ipv6 keeps its brackets in urls`() {
        val a = ServerAddress.parse("[fe80::1]:3500", "")
        assertEquals("fe80::1", a.host)
        assertEquals("http://[fe80::1]:3500", a.baseUrl)
    }
}
