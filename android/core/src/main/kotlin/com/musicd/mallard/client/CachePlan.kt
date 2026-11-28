package com.musicd.mallard.client

/**
 * This phone playing (Mandarin's CachePlan): which of the upcoming tracks to keep on the phone ahead of time, and the
 * choices for how many.
 *
 * Like Plexamp: a number of tracks for Wi-Fi and a (smaller) one for mobile
 * data, and a limit on the space the cache takes. Tracks already cached
 * stay until the space is needed, so a track played again costs nothing.
 */
object CachePlan {
    /** Tracks cached ahead on Wi-Fi: 5 to 45 in fives, 20 unless chosen. */
    val WIFI_CHOICES = (5..45 step 5).toList()
    const val WIFI_DEFAULT = 20

    /** Tracks cached ahead on mobile data: 5, 10 or 15, 10 unless chosen. */
    val MOBILE_CHOICES = listOf(5, 10, 15)
    const val MOBILE_DEFAULT = 10

    /** The cache's size limit in GB. */
    val SIZE_CHOICES_GB = listOf(2, 4, 6, 8, 10)
    const val SIZE_DEFAULT_GB = 2

    /** [chosen] if it's one of [choices], else [default] (a stored value from another version). */
    fun pick(chosen: Int, choices: List<Int>, default: Int) = if (chosen in choices) chosen else default

    /**
     * The tracks to fetch, nearest first: the next [count] tracks in play
     * order (shuffle and repeat already applied in [upcoming], the playing
     * track not in it), leaving out those already cached and those that aren't
     * the library's (a streaming service's track — null keys). A track that comes
     * up twice (repeat) is fetched once.
     */
    fun toFetch(upcoming: List<String?>, count: Int, cached: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (key in upcoming.take(maxOf(0, count))) {
            if (key == null || !seen.add(key)) continue
            if (!cached(key)) out += key
        }
        return out
    }

    /** The tracks ahead wait (v0.8.17) until the player holds this much of what's playing… */
    const val AHEAD_GO_MS = 30_000L
    /** …and, once on their way, stop again when it's down to this. */
    const val AHEAD_STOP_MS = 10_000L

    /**
     * The playing track first (v0.8.17): whether the tracks ahead wait, so a
     * weak signal goes to what's playing rather than being shared with them.
     * They wait while the player is left waiting for data, and while it is
     * still fetching with less than [AHEAD_GO_MS] held ahead (less than
     * [AHEAD_STOP_MS] once they're on their way, so they don't stop and start
     * again every second). When the player is fetching nothing — all it needs
     * is held, or nothing is playing — they go.
     */
    fun aheadWaits(waitingNow: Boolean, playerWaiting: Boolean, playerFetching: Boolean, heldMs: Long): Boolean = when {
        playerWaiting -> true
        !playerFetching -> false
        waitingNow -> heldMs < AHEAD_GO_MS
        else -> heldMs < AHEAD_STOP_MS
    }

    /**
     * A cached track's name: its LMS track id and its form, so the same track as the file and as Opus are two entries —
     * and whichever is on the phone can be played wherever the phone is.
     */
    fun key(trackId: String, opus: Boolean) = "t$trackId:" + if (opus) "opus" else "orig"

    /** How many tracks ahead now: [wifi] on an unmetered network, [mobile] on mobile data. */
    fun count(metered: Boolean, wifi: Int, mobile: Int) = if (metered) mobile else wifi
}
