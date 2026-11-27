package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's interface list handed to the engine only when it changed, and at most once in ten seconds (item 202). */
class InterfaceFeedTest {
    private var now = 5_000_000L
    private val feed = InterfaceFeed { now }

    @Test fun `the first list goes, the same list again doesn't`() {
        assertEquals(InterfaceFeed.Action.Send, feed.offer("wlan0 up\n"))
        assertEquals(InterfaceFeed.Action.Skip, feed.offer("wlan0 up\n"))
        now += 60_000
        assertEquals(InterfaceFeed.Action.Skip, feed.offer("wlan0 up\n"))
    }

    @Test fun `a change within the gap waits for the gap, a change after it goes at once`() {
        assertEquals(InterfaceFeed.Action.Send, feed.offer("wlan0 up\n"))
        now += 3_000
        assertEquals(InterfaceFeed.Action.Later(7_000), feed.offer("wlan0 up\nrmnet0 up\n"))
        now += 7_000
        assertEquals(InterfaceFeed.Action.Send, feed.offer("wlan0 up\nrmnet0 up\n"))
        now += InterfaceFeed.MIN_GAP_MS
        assertEquals(InterfaceFeed.Action.Send, feed.offer("wlan0 up\n"))
    }

    @Test fun `a flap that undoes itself within the gap costs nothing`() {
        assertEquals(InterfaceFeed.Action.Send, feed.offer("a\n"))
        now += 1_000
        assertEquals(InterfaceFeed.Action.Later(9_000), feed.offer("b\n"))
        now += 9_000
        assertEquals("back as it was by the time the gap is up", InterfaceFeed.Action.Skip, feed.offer("a\n"))
    }

    @Test fun `a new engine process gets the list whatever came before`() {
        assertEquals(InterfaceFeed.Action.Send, feed.offer("a\n"))
        feed.reset()
        assertEquals(InterfaceFeed.Action.Send, feed.offer("a\n"))
    }
}
