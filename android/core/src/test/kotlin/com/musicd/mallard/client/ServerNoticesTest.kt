package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The server's notice worked out by the app as the page's server-notice.js works it out (item 112). */
class ServerNoticesTest {
    private fun of(json: String) = ServerNotices.from(JSONObject(json), Locale.UK)

    @Test fun `all well, or offline, says nothing`() {
        assertNull(of("""{"index_count":120,"sonos":{"rooms":3}}"""))
        assertNull(of("""{"offline":true,"index_count":0}"""))
        assertEquals(60_000L, ServerNotices.nextLook(null, false))
    }

    @Test fun `an empty library, by why`() {
        assertEquals("No music folder at /music. Add your library in Settings → Music folders.",
            of("""{"index_count":0,"install":"linux","music_dir_exists":false}""")!!.lines[0])
        val scanning = of("""{"index_count":0,"scan":{"running":true,"files_seen":12345}}""")!!
        assertEquals("Scanning your music folder… 12,345 files so far. Albums appear when the first pass finishes.", scanning.lines[0])
        assertFalse(scanning.error)
        assertEquals(3_000L, ServerNotices.nextLook(scanning, true))
        // Said for how the server is installed (item 389): Mandarin OS's share, or the folder itself.
        val os = of("""{"index_count":0,"install":"mandarin-os","music_dirs":["/data/Storage"],"scan":{"last":{"status":"ok"}}}""")!!
        assertEquals("No music yet. Copy it into Storage in smb://mandarin/data, or plug in a USB drive: it appears under Storage/USB, and its music here.", os.lines[0])
        assertFalse(os.error)
        assertEquals("No audio files were found in /a. Check that it holds your music and that Mandarin can read it, or add your library in Settings → Music folders.",
            of("""{"index_count":0,"install":"linux","music_dirs":["/a"],"scan":{"last":{"status":"ok"}}}""")!!.lines[0])
        assertEquals("Starting up — reading your music folder…", of("""{"index_count":0}""")!!.lines[0])
    }

    @Test fun `a music folder gone, with Forget and its question, or Remove the missing ones`() {
        val n = of("""{"index_count":9,"sonos":{"rooms":1},"scan":{"last":{"offline_dirs":[
            {"dir":"/m/a","name":"A","tracks":1200,"missing":true},
            {"dir":"/m/b","name":"B","tracks":3,"reason":"unreadable"},
            {"dir":"/m/c","name":"C","tracks":40,"reason":"vanished"}]}}}""")!!
        assertTrue(n.error)
        assertEquals(3, n.lines.size)
        assertTrue(n.lines[0].startsWith("The music folder “A” isn't there — its 1,200 tracks are kept, not removed."))
        // The way it's put back, for how the server is installed (item 389), as the page says it.
        assertTrue(n.lines[0].contains("not removed. Mount the drive or share again and they're back as they were."))
        val gone = """"scan":{"last":{"offline_dirs":[{"dir":"/m/a","name":"A","tracks":1,"missing":true}]}}"""
        assertTrue(of("""{"index_count":9,"install":"mandarin-os","sonos":{"rooms":1},$gone}""")!!.lines[0]
            .contains("not removed. Plug the drive in again and they're back as they were."))
        assertTrue(n.lines[1].startsWith("Part of the music folder “B” couldn't be read"))
        assertEquals(listOf("Forget “A”", "Remove the missing ones"), n.buttons)
        assertEquals(listOf("/m/a", "/m/c"), n.dirs)
        assertEquals(listOf("A", "C"), n.names)
        assertEquals("Forget “A”? Its 1,200 tracks and their albums leave the library (they come back with a rescan if the folder does).", n.ask(0))
        assertEquals(30_000L, ServerNotices.nextLook(n, false))
        // Away from home, a folder gone isn't the phone's to say.
        assertNull(of("""{"index_count":9,"away":true,"sonos":{"rooms":0},"scan":{"last":{"offline_dirs":[{"dir":"/m/a","name":"A","tracks":1}]}}}"""))
        assertEquals("Removed 1,200 tracks from “A”", ServerNotices.forgotten(1200, "A", Locale.UK))
    }

    @Test fun `no rooms, unless still looking or away`() {
        val n = of("""{"index_count":9,"install":"linux","sonos":{"rooms":0,"error":"no route"}}""")!!
        assertEquals("No Sonos rooms found yet. Speakers on the same network as Mandarin appear by themselves; if yours don't, set SONOS_HOSTS to a speaker's IP. (no route)", n.lines[0])
        assertEquals("No Sonos rooms found yet. Speakers on the same network as Mandarin appear by themselves; if yours don't, set SONOS_HOSTS to a speaker's IP.",
            of("""{"index_count":9,"install":"mandarin-os","sonos":{"rooms":0}}""")!!.lines[0])
        assertEquals(8_000L, ServerNotices.nextLook(n, false))
        assertNull(of("""{"index_count":9,"sonos":{"rooms":0,"searching":true}}"""))
        assertNull(of("""{"index_count":9,"away":true,"sonos":{"rooms":0}}"""))
    }
}
