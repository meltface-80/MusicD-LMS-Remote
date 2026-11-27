package com.musicd.mallard.client

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RouteTest {
    private val home = "http://192.168.1.10:3500"
    private val away = "http://100.101.102.103:3500"

    @Test fun awayOffWifiWhateverAnswers() {
        assertTrue(Route.away(onWifi = false, homeAnswers = true))
        assertTrue(Route.away(onWifi = false, homeAnswers = false))
        assertTrue(Route.away(onWifi = true, homeAnswers = false))
        assertFalse(Route.away(onWifi = true, homeAnswers = true))
    }

    @Test fun homeThroughAnotherVpnOffWifi() {
        // Mobile data with a VPN into the home network: the home address answers, so home.
        assertFalse(Route.away(onWifi = false, homeAnswers = true, onVpn = true))
        // A VPN that doesn't reach home: away, as before.
        assertTrue(Route.away(onWifi = false, homeAnswers = false, onVpn = true))
    }

    @Test fun awayOnTheOwnTailscaleOnceSignedInToItUpOrNot() {
        // Off Wi-Fi, signed in to MusicD's own Tailscale: its address (127.0.0.1:34500), even before the engine is up, and
        // never the Tailscale app's (item 118: a stall left the app on the Tailscale app's address, which nothing answered).
        assertEquals(Route.Way(away = true, viaEngine = true), Route.way(onWifi = false, homeAnswers = false, awayAddress = true, ownSignedIn = true))
        // Not signed in to it: the server's Tailscale address, through the Tailscale app.
        assertEquals(Route.Way(away = true, viaEngine = false), Route.way(onWifi = false, homeAnswers = false, awayAddress = true, ownSignedIn = false))
        // Home: neither.
        assertEquals(Route.Way(away = false, viaEngine = false), Route.way(onWifi = true, homeAnswers = true, awayAddress = true, ownSignedIn = true))
        // No address away to reach the server on: home's (and offline once it doesn't answer).
        assertEquals(Route.Way(away = false, viaEngine = false), Route.way(onWifi = false, homeAnswers = false, awayAddress = false, ownSignedIn = true))
        // Another VPN into home, off Wi-Fi: home.
        assertEquals(Route.Way(away = false, viaEngine = false), Route.way(onWifi = false, homeAnswers = true, onVpn = true, awayAddress = true, ownSignedIn = true))
    }

    @Test fun awayTheScreensPlayOnThePhone() {
        // Away, the server offers only this phone to play on (item 119): the screens are in its room, not in a Sonos
        // room the server refuses ("Away from home, only this phone plays") every time it is asked.
        assertEquals("PHONE_ab", Route.room(away = true, kept = "RINCON_1", phone = "PHONE_ab"))
        // Home: the room kept.
        assertEquals("RINCON_1", Route.room(away = false, kept = "RINCON_1", phone = "PHONE_ab"))
        // No phone room known yet (no hello): the room kept; none kept, none.
        assertEquals("RINCON_1", Route.room(away = true, kept = "RINCON_1", phone = null))
        assertEquals("", Route.room(away = false, kept = null, phone = "PHONE_ab"))
    }

    @Test fun streamsGoAwayAsOpus() {
        val u = "$home/stream/t12.flac?s=abcdefghijklmnopqrstuv"
        assertEquals("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv&q=opus", Route.rewrite(u, home, away, true))
        assertEquals(u, Route.rewrite(u, home, away, false))
        // Original chosen for away: moved, but no Opus.
        assertEquals("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv", Route.rewrite(u, home, away, true, opus = false))
        // Back home: lossless again.
        assertEquals(u, Route.rewrite("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv&q=opus", home, away, false))
        // Already away: not asked twice.
        val a = "$away/stream/t12.flac?s=abcdefghijklmnopqrstuv&q=opus"
        assertEquals(a, Route.rewrite(a, home, away, true))
    }

    @Test fun aTrackKeepsTheFormatItStartedIn() {
        val u = "$home/stream/t12.flac?s=abcdefghijklmnopqrstuv"
        // Started away as Opus, home again mid-track: the rest is still Opus (from home).
        assertEquals("$u&q=opus", Route.rewrite(u, home, away, false, keepOpus = true))
        // Started at home as the original, away mid-track: the rest is the original (from away).
        assertEquals("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv", Route.rewrite(u, home, away, true, opus = false))
    }

    @Test fun artMovesButStaysArt() {
        val u = "$home/api/image/al-3-0?size=600&s=abcdefghijklmnopqrstuv"
        assertEquals("$away/api/image/al-3-0?size=600&s=abcdefghijklmnopqrstuv", Route.rewrite(u, home, away, true))
    }

    @Test fun otherAddressesAndNoAwayAreLeftAlone() {
        val other = "https://is1-ssl.mzstatic.com/image/x.jpg"
        assertEquals(other, Route.rewrite(other, home, away, true))
        val u = "$home/stream/t1.flac?s=x"
        assertEquals(u, Route.rewrite(u, home, null, true))
    }

    @Test fun aTrackQueuedOverTheEngineComesFromHomeOnceHome() {
        // Item 404: away over MusicD's own Tailscale the queue's tracks are on its port; home again (the way no longer
        // over the engine, the away address the Tailscale one) they went on through the engine, the phone's player
        // playing over Tailscale at home until Android ended the app for the engine's CPU.
        val engine = "http://127.0.0.1:34500"
        val queued = "$engine/stream/t12.flac?s=abcdefghijklmnopqrstuv&q=opus"
        val (homeAway, homeOthers) = Route.awayBases(viaEngine = false, engine = engine, tailnet = away)
        assertEquals("$home/stream/t12.flac?s=abcdefghijklmnopqrstuv", Route.rewrite(queued, home, homeAway, false, others = homeOthers))
        // Away again over the engine: a track queued at home, or on the Tailscale address, comes over the engine.
        val (onEngine, engineOthers) = Route.awayBases(viaEngine = true, engine = engine, tailnet = away)
        assertEquals(engine, onEngine)
        assertEquals(queued, Route.rewrite("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv", home, onEngine, true, others = engineOthers))
        // Away over the Tailscale app: the engine's port is moved to the Tailscale address.
        assertEquals("$away/stream/t12.flac?s=abcdefghijklmnopqrstuv&q=opus", Route.rewrite(queued, home, homeAway, true, others = homeOthers))
        // No address away known: the engine's port is still this server's.
        assertEquals(engine to listOf(engine), Route.awayBases(viaEngine = true, engine = engine, tailnet = null))
    }

    @Test fun addressesFromTheServer() {
        assertEquals(ServerAddress("100.101.102.103", 3500), ServerAddress.fromUrl("http://100.101.102.103:3500"))
        val ts = ServerAddress.fromUrl("https://musicd.tail1234.ts.net")!!
        assertEquals(443, ts.port)
        assertEquals("https://musicd.tail1234.ts.net:443", ts.baseUrl)
        assertEquals(80, ServerAddress.fromUrl("http://musicd")!!.port)
        assertNull(ServerAddress.fromUrl("musicd:3500"))
        assertNull(ServerAddress.fromUrl(""))
    }

    @Test fun `signed in to the own Tailscale by its state or by the app's memory of joining`() {
        // Item 157: the state file not found, the app went to the Tailscale app's address for twelve minutes, the engine never started.
        assertTrue(Route.ownSignedIn(installed = true, stateFile = false, joined = true))
        assertTrue(Route.ownSignedIn(installed = true, stateFile = true, joined = false))
        assertFalse(Route.ownSignedIn(installed = true, stateFile = false, joined = false))
        assertFalse(Route.ownSignedIn(installed = false, stateFile = true, joined = true))
    }

    @Test fun `home on its Wi-Fi is left only after three missed looks`() {
        // Item 157: one health check slower than 1.5 s (the server reading its library) sent the phone away, at home.
        assertEquals(3, Route.looksBeforeAway(wasAway = false, onWifi = true))
        assertEquals(1, Route.looksBeforeAway(wasAway = true, onWifi = true))    // coming home: one answer is enough
        assertEquals(1, Route.looksBeforeAway(wasAway = false, onWifi = false))  // the Wi-Fi gone: away at once
    }

    @Test fun `the engine is started afresh at most once in three minutes`() {
        assertTrue(Route.mayRestartEngine(now = 1_000_000, last = 0))
        assertFalse(Route.mayRestartEngine(now = 1_000_000, last = 1_000_000 - 60_000))
        assertTrue(Route.mayRestartEngine(now = 1_000_000, last = 1_000_000 - 180_000))
    }

    /**
     * Item 226: away over MusicD's own Tailscale, nothing is sent to 127.0.0.1:34500 until this app's engine forwards it;
     * another app's Tailscale held that port on 30 Oct, and the phone's player said hello to its server forty times.
     */
    @Test fun overTheEngineOnlyWhileItsOwnEngineForwards() {
        assertEquals("waiting for MusicD's Tailscale to connect", Route.notYet(away = true, viaEngine = true, engineForwarding = false, onVpn = false))
        assertEquals("waiting for MusicD's Tailscale to connect", Route.notYet(away = true, viaEngine = true, engineForwarding = false, onVpn = true))
        assertNull(Route.notYet(away = true, viaEngine = true, engineForwarding = true, onVpn = false))
        // Over the Tailscale app: its VPN, as before; at home, always.
        assertEquals("waiting for Tailscale to connect", Route.notYet(away = true, viaEngine = false, engineForwarding = false, onVpn = false))
        assertNull(Route.notYet(away = true, viaEngine = false, engineForwarding = false, onVpn = true))
        assertNull(Route.notYet(away = false, viaEngine = false, engineForwarding = false, onVpn = false))
        assertNull(Route.notYet(away = false, viaEngine = true, engineForwarding = false, onVpn = false))
    }
}

