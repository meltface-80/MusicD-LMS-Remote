package com.musicd.mallard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.Transport
import com.musicd.mallard.client.UiTrace
import org.json.JSONObject

/**
 * Mallard's own log: what the screens did and when (UiTrace's findings marked WARN), each request to the server with how
 * long it took, and a crash. MusicD LMS Remote has no route that takes an app's log, so the lines go to Android's log
 * (tag "Mallard", readable with `adb logcat -s Mallard`) and the newest 2,000 wait in memory for the crash report.
 */
internal object AppLog {
    private const val TAG = "Mallard"
    private const val KEEP = 2000

    private val recent = ArrayDeque<String>()

    @Volatile var on = false
        private set
    /** The screens' watch (findings marked WARN). Main thread only. */
    val trace = UiTrace()
    /** The last tap, for how long a move took after it; what was shown last, for the taps' lines. */
    @Volatile var lastTap = 0L
    @Volatile var shownLast = "start"
    /** When the app last came on screen (SystemClock.elapsedRealtime), for a freeze's report; 0 before it has. */
    @Volatile var onScreenAt = 0L

    fun start(c: Context) {
        if (on) return
        on = true
        line("APP", "the app started: Mallard ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    /** A line of [cat]; [anomaly] makes it a WARN, so it is found in the log at once. */
    fun line(cat: String, msg: String, anomaly: Boolean = false) {
        if (!on) return
        val text = "[$cat] $msg"
        if (anomaly) Log.w(TAG, text) else Log.i(TAG, text)
        synchronized(recent) {
            recent.addLast(text)
            while (recent.size > KEEP) recent.removeFirst()
        }
    }

    /** A crash: its trace, as an error line. */
    fun crash(text: String) { if (on) Log.e(TAG, text) }

    /** The newest lines, oldest first (for a report). */
    fun lines(): List<String> = synchronized(recent) { recent.toList() }

    /** UiTrace's findings, each an anomaly. */
    fun found(findings: List<UiTrace.Finding>) {
        for (f in findings) {
            if (f.text == lastFound?.text && f.atMs - (lastFound?.atMs ?: 0) < 50) continue
            lastFound = f
            line("UI", f.text, anomaly = true)
        }
    }
    @Volatile private var lastFound: UiTrace.Finding? = null

    /** [t]'s requests and answers, each logged with how long it took; a slow answer (2 s or more) and an error marked. */
    fun net(t: Transport, who: String): Transport = object : Transport {
        override fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject = timed("$method $path", timeout) { t.json(method, path, body, timeout) }
        override fun bytes(path: String, timeout: Int): ByteArray = timed("GET $path", timeout) { t.bytes(path, timeout) }
        private fun <T> timed(what: String, timeout: Int, ask: () -> T): T {
            if (!on) return ask()
            val t0 = SystemClock.elapsedRealtime()
            val shown = what.take(200)
            try {
                val r = ask()
                val ms = SystemClock.elapsedRealtime() - t0
                val size = when (r) { is ByteArray -> " ${r.size} B"; else -> "" }
                line("NET", "$who $shown → ok in $ms ms$size", anomaly = UiTrace.slowAnswer(what, ms))
                return r
            } catch (e: Exception) {
                val ms = SystemClock.elapsedRealtime() - t0
                val why = (e as? ServerClient.ServerException)?.let { "HTTP ${it.status} ${it.message}" } ?: "${e.javaClass.simpleName}: ${e.message}"
                if (UiTrace.slept(ms, timeout)) line("NET", "$who $shown → $why after $ms ms (the phone slept meanwhile)")
                else line("NET", "$who $shown → $why after $ms ms", anomaly = true)
                throw e
            }
        }
    }

    /** Kept for the callers that start and stop snapshots with the screen: Mallard takes none. */
    fun snapshots(c: Context, on: Boolean) = Unit
}
