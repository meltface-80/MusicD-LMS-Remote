package com.musicd.mallard.client

/** How long to wait before asking again after [missed] answers missed in a row: 2 s, doubled each time, up to half a minute (item 119). */
object Retry {
    fun ms(missed: Int): Long = minOf(30_000L, 2_000L shl (missed - 1).coerceIn(0, 4))

    /**
     * The server called unreachable after [looks] missed over [elapsedMs] (item 158): three looks and ten seconds both,
     * not three looks alone — refused at once while the app's Tailscale came up, three took three seconds, not ten.
     */
    fun unreachable(looks: Int, elapsedMs: Long): Boolean = looks >= 3 && elapsedMs >= 10_000L

    /** Offline and off screen, the server looked for after [missed] looks: 10 s, doubled to five minutes (item 158: every 10 s all night). */
    fun offScreen(missed: Int): Long = minOf(300_000L, 10_000L shl (missed - 1).coerceIn(0, 5))
}
