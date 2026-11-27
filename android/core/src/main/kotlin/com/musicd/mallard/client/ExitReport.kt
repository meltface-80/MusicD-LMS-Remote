package com.musicd.mallard.client

/**
 * One ending of the app's process, as Android keeps it
 * (android.app.ApplicationExitInfo): when, why, and how much the app was in
 * use then.
 */
data class AppExit(val at: Long, val reason: Int, val importance: Int, val description: String?)

/**
 * Why playback stopped while the app wasn't on screen, for the next start to
 * say (bug list item 34). Android ends an app's process for many reasons,
 * most of them ordinary (the app idle, the phone short of memory); only an
 * ending that says something is offered: one Android calls a fault, or one
 * that came while the app was in use (playing, or on screen).
 */
object ExitReport {
    // ApplicationExitInfo.REASON_*: core is plain Kotlin, so the numbers are copied.
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    // ActivityManager.RunningAppProcessInfo.IMPORTANCE_*.
    const val FOREGROUND = 100
    const val FOREGROUND_SERVICE = 125
    const val VISIBLE = 200
    const val PERCEPTIBLE = 230
    const val SERVICE = 300
    const val CACHED = 400

    /**
     * The latest ending since [since] worth saying, or none. [since] null:
     * never looked before (a new install), so none, the older endings being
     * no news.
     */
    fun pick(exits: List<AppExit>, since: Long?): AppExit? =
        if (since == null) null else exits.filter { it.at > since && worth(it) }.maxByOrNull { it.at }

    /**
     * Worth saying: a fault, or an ending while the app was in use; never one asked for, or one the crash log already has.
     * Frozen (item 125) is Android's clean-up of an idle app, like short of memory: a frozen app another app calls on and
     * must wait for is ended for it ("Sync transaction while frozen"), whoever the caller, the app losing nothing it
     * doesn't bring back (the screen it was on, the phone's queue, paused). In use is on screen, in view or playing:
     * perceptible (230) is Android waking the app for a task of its own (item 126: a signal at 04:05 with no words, the
     * nightly backup, which ends the app it backed up), not the user using it.
     */
    fun worth(e: AppExit): Boolean = when (e.reason) {
        EXIT_SELF, CRASH, USER_REQUESTED, USER_STOPPED, PERMISSION_CHANGE, PACKAGE_STATE_CHANGE, PACKAGE_UPDATED -> false
        CRASH_NATIVE, ANR, INITIALIZATION_FAILURE, EXCESSIVE_RESOURCE_USAGE, DEPENDENCY_DIED -> true
        else -> e.importance < PERCEPTIBLE
    }

    /** The endings since [since] not worth a note, oldest first: for the log alone. */
    fun quiet(exits: List<AppExit>, since: Long?): List<AppExit> =
        if (since == null) emptyList() else exits.filter { it.at > since && !worth(it) }.sortedBy { it.at }

    /** What the next start says of it; [time] is when, as the phone writes a date. */
    fun text(e: AppExit, time: String): String =
        "Android ended Mandarin (${reason(e.reason)}) while it was ${importance(e.importance)}, $time." +
            (e.description?.takeIf { it.isNotBlank() }?.let { "\nAndroid's words: $it" } ?: "") +
            (if (childProcess(e)) "\n$CHILD" else "")

    /** Android ended the app for what a child process did: the one process Mandarin runs beside itself is the Tailscale engine (item 202). */
    fun childProcess(e: AppExit): Boolean = e.description?.contains("Caused by child process") == true

    const val CHILD = "The child process is Mandarin's own Tailscale engine: it was using the CPU, and Android ended the app with it."

    fun reason(r: Int): String = when (r) {
        EXIT_SELF -> "it closed itself"
        SIGNALED -> "a signal"
        LOW_MEMORY -> "short of memory"
        CRASH -> "a crash"
        CRASH_NATIVE -> "a crash in native code"
        ANR -> "not responding"
        INITIALIZATION_FAILURE -> "it failed to start"
        PERMISSION_CHANGE -> "a permission changed"
        EXCESSIVE_RESOURCE_USAGE -> "too much CPU or battery"
        USER_REQUESTED -> "asked to"
        USER_STOPPED -> "force-stopped"
        DEPENDENCY_DIED -> "something it depends on stopped"
        OTHER -> "the system's own reasons"
        FREEZER -> "frozen in the background"
        PACKAGE_STATE_CHANGE -> "the app was disabled"
        PACKAGE_UPDATED -> "the app was updated"
        else -> "reason $r"
    }

    fun importance(i: Int): String = when {
        i <= FOREGROUND -> "on screen"
        i <= FOREGROUND_SERVICE -> "playing in the background"
        i <= VISIBLE -> "in the background, in view"
        i <= PERCEPTIBLE -> "in the background, woken by Android"
        i <= SERVICE -> "in the background, not playing"
        else -> "idle"
    } + " ($i)"

    /** The phone's player giving up away from home: how it stopped, and whether the Tailscale engine was still running. */
    fun playerText(error: String, cause: String?, viaEngine: Boolean, engineRunning: Boolean, time: String): String =
        "Playback stopped away from home and gave up after its tries, $time: $error" +
            (cause?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". " +
            (if (viaEngine) "Mandarin's Tailscale engine was " + (if (engineRunning) "running." else "not running.") else "The Tailscale app carried it.")

    /** The Tailscale engine ending without being asked: its exit code (137: killed) and its last lines. */
    fun engineText(code: Int, lastLines: List<String>, time: String): String =
        "Mandarin's Tailscale engine ended by itself, $time, exit code $code" + (if (code == 137) " (killed)" else "") + "." +
            (if (lastLines.isEmpty()) "" else "\nIts last lines:\n" + lastLines.takeLast(8).joinToString("\n"))
}
