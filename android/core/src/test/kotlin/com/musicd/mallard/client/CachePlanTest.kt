package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePlanTest {
    @Test fun theChoices() {
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35, 40, 45), CachePlan.WIFI_CHOICES)
        assertEquals(20, CachePlan.pick(0, CachePlan.WIFI_CHOICES, CachePlan.WIFI_DEFAULT))
        assertEquals(35, CachePlan.pick(35, CachePlan.WIFI_CHOICES, CachePlan.WIFI_DEFAULT))
        assertEquals(10, CachePlan.pick(50, CachePlan.MOBILE_CHOICES, CachePlan.MOBILE_DEFAULT))
        assertEquals(listOf(2, 4, 6, 8, 10), CachePlan.SIZE_CHOICES_GB)
    }

    @Test fun theNextTracksNotYetCachedNearestFirst() {
        val upcoming = listOf("a", "b", "c", "d", "e", "f")
        val cached = setOf("b", "d")
        assertEquals(listOf("a", "c", "e"), CachePlan.toFetch(upcoming, 5) { it in cached })
        assertEquals(listOf("a"), CachePlan.toFetch(upcoming, 2) { it in cached })
        assertEquals(emptyList<String>(), CachePlan.toFetch(upcoming, 0) { false })
    }

    @Test fun thePlayingTrackFirst() {
        // The player left waiting for data: the tracks ahead wait, whatever is held.
        assertTrue(CachePlan.aheadWaits(false, true, true, 60_000))
        assertTrue(CachePlan.aheadWaits(false, true, false, 0))
        // Still fetching what plays, short of 30 s held: they wait; at 30 s, they go.
        assertTrue(CachePlan.aheadWaits(true, false, true, 29_999))
        assertFalse(CachePlan.aheadWaits(true, false, true, 30_000))
        // On their way: they carry on down to 10 s held, then stop.
        assertFalse(CachePlan.aheadWaits(false, false, true, 10_000))
        assertTrue(CachePlan.aheadWaits(false, false, true, 9_999))
        // The player fetching nothing (all it needs held, or nothing playing): they go.
        assertFalse(CachePlan.aheadWaits(true, false, false, 0))
        assertFalse(CachePlan.aheadWaits(false, false, false, 0))
    }

    @Test fun downloadsAndRepeatsAreLeftOut() {
        // null: a streaming service's track — nothing to fetch. "a" twice: repeat one.
        assertEquals(listOf("a", "c"), CachePlan.toFetch(listOf("a", null, "a", "c"), 4) { false })
    }

    @Test fun keysAndHowMany() {
        assertEquals("t12:opus", CachePlan.key("12", true))
        assertEquals("t12:orig", CachePlan.key("12", false))
        assertEquals(10, CachePlan.count(metered = true, wifi = 20, mobile = 10))
        assertEquals(20, CachePlan.count(metered = false, wifi = 20, mobile = 10))
    }
}
