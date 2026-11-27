package com.musicd.mallard.client

/**
 * The app's log without what is the owner's alone (bug list items 121, 235): each line goes through [line] before it
 * is written, and the server's lines (already scrubbed the same way, the server's LogScrub.cs) again as they are saved.
 * From item 121 every address, name, title, file and quoted word went too, and with them what a log is for: on
 * 2 November the server's log held 30,767 Qobuz failures and not one cause. The owner's rule since: hide what is theirs
 * alone, keep what helps find what went wrong.
 *
 * Hidden: keys, tokens and passwords ("token=[…]", "Bearer […]", a run of 32 or more letters and figures); email
 * addresses ([email]); internet (WAN) addresses ([wan]); Tailscale's addresses, 100.64.0.0/10 and fd7a:115c:a1e0::/48,
 * and its names, *.ts.net ([tailscale]); the user's own folder in a path (/home/[user]/…).
 *
 * Kept: everything else. Home-network addresses (192.168.x.x, 10.x.x.x, 172.16–31.x.x, fe80::, 127.0.0.1), rooms'
 * and devices' names and ids, what is played and searched for, files and folders, error messages.
 */
object LogRedact {
    const val HIDDEN = "[…]"

    private val URL_LOGIN = Regex("""(://)[^/\s:@]+:[^/\s@]+@""")
    private val BEARER = Regex("""\b(Bearer|Basic)\s+[^\s&,;]+""")
    private val SECRET = Regex("""(?i)\b([\w-]*(?:token|password|passwd|pwd|secret|apikey|api[_-]key|sig|signature|authorization|cookie))(\s*[=:]\s*)(?!\[…])[^\s&,;]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val LONG = Regex("""\b[A-Za-z0-9_-]{32,}\b""")
    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val IPV4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?:/\d{1,2})?(?![\w.])""")
    private val IPV6 = Regex("""(?<![\w:])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?)(?:%[\w.-]+)?(?![\w:.])""")
    private val TAILNET = Regex("""\b[\w-]+(?:\.[\w-]+)*\.ts\.net\b""")
    private val HOME = Regex("""(?<![\w.])(/home/|/Users/|[A-Za-z]:\\Users\\)[^/\\\s]+""")

    /** [s] without what is the owner's alone. */
    fun line(s: String): String {
        var t = URL_LOGIN.replace(s) { it.groupValues[1] + HIDDEN + "@" }
        t = BEARER.replace(t) { it.groupValues[1] + " " + HIDDEN }
        t = SECRET.replace(t) { it.groupValues[1] + it.groupValues[2] + HIDDEN }
        t = EMAIL.replace(t, "[email]")
        t = LONG.replace(t) { if (UUID.matches(it.value)) it.value else HIDDEN }
        t = IPV4.replace(t) { v4(it.value) ?: it.value }
        t = IPV6.replace(t) { v6(it.value) ?: it.value }
        t = TAILNET.replace(t, "[tailscale]")
        return HOME.replace(t) { it.groupValues[1] + "[user]" }
    }

    /** An IPv4 address's kind when it is hidden ("[wan]", "[tailscale]"), or null when it is kept (or isn't one). */
    private fun v4(s: String): String? {
        val o = s.substringBefore('/').split('.').map { it.toInt() }
        if (o.any { it > 255 }) return null
        val (a, b) = o
        val home = a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 192 && b == 0 && o[2] == 0)
        return if (home) null else if (a == 100 && b in 64..127) "[tailscale]" else "[wan]"
    }

    /** An IPv6 address's kind when it is hidden, or null when it is kept: link-local, private, loopback, multicast. */
    private fun v6(s: String): String? {
        val t = s.lowercase()
        if (t.startsWith("fd7a:115c:a1e0:")) return "[tailscale]"
        val head = t.substringBefore(':')
        if (head.isEmpty()) return null
        return if (head.toInt(16) in 0x2000..0x3fff) "[wan]" else null
    }
}
