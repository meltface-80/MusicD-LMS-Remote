package com.musicd.mallard.client

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class FinderTest {
    @Test fun `candidates are the rest of the slash-24, nearest first`() {
        val c = Finder.candidates("192.168.1.20")
        assertEquals(253, c.size)
        assertFalse("192.168.1.20" in c)
        assertEquals("192.168.1.19", c[0])
        assertEquals("192.168.1.21", c[1])
        assertTrue(c.all { it.startsWith("192.168.1.") })
    }

    @Test fun `find returns what answered, in candidate order`() {
        val hosts = Finder.candidates("10.0.0.9")
        val found = Finder.find(hosts) { it == "10.0.0.200" || it == "10.0.0.3" }
        assertEquals(listOf("10.0.0.3", "10.0.0.200").sortedBy { hosts.indexOf(it) }, found)
    }

    @Test fun `the client talks to a real HTTP server`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/status") { ex ->
            val body = """{"paired":true,"core_id":"u","core_name":"Lyrion Music Server 9.0","zone_count":2}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/api/control") { ex ->
            val body = """{"error":"That room isn't available"}""".toByteArray()
            ex.requestBody.readBytes()
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = ServerClient(ServerAddress("127.0.0.1", server.address.port))
            assertEquals(2, client.health().rooms)
            try {
                client.control("nope", "play")
                throw AssertionError("should have thrown")
            } catch (e: ServerClient.ServerException) {
                assertEquals(404, e.status)
                assertEquals("That room isn't available", e.message)
            }
        } finally {
            server.stop(0)
        }
    }
}
