package com.musicd.mallard.client

/**
 * A look repeated now and then, one loop of it however often it is started again (bug list item 193): the app's live
 * look at the server (ServerReach) every quarter of a minute while on screen.
 *
 * Started again (on screen again, a network lost) while a look was out, the old way only took back the next look not
 * yet posted, and the look out posted its own next one when it came back: two loops from then on, and another with each
 * start, the server asked more and more often. Each start here is a turn of its own, and a look's next is posted only
 * while its turn is the current one ([start], [stop]).
 *
 * [post] runs a runnable after a delay and [unpost] takes one back (a Handler's postDelayed and removeCallbacks), both
 * on the one thread the loop is used from. [look] makes one look and calls its argument with the delay to the next, or
 * not at all to end the loop.
 */
class OneLoop(
    private val post: (Runnable, Long) -> Unit,
    private val unpost: (Runnable) -> Unit,
    private val look: (next: (Long) -> Unit) -> Unit
) {
    private var turn = 0
    private var pending: Runnable? = null

    /** The loop (re)started, its next look in [ms]: a look out now has no next. */
    fun start(ms: Long) {
        stop()
        schedule(turn, ms)
    }

    /** No more looks: the one posted taken back, a look out has no next. */
    fun stop() {
        turn++
        pending?.let(unpost)
        pending = null
    }

    private fun schedule(t: Int, ms: Long) {
        if (t != turn) return
        pending?.let(unpost)
        val r = Runnable {
            if (t == turn) {
                pending = null
                look { next -> schedule(t, next) }
            }
        }
        pending = r
        post(r, ms)
    }
}
