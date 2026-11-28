package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The diagnostic log saved on the phone (Settings → System → Log, DiagnosticFile): named by when it was saved, in the
 * phone's own time; the server's log first, then this phone's recent lines under its name, each without what is the
 * owner's alone.
 */
class DiagnosticFileTest {
    @Test fun `named by when it was saved, in the phone's own time`() {
        val was = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals("mallard-log-202606151407.txt", DiagnosticFile.name(1_781_528_820_000L))   // 2026-06-15 13:07 UTC, 14:07 BST
        } finally { TimeZone.setDefault(was) }
    }

    @Test fun `the server's log, then this phone's lines under its name, each redacted`() {
        val f = DiagnosticFile.join("MusicD LMS Remote — diagnostic log\nline\n\n", "2026-11-28 13:00:00.000 UTC  WARN   [NET] token=sekrit from owner@example.com\nsecond", "Pixel 9")
        assertEquals("MusicD LMS Remote — diagnostic log\nline\n\n===== Mallard on Pixel 9: its recent lines =====\n" +
            "2026-11-28 13:00:00.000 UTC  WARN   [NET] token=[…] from [email]\nsecond\n", f)
        assertFalse(f.contains("sekrit"))
        assertTrue(f.endsWith("\n"))
    }
}
