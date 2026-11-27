package com.musicd.mallard.client

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finding the server when all you know is its port.
 *
 * The phone knows its own address; the server is almost always on the same
 * home network, so every address in the phone's /24 is asked for /api/status
 * on that port, a few dozen at a time. On a home network that is about two
 * seconds, and it needs nothing from the server beyond the health route it
 * already has — no multicast, which Android makes awkward and many routers
 * drop between Wi-Fi and wired anyway.
 */
object Finder {

    /** Every other address in the same /24 as [localIp], nearest first. */
    fun candidates(localIp: String): List<String> {
        val parts = localIp.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) { "not an IPv4 address: $localIp" }
        val base = parts.take(3).joinToString(".")
        val me = parts[3].toInt()
        return (1..254).filter { it != me }
            .sortedBy { kotlin.math.abs(it - me) }
            .map { "$base.$it" }
    }

    /**
     * Probe [hosts] with [probe] (true = a MusicD LMS Remote answered there) and
     * return the ones that did, in the order given. [onFound] fires as each is
     * found, so a screen can show the first result without waiting for all.
     */
    fun find(
        hosts: List<String>,
        threads: Int = 48,
        deadlineMs: Long = 8000,
        onFound: (String) -> Unit = {},
        probe: (String) -> Boolean
    ): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val found = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, hosts.size)) { r ->
            Thread(r, "finder").apply { isDaemon = true }
        }
        try {
            for (h in hosts) {
                pool.execute {
                    val ok = try { probe(h) } catch (e: Exception) { false }
                    if (ok) { found.add(h); onFound(h) }
                }
            }
            pool.shutdown()
            pool.awaitTermination(deadlineMs, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        val set = found.toSet()
        return hosts.filter { it in set }
    }
}
