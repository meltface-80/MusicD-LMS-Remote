package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's lines go to Mandarin's one log and are kept nowhere else (item 425, LogSend): each dated in UTC with its
 * level and redacted before it waits, in memory alone, for the server; sent in order and let go once the server has
 * them; kept while it can't be reached, the oldest let go past the most that may wait.
 */
class LogSendTest {
    private val at = 1_763_199_794_002L   // 2025-11-15 09:43:14.002 UTC

    @Test fun `a line dated in UTC with its level, redacted before it waits`() {
        val s = LogSend(clock = { at })
        s.line("NET", "server GET /api/zones → HTTP 500 for owner@example.com, token=abcdef0123456789", "WARN")
        s.said("2025-11-15 09:43:15.120  4242  4250 W PhonePlayer: Bearer abc.def from 81.2.69.160")
        var sent = ""
        assertEquals(2, s.send { sent = it })
        assertEquals("2025-11-15 09:43:14.002 UTC  WARN   [NET] server GET /api/zones → HTTP 500 for [email], token=[…]\n" +
            "2025-11-15 09:43:15.120  4242  4250 W PhonePlayer: Bearer […] from [wan]\n", sent)
        assertFalse(sent.contains("owner@example.com"))
    }

    @Test fun `a crash's trace each line redacted, the first dated`() {
        val s = LogSend(clock = { at })
        s.line("APP", "CRASH on main: java.io.IOException: at /home/jane/x\n\tat com.musicd.A.b(A.kt:1)", "ERROR")
        var sent = ""
        s.send { sent = it }
        assertEquals("2025-11-15 09:43:14.002 UTC  ERROR  [APP] CRASH on main: java.io.IOException: at /home/[user]/x\n\tat com.musicd.A.b(A.kt:1)\n", sent)
    }

    @Test fun `sent in order and let go once the server has them, kept while it can't be reached`() {
        val s = LogSend(clock = { at })
        for (i in 1..3) s.line("APP", "line $i")
        assertTrue(runCatching { s.send { throw java.io.IOException("the server can't be reached") } }.isFailure)
        assertEquals("kept for the next send", 3, s.count)
        s.line("APP", "line 4")
        val got = mutableListOf<String>()
        assertEquals(4, s.send { got += it })
        assertEquals(listOf("line 1", "line 2", "line 3", "line 4"), got.single().trim().lines().map { it.substringAfter("[APP] ") })
        assertEquals("let go once sent", 0, s.count)
        assertEquals("nothing more to send", 0, s.send { got += it })
    }

    @Test fun `no more than the most may wait, the oldest let go, and a send no bigger than it may be`() {
        val s = LogSend(most = 5, sendChars = 120, clock = { at })
        for (i in 1..8) s.line("APP", "line $i")
        assertEquals(5, s.count)
        val got = mutableListOf<String>()
        while (s.count > 0) s.send { got += it }
        assertTrue(got.size > 1)
        assertTrue(got.all { it.length <= 120 })
        assertEquals((4..8).map { "line $it" }, got.joinToString("").trim().lines().map { it.substringAfter("[APP] ") })
    }

    @Test fun `lines said while a send is on their way stay for the next`() {
        val s = LogSend(most = 3, clock = { at })
        for (i in 1..3) s.line("APP", "line $i")
        // While the first three go, three more come and push them out of the queue: none of the new let go with them.
        s.send { for (i in 4..6) s.line("APP", "line $i") }
        val got = mutableListOf<String>()
        s.send { got += it }
        assertEquals((4..6).map { "line $it" }, got.single().trim().lines().map { it.substringAfter("[APP] ") })
    }
}
