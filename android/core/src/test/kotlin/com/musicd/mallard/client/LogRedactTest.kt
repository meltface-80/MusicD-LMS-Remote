package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's log leaves out what is the owner's alone and keeps what helps (bug list items 121, 235): each line goes
 * through LogRedact before it is written. The lines here are as the app wrote them in the owner's logs of 20 and
 * 21 October; the server's LogScrub (LogScrubTests) does the same to the same kinds of line.
 */
class LogRedactTest {
    private fun same(s: String) = assertEquals(s, LogRedact.line(s))

    @Test fun `internet and Tailscale addresses hidden, home ones kept`() {
        same("REACH  to the server at http://192.168.0.57:3500 (home)")
        same("REACH  the live look at http://127.0.0.1:34500 missed")
        same("server GET /api/health → SocketTimeoutException: failed to connect to /192.168.0.57 (port 3500) from /192.0.0.4 (port 50928) after 5000ms")
        same("route 10.2.0.0/24 via 10.2.0.1, 172.20.1.2, 239.255.255.250:1900")
        same("v6 fe80::1c2b:3aff:fe4d:5e6f%wlan0 and ::ffff:192.168.0.61")
        same("the phone at 34:7E:5C:D9:64:62")
        assertEquals("Away: away: using [tailscale]:3500", LogRedact.line("Away: away: using 100.92.159.80:3500"))
        assertEquals("v6 [tailscale] and [wan]", LogRedact.line("v6 fd7a:115c:a1e0::1 and 2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("on [tailscale]", LogRedact.line("on my-server.tail1234.ts.net"))
        assertEquals("from [wan] and http://[wan]:443/x", LogRedact.line("from 81.2.69.160 and http://81.2.69.160:443/x"))
    }

    @Test fun `keys, logins and emails hidden, rooms and devices kept`() {
        same("NET    server GET /api/zone-state?zone=RINCON_347E5CD9646201400&wait_for=22&timeout=20000 → ok in 1188 ms")
        same("the phone PHONE_L5BgOVpYfQVC")
        same("device 0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals("token=[…] and Bearer […]", LogRedact.line("token=abc123def and Bearer eyJhbGciOiJIUzI1NiJ9"))
        assertEquals("key […]", LogRedact.line("key 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        assertEquals("GET /x?album_id=0060254&user_auth_token=[…]&request_sig=[…]", LogRedact.line("GET /x?album_id=0060254&user_auth_token=AbC123xyz&request_sig=9f86d0"))
        assertEquals("by [email]", LogRedact.line("by someone@example.com"))
        assertEquals("rtsp://[…]@192.168.0.9/live", LogRedact.line("rtsp://admin:secret1@192.168.0.9/live"))
        assertEquals("[scan] /home/[user]/Music/Soen: unreadable", LogRedact.line("[scan] /home/jane/Music/Soen: unreadable"))
    }

    @Test fun `what is listened to and searched for, and files and folders, kept`() {
        same("server GET /api/album/extras?title=Battle%20Born&artist=The%20Killers&day=1 → ok in 12 ms")
        same("server GET /api/search?q=1984")
        same("FileNotFoundException: /storage/emulated/0/Music/The Killers/Battle Born/01 Flesh and Bone.flac: open failed: ENOENT")
        same("the folder content://com.android.externalstorage.documents/tree/primary%3AMusic")
        same("no album 'Battle Born' here")
        same("HAND   room RINCON_347E5CD9646201400 (Living Room)")
    }

    @Test fun `what the log is for is kept`() {
        same("10-21 08:00:18.578 !! NET    server GET /api/image/al-701-a7eea0c0eb?size=300 → SocketException: Connection reset after 660 ms")
        same("NAV    to settings/away (108 ms after the last tap); stack: home > settings/home > settings/setup")
        same("UI     JANK: a frame took 145 ms on home")
        same("SNAP   phone player: playing, track 1 of 9, at 215.404 of 324.68, volume 13, revision 90")
        same("APP    started: Mandarin 1.1.0 (327), Android 16 (API 36)")
        same("LOGCAT 10-20 23:42:23.659  8735  8735 W WindowOnBackDispatcher: sendCancelIfRunning: isInProgress=false callback=androidx.activity.OnBackPressedDispatcher\$Api34Impl\$createOnBackAnimationCallback\$1@c54ddd2")
        same("[auth] wrong password from 192.168.0.23")
    }

    @Test fun `nothing that is the owner's alone is left in a whole log`() {
        val out = SAMPLE.lines().joinToString("\n") { LogRedact.line(it) }
        for (bad in listOf("100.92.", "81.2.69", "ts.net", "someone@", "AbC123xyz", "jane"))
            assertFalse("$bad left in", out.contains(bad))
        for (good in listOf("192.168.0.57", "10.2.0.2", "RINCON_347E", "PHONE_L5B", "Killers", "Mostly%20Autumn"))
            assertTrue("$good taken out", out.contains(good))
    }

    private companion object {
        val SAMPLE = """
            10-20 23:42:32.190    NET    server GET /api/album/extras?title=Battle%20Born&artist=The%20Killers&day=1 → ok in 12 ms
            10-20 23:43:38.819 !! NET    server GET /api/lastfm/similar-albums?artist=Mostly%20Autumn → ok in 4246 ms
            10-21 00:14:52.077 !! NET    server GET /api/phone/commands?after=9&wait=25000 → SocketTimeoutException: failed to connect to /192.168.0.57 (port 3500) from /10.2.0.2 (port 56048) after 5000ms after 5006 ms
                                   LOGCAT 10-21 00:15:10.117  8735  8830 I Away    : away: using 100.92.159.80:3500 on my-server.tail1234.ts.net
            10-21 00:16:01.000    NET    qobuz sign-in by someone@example.com from 81.2.69.160, user_auth_token=AbC123xyz
            10-21 00:17:00.000    SCAN   /home/jane/Music unreadable
            10-20 23:54:04.126    SNAP   rooms: 1, last RINCON_347E5CD9646201400
            10-21 00:15:22.195    NET    phone GET /api/zone-state?zone=PHONE_L5BgOVpYfQVC → ok in 15 ms
        """.trimIndent()
    }
}
