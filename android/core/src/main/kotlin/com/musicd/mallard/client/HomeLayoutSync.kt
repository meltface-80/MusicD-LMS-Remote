package com.musicd.mallard.client

import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home's layout read from the server and saved to it one call at a time, in the order they were asked for (bug list
 * item 83). Settings → Home Screen saves an order and Home, back on screen, reads the layout at once: made side by side,
 * the read could be answered before the save was made, and Home kept the old order until the app was started again.
 * Ask from the main thread, so the order asked is the order made; wait for the answer off it. Made on [lane], with the
 * app's other changes to what Home shows (item 85).
 */
class HomeLayoutSync(private val lane: ServerLane = ServerLane()) {
    private val asked = AtomicInteger()

    /** The layout as the server keeps it ([get]), after every save asked for before. */
    fun load(get: () -> List<HomeRow>): Future<List<HomeRow>> = lane.submit(get)

    /**
     * [rows] saved ([post] with the body to save), after every call asked for before: the server's answer, or none when a
     * later save has been asked for since (its answer is the one to show) or the answer holds no rows. Throws what the
     * server said went wrong.
     */
    fun save(rows: List<HomeRow>, post: (org.json.JSONObject) -> org.json.JSONObject): Future<List<HomeRow>?> {
        val ticket = asked.incrementAndGet()
        return lane.submit {
            val j = post(HomeRowsSettings.body(rows))
            j.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
            if (ticket == asked.get() && j.optJSONArray("rows") != null) HomeRows.parse(j) else null
        }
    }
}
