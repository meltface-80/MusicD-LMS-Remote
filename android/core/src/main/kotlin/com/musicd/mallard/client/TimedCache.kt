package com.musicd.mallard.client

/**
 * A small cache of what was last fetched, for a page opened again (bug list item 140: an artist's page drawn at once
 * as it was, its picture with it). Kept [ttlMs] from when it was put, then fetched afresh; the [capacity] most recently
 * used, the oldest let go first. [now] is the clock, for the tests.
 */
class TimedCache<K, V>(private val capacity: Int, private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private class Kept<V>(val value: V, val at: Long)
    private val map = LinkedHashMap<K, Kept<V>>(16, 0.75f, true)

    /** What was kept for [key], fresh or not; null when nothing is. */
    @Synchronized fun get(key: K): V? = map[key]?.value

    /** Whether what is kept for [key] is still within its time (false when nothing is). */
    @Synchronized fun fresh(key: K): Boolean = map[key]?.let { now() - it.at < ttlMs } ?: false

    /** Keeps [value] for [key] from now, letting the least recently used go past [capacity]. */
    @Synchronized fun put(key: K, value: V) {
        map[key] = Kept(value, now())
        while (map.size > capacity) map.remove(map.keys.first())
    }

    /** Changes what is kept for [key] by [change], its time as it was; puts it as new when nothing is. */
    @Synchronized fun update(key: K, empty: V, change: (V) -> V) {
        val k = map[key]
        if (k == null) put(key, change(empty)) else map[key] = Kept(change(k.value), k.at)
    }

    /** Everything kept past its time, but kept (item 357: the library changed): drawn at once as it was, and asked afresh. */
    @Synchronized fun stale() { val at = now() - ttlMs; map.replaceAll { _, k -> Kept(k.value, at) } }

    /** Lets everything go (item 157: what the phone answered offline isn't kept for the server's). */
    @Synchronized fun clear() = map.clear()
}
