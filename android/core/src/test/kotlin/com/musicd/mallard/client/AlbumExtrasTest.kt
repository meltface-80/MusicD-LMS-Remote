package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AlbumExtrasTest {
    @Test fun `the date as the page writes it, in the phone's language`() {
        assertEquals("20 November 2006", ReleaseDate.format("2006-11-20", Locale.UK))
        assertEquals("November 20, 2006", ReleaseDate.format("2006-11-20", Locale.US))
        assertEquals("November 2006", ReleaseDate.format("2006-11", Locale.UK))
        assertEquals("2006", ReleaseDate.format("2006", Locale.UK))
        assertEquals("no such day: the year", "2026", ReleaseDate.format("2026-13-40", Locale.UK))
        assertEquals("", ReleaseDate.format("soon", Locale.UK))
        assertEquals("", ReleaseDate.format(null, Locale.UK))
    }

    @Test fun `the extras read, date, score`() {
        val e = AlbumExtras.parse(JSONObject("""{"release_date": "2006-11-20", "year": "2006", "album": {"label": "RCA", "score": 8, "isBestNewMusic": true}}"""))
        assertEquals("20 November 2006", e.date(Locale.UK))
        assertEquals("8.0", e.scoreText)
        assertTrue(e.bestNewMusic)
        val bare = AlbumExtras.parse(JSONObject("""{"album": {"year": 1997}}"""))
        assertEquals("the year when that is all", "1997", bare.date(Locale.UK))
        assertNull(bare.scoreText); assertFalse(bare.bestNewMusic)
        assertEquals("7.4", AlbumExtras(null, null, 7.4, false).scoreText)
        // A field the server has as null is none (Android's org.json would read it as the word "null").
        val unlabelled = AlbumExtras.parse(JSONObject("""{"release_date": null, "year": null, "album": {"label": null, "year": 2024, "score": null}}"""))
        assertNull(unlabelled.releaseDate); assertNull(unlabelled.scoreText)
        assertEquals("2024", unlabelled.date(Locale.UK))
        assertEquals("", AlbumExtras.parse(JSONObject("{}")).date(Locale.UK))
    }

    @Test fun `the notes under the head, and the list an album was opened from`() {
        val a = Album.parse(JSONObject("""{"album": {"offset": 3, "title": "Disc 2", "subtitle": "Artist A", "box": {"name": "The Box", "disc": 2, "of": 3},
            "service_why": {"name": "Qobuz", "kept": "purchase", "since": 1709596800000}},
            "names_from_folders": true, "box_albums": [{"offset": 9, "title": "Disc 3", "subtitle": "Artist A", "box": {"name": "The Box", "disc": 3, "of": 3}},
            {"offset": 1, "title": "Disc 1", "subtitle": "Artist A", "box": {"name": "The Box", "disc": 1, "of": 3}}]}"""))
        assertTrue(a.namesFromFolders)
        assertEquals("From The Box · disc 2 of 3", a.album.box!!.text)
        assertEquals("the discs in order, this one among them", listOf(1L, 3L, 9L), a.boxDiscs.map { it.offset })
        assertEquals("In your library as a Qobuz purchase since 5 Mar 2024", a.serviceWhy!!.text(Locale.UK))
        assertEquals("Played from Tidal — not in your favourites", ServiceWhy.parse(JSONObject("""{"name": "Tidal", "kept": false}""")).text(Locale.UK))
        assertEquals("Played from Tidal — not in your favourites · not out until 20 November 2026", ServiceWhy.parse(JSONObject("""{"name": "Tidal", "kept": null, "releases": "2026-11-20"}""")).text(Locale.UK))
        assertEquals("In your library as a Qobuz favourite", ServiceWhy.parse(JSONObject("""{"name": "Qobuz", "kept": true}""")).text(Locale.UK))
        val nav = NavList.parse("""{"items": [{"offset": 3, "title": "Album One"}, {"title": "no offset"}, {"offset": 7, "title": "Album Two"}], "index": 1}""")!!
        assertEquals(listOf(NavItem(3, "Album One"), NavItem(7, "Album Two")), nav.items)
        assertEquals(1, nav.index)
        assertNull("one album is no list", NavList.parse("""{"items": [{"offset": 3, "title": "x"}], "index": 0}"""))
        assertNull(NavList.parse("not json")); assertNull(NavList.parse(null))
    }

    @Test fun `a streamed album's service, its id there and its favourite`() {
        val q = AlbumSummary.parse(JSONObject("""{"offset": 3, "title": "x", "source": "qobuz", "service_id": "q123", "service_favourite": true}"""))
        assertEquals(listOf("Qobuz", "q123", true), listOf(q.serviceName, q.serviceId, q.serviceFavourite))
        val own = AlbumSummary.parse(JSONObject("""{"offset": 3, "title": "x", "source": null}"""))
        assertNull(own.serviceName); assertNull(own.serviceId); assertNull("not signed in: unknown", own.serviceFavourite)
        assertNull(AlbumSummary.parse(JSONObject("""{"offset": 3, "title": "x", "source": "tidal", "service_favourite": "yes"}""")).serviceFavourite)
    }

    @Test fun `an album's booklets, and the book's words`() {
        val b = Booklet.list(JSONObject("""{"booklets": [{"id": "Booklet.pdf", "name": "Booklet", "pages": 12}, {"id": "", "name": "x", "pages": 1}, {"id": "Scans/Inlay.pdf", "name": "Inlay", "pages": 1}]}"""))
        assertEquals(listOf("Booklet · 12 pages", "Inlay · 1 page"), b.map { it.text })
        assertEquals(listOf("Booklet", "2 booklets"), listOf(Booklet.label(1), Booklet.label(2)))
        assertEquals(emptyList<Booklet>(), Booklet.list(JSONObject("{}")))
    }

    @Test fun `About this album, the write-up, whose words, and the link`() {
        val pf = AlbumExtras.parse(JSONObject("""{"album": {"description": "A record.", "description_source": "Wikipedia", "description_url": "https://w/x",
            "source": "Pitchfork", "url": "https://p/x", "score": 8}}""")).review!!
        assertEquals(listOf("A record.", "Read the full review on Pitchfork", "From Wikipedia", "https://p/x"), listOf(pf.text, pf.linkText, pf.attribution, pf.url))
        val q = AlbumExtras.parse(JSONObject("""{"album": {"description": "Notes.", "description_source": "Qobuz", "source": "Qobuz", "url": "https://q/x"}}""")).review!!
        assertEquals("the same source: no attribution", listOf("View on Qobuz", null), listOf(q.linkText, q.attribution))
        assertEquals("a link alone is a section", "View on Qobuz", AlbumExtras.parse(JSONObject("""{"album": {"source": "Qobuz", "url": "https://q/x"}}""")).review!!.linkText)
        assertNull("nothing to show", AlbumExtras.parse(JSONObject("""{"album": {"label": "RCA", "description": null, "url": null}}""")).review)
        assertNull(AlbumExtras.parse(JSONObject("{}")).review)
    }

    @Test fun `the album's length, and its marks`() {
        val t = listOf(Track(1, "a", "", 183.4, null), Track(2, "b", "", 2638.0, null))
        assertEquals("47:01", albumLength(t))
        assertEquals("", albumLength(emptyList()))
        val a = AlbumSummary.parse(JSONObject("""{"offset": 3, "title": "Album One", "favourite": true, "hires": true}"""))
        assertEquals(listOf(true, false, true), listOf(a.favourite, a.later, a.hires))
        assertEquals(listOf("", "0:03"), Track(5, "Song 1", "", 3.0, null).copy(subtitle = "0:03").let { listOf(it.sub, it.lengthText) })
        assertEquals(listOf("Artist B · Live", "4:05"), Track(5, "x", "Artist B · Live · 4:05", 245.0, null).let { listOf(it.sub, it.lengthText) })
        assertEquals("no length: all of it", listOf("Artist B", ""), Track(5, "x", "Artist B", 0.0, null).let { listOf(it.sub, it.lengthText) })
    }
}
