package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Android's endings of the app the next start tells of (bug list item 34), and how. */
class ExitReportTest {
    private fun exit(at: Long, reason: Int, importance: Int, why: String? = null) = AppExit(at, reason, importance, why)

    @Test
    fun `a new install tells of nothing older`() {
        assertNull(ExitReport.pick(listOf(exit(5, ExitReport.EXCESSIVE_RESOURCE_USAGE, ExitReport.FOREGROUND_SERVICE)), null))
    }

    @Test
    fun `only endings since the last look`() {
        val exits = listOf(exit(5, ExitReport.ANR, ExitReport.FOREGROUND), exit(9, ExitReport.LOW_MEMORY, ExitReport.FOREGROUND_SERVICE))
        assertEquals(9L, ExitReport.pick(exits, 5)?.at)
        assertNull(ExitReport.pick(exits, 9))
    }

    @Test
    fun `the latest worth saying, past an ordinary one after it`() {
        val exits = listOf(exit(7, ExitReport.OTHER, ExitReport.FOREGROUND_SERVICE), exit(8, ExitReport.LOW_MEMORY, ExitReport.CACHED))
        assertEquals(7L, ExitReport.pick(exits, 0)?.at)
    }

    @Test
    fun `while playing, any ending the system chose is worth saying`() {
        for (r in listOf(ExitReport.LOW_MEMORY, ExitReport.SIGNALED, ExitReport.OTHER))
            assertTrue("reason $r", ExitReport.worth(exit(1, r, ExitReport.FOREGROUND_SERVICE)))
    }

    @Test
    fun `idle, or the player only waiting, an ordinary clean-up is not`() {
        assertFalse(ExitReport.worth(exit(1, ExitReport.LOW_MEMORY, ExitReport.CACHED)))
        assertFalse(ExitReport.worth(exit(1, ExitReport.OTHER, ExitReport.SERVICE)))
    }

    @Test
    fun `faults are said whenever they came`() {
        for (r in listOf(ExitReport.EXCESSIVE_RESOURCE_USAGE, ExitReport.ANR, ExitReport.CRASH_NATIVE, ExitReport.DEPENDENCY_DIED))
            assertTrue("reason $r", ExitReport.worth(exit(1, r, ExitReport.CACHED)))
    }

    @Test
    fun `frozen while idle is a clean-up, kept for the log alone`() {
        // Item 125: 21 October 18:49, six minutes after the phone's player had stopped itself, idle (400), and a note
        // at the next start as if something had gone wrong.
        val frozen = exit(10, ExitReport.FREEZER, ExitReport.CACHED, "[FREEZER BINDER TRANSACTION] Sync transaction while frozen")
        assertFalse(ExitReport.worth(frozen))
        assertNull(ExitReport.pick(listOf(frozen), 5))
        val low = exit(8, ExitReport.LOW_MEMORY, ExitReport.CACHED)
        assertEquals(listOf(low, frozen), ExitReport.quiet(listOf(frozen, low, exit(3, ExitReport.LOW_MEMORY, ExitReport.CACHED)), 5))
        assertEquals(emptyList<AppExit>(), ExitReport.quiet(listOf(frozen), null))
        // In use it would still be said.
        assertTrue(ExitReport.worth(exit(1, ExitReport.FREEZER, ExitReport.FOREGROUND_SERVICE)))
    }

    @Test
    fun `woken by Android for a task of its own is not in use`() {
        // Item 126: 22 October 04:05, "a signal" at 230 with no words from Android, overnight: the nightly backup ends
        // the app it backed up. On screen, in view or playing, the same ending is still said.
        val backup = exit(10, ExitReport.SIGNALED, ExitReport.PERCEPTIBLE)
        assertFalse(ExitReport.worth(backup))
        assertEquals(listOf(backup), ExitReport.quiet(listOf(backup), 5))
        for (i in listOf(ExitReport.FOREGROUND, ExitReport.FOREGROUND_SERVICE, ExitReport.VISIBLE))
            assertTrue("importance $i", ExitReport.worth(exit(1, ExitReport.SIGNALED, i)))
        assertEquals("in the background, woken by Android (230)", ExitReport.importance(ExitReport.PERCEPTIBLE))
        assertEquals("in the background, in view (200)", ExitReport.importance(ExitReport.VISIBLE))
    }

    @Test
    fun `never what was asked for, or a crash the crash log already keeps`() {
        for (r in listOf(ExitReport.USER_REQUESTED, ExitReport.USER_STOPPED, ExitReport.EXIT_SELF, ExitReport.PACKAGE_UPDATED, ExitReport.CRASH))
            assertFalse("reason $r", ExitReport.worth(exit(1, r, ExitReport.FOREGROUND_SERVICE)))
    }

    @Test
    fun `said in words, with Android's own`() {
        assertEquals(
            "Android ended Mandarin (too much CPU or battery) while it was playing in the background (125), 19 Oct 09:12.\nAndroid's words: excessive cpu 31000 during 300000",
            ExitReport.text(exit(1, ExitReport.EXCESSIVE_RESOURCE_USAGE, 125, "excessive cpu 31000 during 300000"), "19 Oct 09:12")
        )
        assertEquals("Android ended Mandarin (short of memory) while it was idle (400), now.", ExitReport.text(exit(1, ExitReport.LOW_MEMORY, 400, " "), "now"))
    }

    @Test
    fun `the player giving up says whether the engine was running`() {
        assertEquals(
            "Playback stopped away from home and gave up after its tries, now: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (connect timed out). Mandarin's Tailscale engine was not running.",
            ExitReport.playerText("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "connect timed out", viaEngine = true, engineRunning = false, time = "now")
        )
        assertTrue(ExitReport.playerText("E", null, viaEngine = false, engineRunning = false, time = "now").endsWith("E. The Tailscale app carried it."))
    }

    @Test
    fun `the engine ending says its code, killed or not, and its last lines`() {
        assertEquals("Mandarin's Tailscale engine ended by itself, now, exit code 137 (killed).", ExitReport.engineText(137, emptyList(), "now"))
        val lines = (1..10).map { "line $it" }
        assertTrue(ExitReport.engineText(2, lines, "now").endsWith("Its last lines:\n" + (3..10).joinToString("\n") { "line $it" }))
    }

    @Test
    fun `ended for a child process's CPU, the note names the engine (item 202)`() {
        val words = "[EXCESSIVE CPU USAGE] Caused by child process: excessive cpu 131180 during 300059 dur=250359 limit=25"
        val e = exit(5, ExitReport.EXCESSIVE_RESOURCE_USAGE, ExitReport.CACHED, words)
        assertTrue(ExitReport.childProcess(e))
        val t = ExitReport.text(e, "26 Oct 2026 15:25")
        assertTrue(t, t.contains("Android's words: " + words))
        assertTrue(t, t.endsWith(ExitReport.CHILD))
        val own = exit(6, ExitReport.EXCESSIVE_RESOURCE_USAGE, ExitReport.CACHED, "[EXCESSIVE CPU USAGE] excessive cpu 90000 during 300000")
        assertFalse(ExitReport.childProcess(own))
        assertFalse(ExitReport.text(own, "x").contains(ExitReport.CHILD))
    }
}
