package com.musicd.mallard.client

/**
 * Where the MusicD LMS Remote server is: a host and a port (3390 unless it was changed).
 *
 * People type these in by hand on a phone, so parsing is forgiving about what
 * does not matter — a pasted `http://`, a trailing slash, the port typed into
 * the address box as `192.168.1.10:3390` — and strict about what does: a port
 * outside 1-65535 or an address with spaces in it is refused with a sentence
 * that says which.
 */
data class ServerAddress(val host: String, val port: Int, val secure: Boolean = false) {

    /** The host as it goes into a URL: IPv6 literals need their brackets. */
    val urlHost: String get() = if (host.contains(':')) "[$host]" else host

    val baseUrl: String get() = (if (secure) "https" else "http") + "://$urlHost:$port"

    override fun toString(): String = "$urlHost:$port"

    companion object {
        const val DEFAULT_PORT = 3390

        /**
         * Parse what was typed into the two boxes. [hostInput] may carry a
         * scheme, a path or its own port; a port found there wins over an empty
         * [portInput] and loses to a filled one only if they disagree — in which
         * case that is an error, because one of them is a typo.
         */
        fun parse(hostInput: String, portInput: String): ServerAddress {
            var h = hostInput.trim()
            require(h.isNotEmpty()) { "Enter the server's address" }
            h = h.removePrefix("http://").removePrefix("https://")
            h = h.substringBefore('/').substringBefore('?').substringBefore('#')

            var embeddedPort: Int? = null
            if (h.startsWith("[")) {
                val end = h.indexOf(']')
                require(end > 0) { "That address is missing a closing ]" }
                val rest = h.substring(end + 1)
                if (rest.startsWith(":")) embeddedPort = portOf(rest.substring(1))
                h = h.substring(1, end)
            } else if (h.count { it == ':' } == 1) {
                embeddedPort = portOf(h.substringAfter(':'))
                h = h.substringBefore(':')
            }
            require(h.isNotEmpty()) { "Enter the server's address" }
            require(h.none { it.isWhitespace() }) { "An address can't contain spaces" }
            require(h.all { it.isLetterOrDigit() || it in ".-_:%" }) { "That doesn't look like an address" }

            val typed = portInput.trim().takeIf { it.isNotEmpty() }?.let { portOf(it) }
            if (typed != null && embeddedPort != null && typed != embeddedPort) {
                throw IllegalArgumentException("The address says port $embeddedPort but the port box says $typed")
            }
            return ServerAddress(h, typed ?: embeddedPort ?: DEFAULT_PORT)
        }

        /**
         * A whole address as the server gives it ("http://192.168.1.10:3390",
         * "https://musicd.tail1234.ts.net"), or null if it isn't one.
         */
        fun fromUrl(url: String): ServerAddress? {
            val u = url.trim()
            val secure = u.startsWith("https://", ignoreCase = true)
            if (!secure && !u.startsWith("http://", ignoreCase = true)) return null
            return runCatching {
                val a = parse(u, "")
                val typedPort = u.substringAfter("://").substringBefore('/').let { hp ->
                    if (hp.startsWith("[")) hp.substringAfter(']').startsWith(":") else hp.count { it == ':' } == 1
                }
                ServerAddress(a.host, if (typedPort) a.port else if (secure) 443 else 80, secure)
            }.getOrNull()
        }

        fun portOf(text: String): Int {
            val n = text.trim().toIntOrNull()
            require(n != null && n in 1..65535) { "A port is a number from 1 to 65535" }
            return n
        }
    }
}
