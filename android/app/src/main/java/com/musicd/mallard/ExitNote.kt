package com.musicd.mallard

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.musicd.mallard.client.AppExit
import com.musicd.mallard.client.ExitReport
import java.text.DateFormat
import java.util.Date

/**
 * Why Android last ended the app, kept for the next start to say (bug list
 * item 34): playback that stopped while the app wasn't on screen leaves no
 * crash behind, only Android's own note of why it ended the process
 * (ApplicationExitInfo, Android 11 on). Which endings are worth saying is
 * [ExitReport]'s to decide.
 */
internal object ExitNote {
    private const val PREFS = "exit_note"
    private const val SEEN = "seen"

    /** At each start of the process: Android's endings since the last look, the one worth saying kept with the crash log. */
    fun keep(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = c.getSystemService(ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(c.packageName, 0, 8)
                .filter { it.processName == c.packageName }
                .map { AppExit(it.timestamp, it.reason, it.importance, it.description) }
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = if (prefs.contains(SEEN)) prefs.getLong(SEEN, 0L) else null
            prefs.edit().putLong(SEEN, maxOf(since ?: 0L, exits.maxOfOrNull { it.at } ?: 0L)).apply()
            // The ordinary ones (idle, frozen or short of memory: item 125) only in the log, when it is on.
            for (q in ExitReport.quiet(exits, since)) AppLog.line("APP", "no note: " + ExitReport.text(q, time(q.at)))
            val e = ExitReport.pick(exits, since) ?: return
            CrashLog.keepNote(c, ExitReport.text(e, time(e.at)))
        }
    }

    /** When, as the phone writes it. */
    fun time(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
}
