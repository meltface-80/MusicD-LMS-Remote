package com.musicd.mallard.client

/**
 * Covers kept on the phone's disk (bug list item 158): every start away from home fetched every cover again over
 * Tailscale, though a cover's key changes whenever its picture does, so one kept is never stale. Kept by key and size,
 * whichever address the server was reached on; the least recently used let go past a limit.
 */
object CoverDisk {
    private val unsafe = Regex("[^A-Za-z0-9._-]")

    /** The file cover [key] at [size] pixels is kept in. */
    fun name(key: String, size: Int): String = key.replace(unsafe, "_").take(120) + "@" + size + ".img"

    /** A file kept: its [name], its [bytes], and when it was [used] last (ms). */
    data class Kept(val name: String, val bytes: Long, val used: Long)

    /** Which of [files] to let go so the rest come within [limit] bytes: the least recently used first. */
    fun trim(files: List<Kept>, limit: Long): List<String> {
        var total = files.sumOf { it.bytes }
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.used }) {
            if (total <= limit) break
            out += f.name; total -= f.bytes
        }
        return out
    }
}
