package com.musicd.mallard

import android.app.AlertDialog
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Mallard's process. Its one job: when the app crashes, keep what went wrong (the stack trace) so the next start can
 * show it and offer to share it — a crash on a phone otherwise leaves nothing to go on.
 */
class MusicDApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        CrashLog.install(this)
        CrashLog.watchMainThread(this)
        // The USB port watched (a DAC plugged in or pulled out) for the phone's own player away from home.
        UsbDac.start(this)
    }
}

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last-crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                AppLog.crash("CRASH on ${thread.name}: $sw")
                file(app).writeText(
                    "Mallard ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * A frozen app leaves no crash behind — it just sits there (the start-up
     * logo, until it's force-stopped). So the main thread is watched: if it
     * hasn't answered for 10 s, what it's stuck on is kept the same way as a
     * crash, and the next start offers it to share.
     */
    fun watchMainThread(c: Context) {
        val app = c.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            var stuckFor = 0
            var kept = false
            while (true) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { return@Thread }
                if (answered.get()) { stuckFor = 0; kept = false; continue }
                stuckFor += 2
                if (stuckFor == 4) AppLog.line("APP", "FREEZE: the screen hasn't answered for 4 s, ${stall()}, at: " +
                    android.os.Looper.getMainLooper().thread.stackTrace.take(12).joinToString(" < "), anomaly = true)
                if (stuckFor >= 10 && !kept) {
                    kept = true
                    runCatching {
                        val trace = android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
                        file(app).writeText(
                            "$FROZE ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                                "${Build.MANUFACTURER} ${Build.MODEL}\nThe screen stopped answering for $stuckFor s, ${stall()}, at:\n\n$trace"
                        )
                    }
                }
            }
        }, "main-watch").apply { isDaemon = true; start() }
    }
    private const val FROZE = "Mallard froze"

    /*
     * Where the app was and what Android's drawing thread was doing as the screen stopped answering (item 471): a freeze
     * stuck drawing a frame (HardwareRenderer.nSyncAndDrawFrame) is the main thread waiting for it. Its state as the
     * kernel has it, read from the app's own /proc; what can't be read is left out.
     */
    private fun stall(): String {
        val on = AppLog.onScreenAt
        val where = "on ${AppLog.shownLast}" + (if (on > 0) ", ${(android.os.SystemClock.elapsedRealtime() - on) / 1000} s after it came on screen" else "")
        val task = runCatching {
            java.io.File("/proc/self/task").listFiles()?.firstOrNull { f -> runCatching { java.io.File(f, "comm").readText().trim() == "RenderThread" }.getOrDefault(false) }
        }.getOrNull()
        fun read(name: String) = task?.let { t -> runCatching { java.io.File(t, name).readText() }.getOrNull() }
        return where + "; drawing thread: " + (read("wchan")?.trim()?.ifEmpty { null } ?: "unknown")
    }

    /**
     * Something the next start should say though nothing crashed: Android
     * ending the app, playback giving up away from home, the Tailscale engine
     * ending (bug list item 34). Added to what is already waiting, a crash or
     * a freeze included, so the order they came in is kept.
     */
    @Synchronized
    fun keepNote(c: Context, what: String) {
        AppLog.line("APP", "note kept for the next start: $what", anomaly = true)
        runCatching {
            val f = file(c.applicationContext)
            val before = if (f.exists()) f.readText().take(6000) else ""
            f.writeText(
                if (before.isNotBlank()) "$before\n\n$what"
                else "$NOTE ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n\n$what"
            )
        }
    }
    private const val NOTE = "Mallard stopped in the background"
    /** A note an app before item 111 kept when its page stopped: still offered once. */
    private const val PAGE = "Mallard's page stopped"

    /** After a crash (or a freeze): say so, once, and offer the details to share. */
    fun offer(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (text.isBlank()) return
        val page = text.startsWith(PAGE)
        AlertDialog.Builder(activity)
            .setTitle(when { text.startsWith(FROZE) -> "Mallard froze last time"; page -> "Mallard's page stopped last time"; text.startsWith(NOTE) -> "$NOTE last time"; else -> "Mallard stopped last time" })
            .setMessage((if (page) "The app put it up again by itself. " else "") + "Sharing the details helps get it fixed.\n\n" + text.take(1200))
            .setPositiveButton("Share details") { _, _ ->
                runCatching {
                    activity.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, when { text.startsWith(FROZE) -> "Mallard freeze"; page -> "Mallard page stopped"; text.startsWith(NOTE) -> NOTE; else -> "Mallard crash" })
                            .putExtra(Intent.EXTRA_TEXT, text),
                        "Share crash details"))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
