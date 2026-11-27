package com.musicd.mallard.client

import java.io.IOException

/**
 * What a screen says when asking the server failed (item 127): the server's own words when it answered with an
 * error, and "Couldn't reach the server" when it couldn't be reached at all, rather than the connection's own
 * message, which names addresses and ports ("failed to connect to /192.168.0.57 (port 3500) from /192.0.0.4 …").
 */
object Failure {
    const val UNREACHABLE = "Couldn't reach the server"

    fun said(t: Throwable, otherwise: String): String = if (unreachable(t)) UNREACHABLE else t.message ?: otherwise

    /** The server not reached at all (worth asking again in a while), not an answer of its own. */
    fun unreachable(t: Throwable): Boolean = t is IOException && t !is ServerClient.ServerException

    /**
     * Worth asking again in a while (item 157): the server not reached, or its own trouble (a 5xx, its library still
     * being read) — not a refusal or a not-found, which asking again won't change.
     */
    fun worthAgain(t: Throwable): Boolean = unreachable(t) || (t as? ServerClient.ServerException)?.status?.let { it >= 500 } == true
}
