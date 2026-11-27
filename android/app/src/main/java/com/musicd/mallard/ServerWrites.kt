package com.musicd.mallard

import com.musicd.mallard.client.ServerLane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/*
 * The app's changes to what Home shows (bug list item 85): an album put on Listen later or taken off it, a favourite,
 * the rows' order (item 83). Each is made on one lane, in the order asked, to the end whatever becomes of the screen that
 * asked; Home waits for those under way before it reads its rows, so it shows them as soon as it is back.
 */
internal object ServerWrites {
    val lane = ServerLane()

    /** [call] made on the lane. Ask on the main thread, when the change is asked for, so it is made in the order asked. */
    fun <T> write(call: () -> T): Future<T> = lane.submit(call)

    /** The longest a call on the lane may take (its timeout, ms): one that may take longer is made [aside] (item 390). */
    const val LANE_MOST = 20_000

    /** [call] made beside the lane, at once: a long call (an import, a rescan, a restore) never holds the changes after it. */
    fun <T> aside(call: () -> T): Future<T> = lane.aside(call)

    /** [made]'s answer, or what went wrong, waited for off the main thread (the change goes on if the waiting is called off). */
    suspend fun <T> await(made: Future<T>): Result<T> =
        withContext(Dispatchers.IO) { runCatching { try { made.get() } catch (e: ExecutionException) { throw e.cause ?: e } } }

    /** Once every change asked for before is made. */
    suspend fun drained() { await(lane.drained()) }
}
