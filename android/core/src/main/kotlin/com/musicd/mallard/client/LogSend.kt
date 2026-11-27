package com.musicd.mallard.client

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's lines on their way to Mandarin's one log (item 425): the app keeps no log of its own, on the phone or
 * anywhere else. Each line is dated in UTC with its level, as the server writes its own ("2026-11-15 09:43:14.002 UTC
 * WARN   [NET] …"), and put through [LogRedact] before it is kept here at all; then it waits, in memory alone, until it
 * is sent to the server (POST /api/diagnostics/app), which puts it in the one log as "[app <the phone's name>]" with the
 * time it was said. At most [most] lines wait (the oldest let go first, while the server can't be reached); a send that
 * fails keeps them for the next, one that works lets go of those it took.
 */
class LogSend(private val most: Int = 5000, private val sendChars: Int = 400_000, private val clock: () -> Long = System::currentTimeMillis) {
    /** Each line waiting, with its number: those a send took let go by number, whatever went meanwhile. */
    private val waiting = ArrayDeque<Pair<Long, String>>()
    private var next = 0L
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** A line of [cat] ("NET", "APP", "UI"…) at [level] (INFO, WARN or ERROR), without what is the owner's alone. */
    fun line(cat: String, msg: String, level: String = "INFO") {
        val at = synchronized(stamp) { stamp.format(Date(clock())) }
        add("$at UTC  ${level.padEnd(5)}  [$cat] " + msg.lines().joinToString("\n") { LogRedact.line(it) })
    }

    /** A line as it was said elsewhere, dated already (Android's own log, as logcat writes it in UTC), redacted. */
    fun said(text: String) = add(LogRedact.line(text))

    private fun add(l: String) {
        synchronized(waiting) {
            waiting.addLast(next++ to l)
            while (waiting.size > most) waiting.removeFirst()
        }
    }

    /** How many lines wait to be sent. */
    val count: Int get() = synchronized(waiting) { waiting.size }

    /**
     * The lines waiting, oldest first and [sendChars] at most, handed to [post] as one text, a line each; let go when it
     * returns, kept when it throws. How many went. Off the main thread: [post] is the request.
     */
    fun send(post: (String) -> Unit): Int {
        val batch = StringBuilder()
        var taken = 0
        var last = -1L
        synchronized(waiting) {
            for ((n, l) in waiting) {
                if (taken > 0 && batch.length + l.length + 1 > sendChars) break
                batch.append(l).append('\n')
                taken++
                last = n
            }
        }
        if (taken == 0) return 0
        post(batch.toString())
        synchronized(waiting) { while (waiting.isNotEmpty() && waiting.first().first <= last) waiting.removeFirst() }
        return taken
    }
}
