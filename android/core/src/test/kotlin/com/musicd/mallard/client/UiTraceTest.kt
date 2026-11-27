package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log's watch on the screens (items 116 and 121): what a smooth app never shows. */
class UiTraceTest {
    @Test fun `a screen seen for a moment between two others is a flash`() {
        val t = UiTrace()
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("home", 0))
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("home+menu", 1_000))
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("home", 2_000))
        assertEquals(listOf(UiTrace.Finding(2_016, "FLASH: home shown for 16 ms between home+menu and settings")), t.shown("settings", 2_016))
    }

    @Test fun `straight from the side menu to Settings is no flash, nor a screen kept a while`() {
        val t = UiTrace()
        t.shown("home", 0); t.shown("home+menu", 500)
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("settings", 900))
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("home", 5_000))
    }

    @Test fun `gone to and straight back is a bounce`() {
        val t = UiTrace()
        t.shown("settings", 0); t.shown("home", 1_000)
        assertEquals(listOf(UiTrace.Finding(1_050, "BOUNCE: home for 50 ms, then back to settings")), t.shown("settings", 1_050))
    }

    @Test fun `a back stack without Home at its foot, empty, or a screen twice in a row`() {
        val t = UiTrace()
        assertEquals(emptyList<UiTrace.Finding>(), t.stack(listOf("home", "settings/home"), 0))
        assertEquals("STACK: no screen at all", t.stack(emptyList(), 0).single().text)
        assertTrue(t.stack(listOf("settings/home"), 0).single().text.startsWith("STACK: Home not at the foot"))
        assertTrue(t.stack(listOf("home", "album/7", "album/7"), 0).single().text.startsWith("STACK: album/7 twice in a row"))
    }

    @Test fun `screens of one kind one over another are no fault, the same screen twice is`() {
        // The 21 October log: Settings → Setup → Away from home, and an album → its artist → another album, were each
        // called a screen twice in a row, the routes compared without their arguments.
        val t = UiTrace()
        assertEquals(emptyList<UiTrace.Finding>(), t.stack(listOf("home", "settings/home", "settings/setup", "settings/away"), 0))
        assertEquals(emptyList<UiTrace.Finding>(), t.stack(listOf("home", "album/11706", "artist", "album/11710"), 0))
    }

    @Test fun `the app's screen made again starts the watch afresh`() {
        // At 08:00:26 the app's screen was made again and "none" then "home" were shown: called a bounce, with what was
        // shown before the old screen went.
        val t = UiTrace()
        t.shown("home", 0); t.shown("home+menu", 1_000)
        t.reset()
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("none", 1_100))
        assertEquals(emptyList<UiTrace.Finding>(), t.shown("home", 1_143))
    }

    @Test fun `a wait held on purpose is never slow, nor a request the phone slept through a fault`() {
        assertTrue(UiTrace.slowAnswer("GET /api/album?offset=3", 2_500))
        assertFalse(UiTrace.slowAnswer("GET /api/album?offset=3", 400))
        // The server holds these until something changes.
        assertFalse(UiTrace.slowAnswer("GET /api/zone-state?zone=R&wait_for=4&timeout=20000", 20_000))
        assertFalse(UiTrace.slowAnswer("GET /api/phone/commands?after=9&wait=25000", 25_000))
        // Timed out long after its own timeout: the phone was asleep, not the server slow.
        assertTrue(UiTrace.slept(1_235_913, 30_000))
        assertTrue(UiTrace.slept(70_256, 35_000))
        assertFalse(UiTrace.slept(6_008, 6_000))
    }

    @Test fun `Android asking for memory back is a fault only while the app runs short on screen`() {
        assertTrue(UiTrace.memoryLow(10))
        assertTrue(UiTrace.memoryLow(15))
        // Hidden, in the background: Android's usual notes.
        for (level in listOf(5, 20, 40, 60, 80)) assertFalse(UiTrace.memoryLow(level))
    }

    @Test fun `a stall is a finding, slow frames a count by screen`() {
        val t = UiTrace()
        t.shown("home", 0)
        assertNull(t.frame(16, 10))
        assertNull(t.frame(40, 20))
        assertNull(t.frame(50, 30))
        assertEquals("JANK: a frame took 250 ms on home", t.frame(250, 40)!!.text)
        t.shown("library", 50)
        assertNull(t.frame(60, 60))
        assertEquals(mapOf("home" to 2, "library" to 1), t.slowFrames())
        assertEquals(emptyMap<String, Int>(), t.slowFrames())
    }
}
