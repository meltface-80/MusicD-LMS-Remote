package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** The button before Play Now on an album's page (item 539): + for a streamed album not in your favourites there, else Mandarin's heart. */
class AlbumHeartTest {
    @Test fun `a Qobuz or Tidal album not in your favourites there is a +, whatever Mandarin's heart says`() {
        assertEquals(AlbumHeart.ADD, AlbumHeart.of("qobuz", serviceFavourite = false, favourite = false))
        assertEquals(AlbumHeart.ADD, AlbumHeart.of("tidal", serviceFavourite = false, favourite = true))
    }

    @Test fun `in your favourites there, the heart, hollow, red once liked in Mandarin`() {
        assertEquals(AlbumHeart.HEART, AlbumHeart.of("qobuz", serviceFavourite = true, favourite = false))
        assertEquals(AlbumHeart.LIKED, AlbumHeart.of("qobuz", serviceFavourite = true, favourite = true))
    }

    @Test fun `your own files, or a service not known (signed out), the heart as ever`() {
        assertEquals(AlbumHeart.HEART, AlbumHeart.of("local", serviceFavourite = null, favourite = false))
        assertEquals(AlbumHeart.LIKED, AlbumHeart.of(null, serviceFavourite = null, favourite = true))
        assertEquals(AlbumHeart.HEART, AlbumHeart.of("qobuz", serviceFavourite = null, favourite = false))
    }

    @Test fun `the service named as the page names it`() {
        assertEquals("Qobuz", AlbumHeart.serviceName("qobuz"))
        assertEquals("Tidal", AlbumHeart.serviceName("tidal"))
        assertEquals(null, AlbumHeart.serviceName("local"))
    }

    @Test fun `added to the favourites there, the note says so from now, a purchase stays one`() {
        // Item 540: the + turned to the heart, but the note went on saying "Played from Qobuz — not in your favourites".
        val played = ServiceWhy("Qobuz", kept = null, since = null, releases = null)
        assertEquals(ServiceWhy("Qobuz", kept = "favourite", since = 1234L, releases = null), played.added(1234L))
        val bought = ServiceWhy("Qobuz", kept = "purchase", since = 99L, releases = null)
        assertEquals(bought, bought.added(1234L))
    }
}
