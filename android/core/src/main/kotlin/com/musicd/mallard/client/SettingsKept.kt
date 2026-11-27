package com.musicd.mallard.client

import org.json.JSONObject

/**
 * The server's last answer to each of Settings' reads (item 198), so a page opens as it was last seen and then follows
 * the server, as the side menu has since item 122. Before, every page opened with its defaults (Ask for a sign-in on,
 * no folders, every switch off) and changed as the answers came.
 *
 * Kept in memory and on the phone ([attach]) for the server they came from: another server's are never shown. Only the
 * server's own answers are kept ([KeptTransport]), never the phone's offline ones. A write is followed by the reads it
 * changes ([after]), read again behind it, so a page never opens on a value from before a change. API keys are never kept.
 */
object SettingsKept {
    /** The reads kept: exact paths, and the settings by name (not the API keys, which are settings too). */
    private val PATHS = setOf(
        "/api/auth/status", "/api/auth/devices", "/api/library/folders", "/api/audio-devices", "/api/backup",
        "/api/identify?lists=0", "/api/loudness", "/api/covers/auto", "/api/album-scanner", "/api/library/cleanup", "/api/tailscale", "/api/update/status"
    )
    private val SETTINGS = setOf(
        "waveform", "smart-picks", "display", "shelf", "home-rows", "qobuz", "tidal", "default-service"
    )
    private const val SETTING = "/api/settings/"
    private const val DEVICE = "/api/audio-devices/"

    /** An answer larger than this isn't kept on the phone (a list of hundreds); it is still kept in memory. */
    const val DISK_MAX = 64 * 1024

    private val answers = LinkedHashMap<String, String>()
    private var server: () -> String? = { null }
    private var write: (String) -> Unit = {}
    private var attached = false

    /** Whether the answer to GET [path] is kept. */
    fun keeps(path: String): Boolean = path in PATHS ||
        path.startsWith(SETTING) && path.removePrefix(SETTING) in SETTINGS ||
        path.startsWith(DEVICE) && path.length > DEVICE.length && path.indexOf('/', DEVICE.length) < 0 && '?' !in path

    /**
     * Kept on the phone from now on: [read] what was kept before (for [serverId]'s server, or nothing), [write] it again
     * whenever an answer changes. Once a run; the first [attach] wins.
     */
    @Synchronized fun attach(serverId: () -> String?, read: () -> String?, write: (String) -> Unit) {
        if (attached) return
        attached = true
        server = serverId
        this.write = write
        runCatching { JSONObject(read() ?: return) }.getOrNull()?.takeIf { it.optString("server") == (serverId() ?: "") }?.optJSONObject("answers")?.let { a ->
            a.keys().forEach { k -> if (k !in answers) answers[k] = a.getJSONObject(k).toString() }
        }
    }

    /** The server's last answer to [path], or null: never read, or forgotten since. */
    @Synchronized fun last(path: String): JSONObject? = answers[path]?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** The last answer to [path] as [parse] reads it, or null (none, or one it can't read). */
    fun <T> last(path: String, parse: (JSONObject) -> T): T? = last(path)?.let { runCatching { parse(it) }.getOrNull() }

    /** The server's answer [json] to GET [path], kept if it is one of Settings' reads. */
    fun saw(path: String, json: JSONObject) {
        if (!keeps(path)) return
        val s = json.toString()
        val changed = synchronized(this) { (answers.put(path, s) != s) }
        if (changed) save()
    }

    /**
     * The kept reads a write to [path] changes, to be read again behind it: the path itself and those above it
     * (/api/settings/qobuz for its sign-in, a device and the list for a device's DSP), and the scanner's and the
     * account's own. Only those kept already: one never read has nothing to put right.
     */
    fun after(path: String): List<String> {
        val p = path.substringBefore('?')
        val near = generateSequence(p) { it.substringBeforeLast('/', "").takeIf { up -> up.length > "/api".length } }.toMutableList()
        when {
            p.startsWith("/api/auth/") -> near += listOf("/api/auth/status", "/api/auth/devices")
            // The Album Scanner (item 411) and the scanner's switches and lists before it: each one's read again.
            p.startsWith("/api/identify") || p.startsWith("/api/loudness") || p.startsWith("/api/library/cleanup") || p.startsWith("/api/album-scanner") ->
                near += listOf("/api/album-scanner", "/api/identify?lists=0", "/api/loudness", "/api/library/cleanup")
            p.startsWith("/api/update/") -> near += "/api/update/status"
        }
        return synchronized(this) { near.distinct().filter { keeps(it) && it in answers } }
    }

    /** Every answer forgotten (signed out, another server). */
    fun forget() {
        synchronized(this) { answers.clear() }
        save()
    }

    private fun save() {
        val blob = synchronized(this) {
            val a = JSONObject()
            answers.forEach { (k, v) -> if (v.length <= DISK_MAX) a.put(k, JSONObject(v)) }
            JSONObject().put("server", server() ?: "").put("answers", a).toString()
        }
        runCatching { write(blob) }
    }

    /** For tests: as a new run of the app. */
    @Synchronized fun reset() { answers.clear(); server = { null }; write = {}; attached = false }
}

/**
 * [inner]'s answers, Settings' reads kept as they come, and a write's reads made again behind it on a thread of their own,
 * so the write's answer isn't held for them ([SettingsKept], item 198). The server's alone.
 */
class KeptTransport(private val inner: Transport, private val behind: (() -> Unit) -> Unit = { Thread(it).start() }) : Transport {
    override fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject {
        val j = inner.json(method, path, body, timeout)
        if (method == "GET") SettingsKept.saw(path, j)
        else SettingsKept.after(path).takeIf { it.isNotEmpty() }?.let { reads ->
            behind { reads.forEach { r -> runCatching { SettingsKept.saw(r, inner.json("GET", r, null, timeout)) } } }
        }
        return j
    }

    override fun bytes(path: String, timeout: Int): ByteArray = inner.bytes(path, timeout)
}
