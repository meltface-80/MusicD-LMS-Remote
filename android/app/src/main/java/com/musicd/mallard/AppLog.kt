package com.musicd.mallard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.musicd.mallard.client.GateWatch
import com.musicd.mallard.client.LogSend
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.Transport
import com.musicd.mallard.client.UiTrace
import com.musicd.mallard.client.WayTransport
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's lines for Mandarin's one log (item 425; before, the app's own log, items 116, 121, 389): what the app's
 * screens did and when (UiTrace's findings marked WARN), each request to the server with how long it took, the way to
 * the server found and lost, the app's own lines of Android's log (the phone's player, the services, a crash), and now
 * and then the server's health and the phone's player. The app keeps no log: no file on the phone, no screen of it, no
 * Save or Copy log. Each line goes through LogRedact (core LogSend: no key, password, email, internet or Tailscale
 * address, item 235), waits in memory alone and is sent to the server within seconds (POST /api/diagnostics/app), which
 * writes it into the one log as "[app <this phone's name>]" with the time it was said; on Mandarin OS that is the shared
 * folder's Logs. While the server can't be reached the newest lines wait, 5,000 at most.
 */
internal object AppLog {
    /** The app's own tags in Android's log, the only ones read (with a crash's). */
    private val TAGS = listOf("Away", "TailscaleEngine", "PhonePlayer", "NowPlaying", "MainActivity", "Downloads", "AutoDownloads", "AppUpdate",
        "AppBackup", "LocalMusic", "OfflineSite", "ShareBridge", "StreamCache", "UsbDac", "UsbDriver", "HomeWifi", "RandomAlbumTile", "NowPlayingWidget")
    /** How long a line waits before it is sent with those after it: a burst goes as one request. */
    private const val SEND_AFTER_MS = 3000L
    private const val PATH = "/api/diagnostics/app"
    /** The lines on their way to the server, in memory alone. */
    private val out = LogSend()
    @Volatile private var app: Context? = null
    @Volatile private var logcat: Process? = null
    /** The Tailscale engine's CPU time at the last snapshot (item 202). */
    @Volatile private var lastEngineCpu: Double? = null
    /** Collecting: from the app's start, always (item 389). */
    @Volatile var on = false
        private set
    /** The screens' watch (findings marked WARN). Main thread only. */
    val trace = UiTrace()
    /** The last tap, for how long a move took after it; what was shown last, for the taps' lines. */
    @Volatile var lastTap = 0L
    @Volatile var shownLast = "start"
    /** When the app last came on screen (SystemClock.elapsedRealtime), for a freeze's report (item 471); 0 before it has. */
    @Volatile var onScreenAt = 0L

    /** With the app's process (MusicDApp): collecting, always (item 389); the files an app before 425 kept let go. */
    fun start(c: Context) {
        app = c.applicationContext
        if (on) return
        on = true
        Thread({ runCatching { java.io.File(c.filesDir, "applog").deleteRecursively() } }, "applog-gone").apply { isDaemon = true; start() }
        line("APP", "the app started: Mandarin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Thread({ readLogcat() }, "applog-logcat").apply { isDaemon = true; start() }
    }

    /** A line of [cat], without personal data; [anomaly] makes it a WARN, so it is found in the log at once. */
    fun line(cat: String, msg: String, anomaly: Boolean = false) {
        if (!on) return
        out.line(cat, msg, if (anomaly) "WARN" else "INFO")
        soon()
    }

    /** A crash: its trace (a line at a time without personal data) sent at once, the process going; three seconds at most. */
    fun crash(text: String) {
        if (!on) return
        out.line("APP", text, "ERROR")
        val t = Thread({ send() }, "applog-crash").apply { isDaemon = true; start() }
        runCatching { t.join(3000) }
    }

    /** UiTrace's findings, each an anomaly. */
    fun found(findings: List<UiTrace.Finding>) {
        for (f in findings) {
            // The same finding twice in the same moment (a frame's stall came twice, 20 October) is written once.
            if (f.text == lastFound?.text && f.atMs - (lastFound?.atMs ?: 0) < 50) continue
            lastFound = f
            line("UI", f.text, anomaly = true)
        }
    }
    @Volatile private var lastFound: UiTrace.Finding? = null

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }
    @Volatile private var pending = false
    @Volatile private var failedAt = -60_000L

    /** The lines waiting sent a few seconds from now, with any said meanwhile. */
    private fun soon() {
        if (pending) return
        pending = true
        runCatching { io.schedule(Runnable { pending = false; send() }, SEND_AFTER_MS, TimeUnit.MILLISECONDS) }.onFailure { pending = false }
    }

    /** The lines waiting sent to the server, for the one log; kept for the next try where it can't be reached. Off the main thread. */
    private fun send() {
        val c = app ?: return
        val client = if (OfflineNow.on) null else Store.client(c)
        // After a send that failed, none for half a minute: a server out of reach isn't asked every few seconds.
        if (client != null && SystemClock.elapsedRealtime() - failedAt > 30_000)
            runCatching { while (out.count > 0 && out.send { client.post(PATH, JSONObject().put("text", it)) } > 0) Unit }
                .onFailure { failedAt = SystemClock.elapsedRealtime() }
        // Not sent (offline, signed out, the server away): tried again in a minute, the lines waiting meanwhile.
        if (out.count > 0) runCatching { io.schedule(Runnable { soon() }, 60, TimeUnit.SECONDS) }
    }

    /** The app's own lines of Android's log (its tags, Info and above, and a crash's), each without personal data, while on. */
    private fun readLogcat() {
        runCatching {
            val filter = TAGS.map { "$it:I" } + listOf("AndroidRuntime:E", "*:S")
            val p = ProcessBuilder(listOf("logcat", "-v", "threadtime", "-v", "UTC", "-v", "year", "--pid=${android.os.Process.myPid()}", "-T", "1") + filter).redirectErrorStream(true).start()
            logcat = p
            // As logcat writes it ("2026-11-11 21:14:03.123  4242  4250 W Tag: …", in UTC): the server reads its time and level.
            p.inputStream.bufferedReader().forEachLine { l -> if (on && l.isNotBlank() && !l.startsWith("-----")) { out.said(l); soon() } }
        }.onFailure { line("APP", "Android's log can't be read: ${it.javaClass.simpleName}", anomaly = true) }
    }

    /**
     * [t]'s requests and answers, each logged with how long it took ([who]: the server, or the phone answering offline);
     * a slow answer (2 s or more) and an error marked.
     */
    fun net(t: Transport, who: String): Transport = object : Transport {
        override fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject = timed("$method $path", timeout) { t.json(method, path, body, timeout) }
        override fun bytes(path: String, timeout: Int): ByteArray = timed("GET $path", timeout) { t.bytes(path, timeout) }
        private fun <T> timed(what: String, timeout: Int, ask: () -> T): T {
            if (!on || what.endsWith(PATH)) return ask()
            val t0 = SystemClock.elapsedRealtime()
            val shown = what.take(200)
            try {
                val r = ask()
                val ms = SystemClock.elapsedRealtime() - t0
                val size = when (r) { is ByteArray -> " ${r.size} B"; else -> "" }
                // A wait for a change (zone-state's wait_for, the phone's commands' wait) is held by the server on purpose: never slow.
                line("NET", "$who $shown → ok in $ms ms$size", anomaly = UiTrace.slowAnswer(what, ms))
                return r
            } catch (e: Exception) {
                val ms = SystemClock.elapsedRealtime() - t0
                val why = (e as? ServerClient.ServerException)?.let { "HTTP ${it.status} ${it.message}" } ?: "${e.javaClass.simpleName}: ${e.message}"
                // Ended long after its own timeout: the phone slept through it (not a fault of the server's).
                if (UiTrace.slept(ms, timeout)) line("NET", "$who $shown → $why after $ms ms (the phone slept meanwhile)")
                else line("NET", "$who $shown → $why after $ms ms", anomaly = true)
                throw e
            }
        }
    }

    /** The server's client as Store makes it, logged while on: at the address [base] gives at each request, [ready] first (item 157). */
    fun server(base: () -> String, token: String, ready: () -> Unit, shut: () -> Unit): Transport = net(GateWatch(WayTransport(base, token, ready), shut), "server")

    /**
     * Now and then while the app is on screen (every five minutes, and on coming back): the server's health and status,
     * the way to it, the phone's player, the app's memory. Off the main thread.
     */
    fun snapshot(c: Context) {
        if (!on) return
        val app = c.applicationContext
        // The slow frames are the screens' watch's, read on its own thread.
        main.post { trace.slowFrames().takeIf { it.isNotEmpty() }?.let { slow -> line("UI", "slow frames (34–100 ms) since the last snapshot, by screen: " + slow.entries.joinToString { "${it.key} ${it.value}" }) } }
        Thread({
            val rt = Runtime.getRuntime()
            val mem = "memory ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB"
            val way = "way: " + (if (OfflineNow.on) "offline (${OfflineNow.kind.value})" else if (Store.isAway(app)) "away" + (if (Store.viaEngine(app)) " over MusicD's Tailscale" else " over the Tailscale app") else "home") +
                ", network ${if (Away.hasNetwork(app)) "up" else "none"}"
            line("SNAP", "$way; $mem")
            val c2 = Store.client(app)
            if (c2 != null && !OfflineNow.on) {
                runCatching { c2.getJson("/api/health") }.onSuccess { h ->
                    line("SNAP", "server: version ${h.optString("version")}, ${h.optInt("albums")} albums, ${h.optInt("rooms")} rooms, ffmpeg ${h.optBoolean("ffmpeg")}")
                }
                runCatching { c2.getJson("/api/status") }.onSuccess { s ->
                    val scan = s.optJSONObject("scan")
                    line("SNAP", "server status: scan " + (if (scan?.optBoolean("running") == true) "running" else "idle") +
                        ", offline ${s.optBoolean("offline")}")
                }
                runCatching { c2.zones() }.onSuccess { z -> line("SNAP", "rooms: ${z.zones.size}, the last " + (if (z.lastZone?.startsWith("PHONE_") == true) "this phone" else if (z.lastZone == null) "none" else "a room of the server's")) }
            }
            PhonePlayerService.current?.offlineState()?.let { p ->
                line("SNAP", "phone player: ${p.state}, track ${p.index} of ${p.items.size}, at ${p.position} of ${p.duration}, volume ${p.volume}, revision ${p.revision}")
            } ?: line("SNAP", "phone player: not running")
            // The engine's CPU time, and how much since the last snapshot (item 202: Android ended the app for it, unseen).
            line("SNAP", "engine: " + (TailscaleEngine.cpuSeconds()?.let { cpu ->
                val since = lastEngineCpu?.let { " (+%.1f s since the last snapshot)".format(cpu - it) }.orEmpty()
                lastEngineCpu = cpu
                "running, cpu %.1f s%s".format(cpu, since)
            } ?: run { lastEngineCpu = null; "not running" }))
        }, "applog-snap").apply { isDaemon = true; start() }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The snapshots' timer. */
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "applog-timer").apply { isDaemon = true } }
    @Volatile private var snapping: java.util.concurrent.ScheduledFuture<*>? = null

    /** Snapshots every five minutes while [c]'s screens are on, and logging is. */
    fun snapshots(c: Context, on: Boolean) {
        snapping?.cancel(false)
        snapping = if (on && this.on) timer.scheduleWithFixedDelay({ snapshot(c) }, 0, 5, TimeUnit.MINUTES) else null
    }
}
