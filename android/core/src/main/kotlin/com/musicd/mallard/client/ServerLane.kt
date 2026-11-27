package com.musicd.mallard.client

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The app's changes to what Home shows (Listen later, a favourite, the rows' order) made one call at a time, in the order
 * they were asked for, each made to the end whatever becomes of the screen that asked (bug list items 83 and 85). Home
 * waits for those under way ([drained]) before it reads its rows: read side by side, the read could be answered before the
 * change was made, and Home showed it only once the app was started again. Ask from the main thread, so the order asked
 * is the order made; wait for the answer off it.
 */
class ServerLane {
    private val one: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "server-lane").apply { isDaemon = true } }

    /** [call] made after every call asked for before. */
    fun <T> submit(call: () -> T): Future<T> = one.submit(Callable { call() })

    /** Done once every call asked for before is made. */
    fun drained(): Future<Unit> = one.submit(Callable { })

    private val beside: ExecutorService = Executors.newCachedThreadPool { Thread(it, "server-aside").apply { isDaemon = true } }

    /**
     * [call] made beside the lane, at once (item 390): a long call (an import that runs for minutes, a rescan, a restore)
     * never holds back the changes asked after it. On the lane, Qobuz's Update library now (up to ten minutes) kept a
     * device's switch from being made until it was done, and the switch seemed not to take.
     */
    fun <T> aside(call: () -> T): Future<T> = beside.submit(Callable { call() })
}
