package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** One live look at the server however often it is started again (bug list item 193). */
class OneLoopTest {
    /** A Handler's posted runnables, run by hand. */
    private val posted = ArrayList<Runnable>()
    /** Looks out (asked, not yet answered): each one's call for its next. */
    private val out = ArrayList<(Long) -> Unit>()
    private var looks = 0

    private val loop = OneLoop({ r, _ -> posted += r }, { r -> posted.removeAll { it === r } }) { next -> looks++; out += next }

    private fun runPosted() { val now = posted.toList(); posted.clear(); now.forEach { it.run() } }
    private fun answer() { val now = out.toList(); out.clear(); now.forEach { it(15_000) } }

    @Test fun started_again_while_a_look_is_out_stays_one_loop() {
        loop.start(15_000)
        runPosted()                // the look goes out
        assertEquals(1, looks)
        loop.start(15_000)         // on screen again before it is answered
        answer()                   // the look out comes back: no next of its own
        assertEquals(1, posted.size)
        for (i in 1..5) { runPosted(); answer() }
        assertEquals("one look each time round, not two", 6, looks)
        assertEquals(1, posted.size)
    }

    @Test fun started_again_and_again_still_one() {
        loop.start(15_000)
        repeat(4) { loop.start(2_000) }
        assertEquals(1, posted.size)
        runPosted(); answer()
        assertEquals(1, looks)
        assertEquals(1, posted.size)
    }

    @Test fun stopped_a_look_out_has_no_next() {
        loop.start(15_000)
        runPosted()
        loop.stop()
        answer()
        assertEquals(0, posted.size)
    }
}
