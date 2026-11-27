package com.musicd.mallard.client

/**
 * The log's watch on the app's screens (bug list items 116 and 121, while logging is on): what a smooth app
 * never does, found in what the screens show and when. It is told each change of what is shown ([shown]: the screen on
 * top, the side menu or a sheet over it), the back stack after each move ([stack]) and each frame's length ([frame]),
 * and answers with what was wrong:
 *
 * - FLASH: something shown for a moment between two others (the side menu closed, Home seen, then Settings);
 * - BOUNCE: something gone to and straight back (a screen opened and closed again by itself);
 * - STACK: a back stack with nothing, without Home at its foot, or the same screen twice in a row (item 115's kind),
 *   the screens named as the log names them (an album with its number, Settings with its page);
 * - JANK: a frame that took long enough to be seen as a stall.
 *
 * Frames that are slow but not stalls are counted by what was shown ([slowFrames]), for a summary now and then.
 */
class UiTrace(private val flashMs: Long = 300, private val bounceMs: Long = 600, private val jankMs: Long = 100, private val slowMs: Long = 34) {
    /** One thing found wrong, at [atMs]. */
    data class Finding(val atMs: Long, val text: String)

    private var shown: String? = null
    private var since = 0L
    private var before: String? = null
    private val slow = LinkedHashMap<String, Int>()

    /** What is shown now: [state] from [atMs]. What was wrong with how it came to be shown. */
    fun shown(state: String, atMs: Long): List<Finding> {
        if (state == shown) return emptyList()
        val out = ArrayList<Finding>()
        val prev = shown
        val prevFor = atMs - since
        val was = before
        if (prev != null && was != null && prevFor < flashMs) {
            if (was == state) out += Finding(atMs, "BOUNCE: $prev for $prevFor ms, then back to $state")
            else out += Finding(atMs, "FLASH: $prev shown for $prevFor ms between $was and $state")
        }
        before = prev
        shown = state
        since = atMs
        return out
    }

    /** The back stack's screens after a move, Home first, as the log names them. What is wrong with it. */
    fun stack(routes: List<String>, atMs: Long): List<Finding> {
        if (routes.isEmpty()) return listOf(Finding(atMs, "STACK: no screen at all"))
        val out = ArrayList<Finding>()
        if (routes.first() != HOME) out += Finding(atMs, "STACK: Home not at the foot: " + routes.joinToString(" > "))
        for (i in 1 until routes.size) if (routes[i] == routes[i - 1]) out += Finding(atMs, "STACK: ${routes[i]} twice in a row: " + routes.joinToString(" > "))
        return out
    }

    /** A frame of [ms] drawn at [atMs]: a stall is a finding; a slow frame is counted against what was shown. */
    fun frame(ms: Long, atMs: Long): Finding? {
        val on = shown ?: "start"
        if (ms >= jankMs) return Finding(atMs, "JANK: a frame took $ms ms on $on")
        if (ms >= slowMs) slow[on] = (slow[on] ?: 0) + 1
        return null
    }

    /** The slow frames counted since the last call, by what was shown; the count starts again. */
    fun slowFrames(): Map<String, Int> {
        val out = LinkedHashMap(slow)
        slow.clear()
        return out
    }

    /** The app's screen made again: what was shown before it went is forgotten (its first moments are no flash). */
    fun reset() { shown = null; before = null; since = 0 }

    companion object {
        /** Home, as the log names it. */
        const val HOME = "home"

        /** An answer slow enough to mark: 2 s or more, unless the server holds it on purpose (a wait for a change). */
        fun slowAnswer(what: String, ms: Long): Boolean = ms >= 2_000 && "wait_for=" !in what && "wait=" !in what

        /** A request that ended long after its own timeout ([timeoutMs]): the phone slept through it, not the server slow. */
        fun slept(ms: Long, timeoutMs: Int): Boolean = ms > timeoutMs + 30_000

        /** Android's call for memory back a fault: running low, or critically, with the app on screen (10, 15); hidden or in the background, its usual notes. */
        fun memoryLow(level: Int): Boolean = level == 10 || level == 15
    }
}
