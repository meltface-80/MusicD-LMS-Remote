package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The app's changes to what Home shows, made in order, Home's read waiting for them (item 85). */
class ServerLaneTest {
    @Test fun `Home waits for an album put on Listen later before it reads its rows`() {
        val lane = ServerLane()
        val later = mutableListOf<Long>()
        val entered = CountDownLatch(1)
        val let = CountDownLatch(1)
        lane.submit { entered.countDown(); let.await(5, TimeUnit.SECONDS); synchronized(later) { later += 7L } }
        assertEquals(true, entered.await(5, TimeUnit.SECONDS))
        val drained = lane.drained()
        Thread.sleep(100)
        assertFalse("still under way", drained.isDone)
        let.countDown()
        drained.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(7L), synchronized(later) { later.toList() })
    }

    @Test fun `changes made in the order asked, the layout's with them`() {
        val lane = ServerLane()
        val made = java.util.Collections.synchronizedList(mutableListOf<String>())
        lane.submit { Thread.sleep(50); made += "later on" }
        HomeLayoutSync(lane).save(listOf(HomeRow("later", true, null))) { made += "layout"; it }
        lane.submit { made += "later off" }
        lane.drained().get(5, TimeUnit.SECONDS)
        assertEquals(listOf("later on", "layout", "later off"), made.toList())
    }

    @Test fun `a long call made aside holds back no change asked after it (item 390)`() {
        val lane = ServerLane()
        val let = CountDownLatch(1)
        // Qobuz's Update library now, running for minutes.
        val long = lane.aside { let.await(10, TimeUnit.SECONDS); "imported" }
        // A device's switch, asked meanwhile: made at once.
        val switched = lane.submit { "switched" }
        assertEquals("switched", switched.get(2, TimeUnit.SECONDS))
        assertFalse("the import still under way", long.isDone)
        let.countDown()
        assertEquals("imported", long.get(5, TimeUnit.SECONDS))
    }
}
