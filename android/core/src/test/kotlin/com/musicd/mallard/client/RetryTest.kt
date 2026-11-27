package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryTest {
    @Test fun askedAgainLessAndLessOftenUpToHalfAMinute() {
        // Item 119: a room not answered (a Sonos room away from home) was asked for every 2 s for as long as the screen was up.
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (1..7).map { Retry.ms(it) })
        assertEquals(30_000L, Retry.ms(1000))
    }

    @Test fun `unreachable only after three looks and ten seconds both`() {
        // Item 158: refused at once while the app's Tailscale came up, three looks took three seconds and the app went offline.
        assertEquals(false, Retry.unreachable(looks = 3, elapsedMs = 3_000))
        assertEquals(false, Retry.unreachable(looks = 2, elapsedMs = 12_000))
        assertEquals(true, Retry.unreachable(looks = 3, elapsedMs = 10_000))
    }

    @Test fun `off screen and offline, the server is looked for less and less often`() {
        // Item 158: every ten seconds all night, the app off screen.
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), (1..7).map { Retry.offScreen(it) })
    }
}

