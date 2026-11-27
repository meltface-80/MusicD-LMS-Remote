package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shelf natively (bug list item 207), held to the page's own numbers: its library read as shelf-pick.js reads it,
 * its orders, its choices and what they say, its motion (shelf.js), and the playing record found on it. The expected
 * numbers are the page's, worked out by its own functions.
 */
class ShelfTest {
    private val answer = JSONObject("""{
      "total": 6, "sig": "abc123", "version": "1.1.11-t2",
      "genres": [{"name": "Rock", "count": 3}, {"name": "Jazz", "count": 2}],
      "labels": [{"name": "Apple", "count": 1}, {"name": "Columbia", "count": 2}],
      "kinds": [{"id": "album", "one": "Album", "many": "Albums"}, {"id": "compilation", "one": "Compilation", "many": "Compilations"}],
      "albums": [
        {"o": 1, "t": "The Wall", "a": "Pink Floyd", "k": "al-1-0", "g": [0], "b": "P", "y": 1979, "l": null, "c": null, "ty": null, "fm": "FLAC 16/44.1", "v": null},
        {"o": 2, "t": "Abbey Road", "a": "The Beatles", "k": "al-2-0", "g": [0], "b": "B", "y": 1969, "l": 0, "c": "PCS 7088", "ty": ["album"], "fm": "FLAC 24/96", "v": null},
        {"o": 3, "t": "Kind of Blue", "a": "Miles Davis", "k": null, "g": [1], "b": "M", "y": 1959, "l": 1, "c": "CL 1355", "ty": null, "fm": "MP3", "v": null},
        {"o": 4, "t": "Discovery (Deluxe Edition)", "a": "Daft Punk", "k": "al-4-0", "g": [0, 1], "b": "D", "y": 2001, "l": 1, "c": "CL 99", "ty": ["compilation"], "fm": "FLAC 16/44.1", "v": ["Discovery", "Deluxe Edition"]},
        {"o": 5, "t": "Best Of", "a": "Various", "k": null, "g": [], "b": "V", "y": null, "l": null, "c": null, "ty": ["compilation", "album"], "fm": null, "v": null},
        {"o": 6, "t": "an Album", "a": "Zed", "k": null, "g": [], "b": "Z", "y": 1975, "l": null, "c": "CL 1355", "ty": null, "fm": null, "v": null}
      ]}""")
    private val lib = ShelfLibrary.parse(answer)!!
    private fun titles(l: List<ShelfCase>) = l.map { it.title }

    @Test fun `the library is read as the page reads it, and a signature still current is the same shelf`() {
        assertEquals(listOf("Rock", "Jazz"), lib.genres.map { it.name })
        assertEquals("abc123", lib.sig)
        val d = lib.albums[3]
        assertEquals(listOf("Discovery", "Deluxe Edition", "FLAC 16/44.1", "CL 99"), listOf(d.baseTitle, d.versionText, d.format, d.catno))
        // A JSON null is none, never the word "null".
        assertEquals(listOf(null, null), listOf(lib.albums[0].catno, lib.albums[2].imageKey))
        assertNull(ShelfLibrary.parse(JSONObject("""{"same": true, "sig": "abc123"}""")))
        assertEquals("/api/shelf/albums?sig=abc123", ShelfLibrary.path("abc123"))
    }

    @Test fun `the orders file the shelf as a shop does`() {
        // A shop's order: numbers as numbers, case and accents alike (the page's Intl.Collator, numeric, base).
        val labels = listOf("WARP 123", "warp 61", "Label 10", "Label 9", "label 9b", "Ácid 2", "acid 10", "10 Records", "9 Records")
        assertEquals(listOf("9 Records", "10 Records", "Ácid 2", "acid 10", "Label 9", "label 9b", "Label 10", "warp 61", "WARP 123"), labels.sortedWith(ShelfSort::natural))
        val c = ShelfChoices()
        assertEquals(listOf("The Wall", "Abbey Road", "Kind of Blue", "Discovery (Deluxe Edition)", "Best Of", "an Album"), titles(c.list(lib)))
        // Rouen's orders (item 213: Mandarin's A → Z by album gone); a device that kept it opens by artist.
        // By label went with the labels (item 492): a device that kept it opens by artist, as one that kept A → Z by album.
        assertEquals(listOf("artist", "catno", "year", "random"), ShelfSort.ORDERS.map { it.first })
        assertEquals("artist", ShelfChoices(keptOrder = "album").order())
        assertEquals("artist", ShelfChoices(keptOrder = "label").order())
        c.toggle("order", "catno")
        assertEquals(listOf("Discovery (Deluxe Edition)", "an Album", "Kind of Blue", "Abbey Road", "Best Of", "The Wall"), titles(c.list(lib)))
        c.toggle("order", "year")
        assertEquals(listOf("Kind of Blue", "Abbey Road", "an Album", "The Wall", "Discovery (Deluxe Edition)", "Best Of"), titles(c.list(lib)))
        // Random is the page's own shuffle: the same seed, the same order; a new seed, another.
        c.toggle("order", "random", newSeed = 7)
        assertEquals(titles(ShelfSort.shuffled(lib.albums, 7)), titles(c.list(lib)))
    }

    @Test fun `the page's hash and shuffle, number for number`() {
        assertEquals(listOf(2166136261L, 2796059957L, 3125253844L), listOf(ShelfSort.hash(""), ShelfSort.hash("Kind of Blue|Miles Davis"), ShelfSort.hash("Rock")))
        val r = ShelfSort.rng(ShelfSort.hash("Kind of BlueMiles Davis"))
        val want = listOf(0.7200066824443638, 0.5934164563659579, 0.743741573067382, 0.6793527172412723)
        for (w in want) assertEquals(w, r(), 1e-15)
    }

    @Test fun `choices narrow one another, and each tile counts what it would bring`() {
        val c = ShelfChoices()
        c.toggle("genre", "Jazz")
        assertEquals(listOf("Kind of Blue", "Discovery (Deluxe Edition)"), titles(c.list(lib)))
        // Genres OR among themselves: a tile's count is what it would add, the genre's own choice left out.
        assertEquals(mapOf("Rock" to 3, "Jazz" to 2), c.counts(lib, "genre"))
        c.toggle("year", "1950")
        assertEquals(listOf("Kind of Blue"), titles(c.list(lib)))
        assertEquals(mapOf("1950" to 1, "2000" to 1), c.counts(lib, "year"))
        assertEquals(Triple("", "1", " of 6 albums on the shelf"), c.sum(lib, 1, ""))
        assertEquals(Triple("Show ", "1", " album"), c.done(1, true))
        assertTrue(c.resetShown())
        assertEquals(listOf("Jazz", "1950s"), c.chips(lib).first.map { it.text })
        // Undo takes back the last that narrowed; Reset everything, the artist order kept again where another was chosen.
        c.undo()
        assertEquals(2, c.list(lib).size)
        c.toggle("order", "year")
        assertEquals("artist", c.clearAll())
        assertEquals(6, c.list(lib).size)
        assertEquals(Triple("The whole library · ", "6", " albums"), c.sum(lib, 6, ""))
    }

    @Test fun `a kind held, kept when the library is read again, let go when it has gone`() {
        val c = ShelfChoices(keptOrder = "catno")
        assertEquals("catno", c.order())
        assertEquals("artist", ShelfChoices(keptOrder = "random").order())
        c.toggle("type", "compilation"); c.toggle("year", "2000")
        assertEquals(listOf("Discovery (Deluxe Edition)"), titles(c.list(lib)))
        c.carry(lib)
        assertEquals(listOf(1, 1), listOf(c.count("type"), c.count("year")))
        val fewer = lib.copy(albums = lib.albums.map { if ("compilation" in it.kinds) it.copy(kinds = emptyList()) else it })
        c.carry(fewer)
        assertEquals(listOf(0, 1), listOf(c.count("type"), c.count("year")))
    }

    @Test fun `the genres in the order chosen above them, #N by default, kept per device (item 213)`() {
        // the albums' genres are places in this list: Rock and Jazz where they were, two more after them
        val three = lib.copy(genres = listOf(ShelfName("Rock", 3), ShelfName("Jazz", 2), ShelfName("blues", 1), ShelfName("Zydeco", 1)))
        val c = ShelfChoices()
        assertEquals(listOf("count" to "#N", "az" to "A → Z", "za" to "Z → A"), ShelfChoices.GENRE_SORTS.map { it.first to it.second })
        assertEquals(listOf("Most albums first", "A to Z", "Z to A"), ShelfChoices.GENRE_SORTS.map { it.third })
        assertEquals("count", c.genreSort)
        // #N: as the server sends them, the most albums first; A → Z with case aside
        assertEquals(listOf("Rock", "Jazz", "blues", "Zydeco"), c.tiles(three, "genre").map { it.name })
        assertEquals("az", c.sortGenres("az"))
        assertEquals(listOf("blues", "Jazz", "Rock", "Zydeco"), c.tiles(three, "genre").map { it.name })
        assertEquals("za", c.sortGenres("za"))
        assertEquals(listOf("Zydeco", "Rock", "Jazz", "blues"), c.tiles(three, "genre").map { it.name })
        assertEquals(null, c.sortGenres("za"))   // the same again: nothing to keep
        assertEquals(null, c.sortGenres("nonsense"))
        // Each tile still its own genre: chosen by name, its own count.
        c.toggle("genre", "Jazz")
        assertEquals(listOf(false, false, true, false), c.tiles(three, "genre").map { it.chosen })
        assertEquals(listOf("Kind of Blue", "Discovery (Deluxe Edition)"), titles(c.list(three)))
        // Kept on this device: the next Shelf opens in it; one unknown, #N.
        assertEquals(listOf("blues", "Jazz", "Rock", "Zydeco"), ShelfChoices(keptGenreSort = "az").tiles(three, "genre").map { it.name })
        assertEquals("count", ShelfChoices(keptGenreSort = "sideways").genreSort)
    }

    @Test fun `the tiles and what the tabs say`() {
        val c = ShelfChoices()
        assertEquals(listOf("2000", "1970", "1960", "1950", "none"), c.decades(lib))
        assertEquals(listOf("The 2000s", "The 1970s", "The 1960s", "The 1950s", "Albums with no year"), c.tiles(lib, "year").map { it.said })
        assertEquals(listOf("album", "compilation", "none"), c.kindsHere(lib))
        assertEquals(listOf("Albums", "Compilations", "Unknown"), c.tiles(lib, "type").map { it.name })
        // a genre's tile its name and count alone, the size of a kind's (item 209)
        assertTrue(c.tiles(lib, "genre").all { it.fan.isEmpty() })
        assertEquals("Artists under #: names starting with 0, or with no letter A to Z", c.tiles(lib, "artist").first { it.id == "#" }.said)
        assertEquals(listOf(true, false), c.tiles(lib, "artist").filter { it.id in listOf("A", "B") }.map { it.zero })
        assertEquals("Tap to choose. Choose as many as you like.", c.note(lib, "genre"))
        assertEquals("How the shelf is filed. One order at a time.", c.note(lib, "order"))
        assertEquals("CL 99 · Compilation · 2001", c.lineOf(lib, lib.albums[3]))
        c.toggle("artist", "B"); c.toggle("artist", "M")
        assertTrue(c.clearShown("artist"))
        assertEquals("2 letters chosen", c.note(lib, "artist"))
        // The cards along the shelf: a new letter or decade where its run begins.
        c.clearAll(); c.toggle("order", "year")
        assertEquals(listOf("1950s", "1960s", "1970s", "", "2000s", ""), c.tabsFor(lib, c.list(lib)))
    }

    @Test fun `Formats and Favourites narrow as the other choices do, the server's tiles in its order (item 208)`() {
        val tiles = listOf("16/44.1", "24/96", "MP3", "16/44.1", null, "DSD64 2CH")
        val l = lib.copy(formats = listOf("16/44.1", "24/96", "DSD64 2CH", "MP3"),
            albums = lib.albums.mapIndexed { i, a -> a.copy(tile = tiles[i], favourite = a.offset in setOf(2L, 3L, 6L)) })
        assertEquals(listOf("16/44.1", "24/96", "DSD64 2CH", "MP3"), ShelfLibrary.parse(JSONObject(answer.toString())
            .put("formats", org.json.JSONArray(l.formats)))!!.formats)
        val c = ShelfChoices()
        assertEquals(listOf("Genres", "Artists", "Years", "Types", "Formats", "Favourites", "Order"), ShelfChoices.TABS.map { it.second })
        assertEquals(listOf("16/44.1", "24/96", "DSD64 2CH", "MP3"), c.tiles(l, "format").map { it.name })
        assertEquals(mapOf("16/44.1" to 2, "24/96" to 1, "MP3" to 1, "DSD64 2CH" to 1), c.counts(l, "format"))
        c.toggle("format", "16/44.1"); c.toggle("format", "DSD64 2CH")
        assertEquals(listOf("The Wall", "Discovery (Deluxe Edition)", "an Album"), titles(c.list(l)))
        assertEquals("2 formats chosen", c.note(l, "format"))
        // The favourites among what the formats leave.
        assertEquals(mapOf(ShelfChoices.FAVOURITE to 1), c.counts(l, "favourite"))
        c.toggle("favourite", ShelfChoices.FAVOURITE)
        assertEquals(listOf("an Album"), titles(c.list(l)))
        assertEquals(listOf("16/44.1", "DSD64 2CH", "Favourites"), c.chips(l).first.map { it.text })
        assertEquals("Only your favourites", c.note(l, "favourite"))
        assertEquals(listOf("al-2-0"), c.tiles(l, "favourite").single().fan)
        // A format the library no longer has, and the favourites all gone: let go.
        val after = l.copy(formats = listOf("16/44.1"), albums = l.albums.map { it.copy(favourite = false) })
        c.carry(after)
        assertEquals(1, c.count("format")); assertEquals(0, c.count("favourite"))
        assertEquals(emptyList<ShelfTile>(), c.tiles(after, "favourite"))
        assertEquals("No favourites yet: tap the heart on an album’s page and it is here.", c.message("favourite"))
    }

    @Test fun `nothing on the shelf says why, and offers Undo where the choices left nothing`() {
        val c = ShelfChoices()
        c.toggle("genre", "Jazz"); c.toggle("artist", "B"); c.toggle("year", "none")
        assertEquals(Triple("Nothing on this shelf", "No albums match Jazz, artists under B and no year.", true), c.empty(lib, 0))
        assertEquals(Triple("The shelf is empty", "Mandarin hasn’t found any albums in your music folders yet.", false), c.empty(lib.copy(albums = emptyList()), 0))
        assertNull(c.empty(lib, 3))
    }

    @Test fun `the stage's measures and a case's place are the page's`() {
        val phone = ShelfGeometry.of(390f, 701.03125f)
        assertEquals(listOf(203f, 602f, 43f, 65f, 52f), listOf(phone.s, phone.sh, phone.t, phone.top, phone.stepPx))
        assertEquals(5, phone.k)
        assertTrue("a phone held upright: a tall card", phone.tall)
        val tablet = ShelfGeometry.of(790.609375f, 759.078125f)
        assertEquals(listOf(435f, 648f, 47f, 74f), listOf(tablet.s, tablet.sh, tablet.t, tablet.top))
        assertEquals(104.4f, tablet.stepPx, 1e-3f)
        val want = listOf(Triple(97.41f, -51.35f, -35.306284f), Triple(129.88f, -101.5f, -90f), Triple(199.54f, -101.5f, -90f), Triple(-143.812f, -101.5f, 90f))
        listOf(0.5f, 1f, 2.5f, -1.3f).zip(want).forEach { (d, w) ->
            val pl = ShelfMotion.place(phone, d, 20)
            assertEquals(w.first, pl.x, 1e-3f); assertEquals(w.second, pl.z, 1e-3f); assertEquals(w.third, pl.ry, 1e-3f)
        }
        // Nearer the front on top; of two as near, the left.
        assertTrue(ShelfMotion.layer(5, 5f) > ShelfMotion.layer(4, 5f))
        assertTrue(ShelfMotion.layer(4, 5f) > ShelfMotion.layer(6, 5f))
    }

    @Test fun `the spring comes to rest on its album, and a swipe moves one, a flick spins`() {
        var p = 0f; var v = 0f; var rest = false; var ms = 0; var mid = 0f
        while (!rest && ms < 3000) { val r = ShelfMotion.spring(p, v, 3f, 16f); p = r.first; v = r.second; rest = r.third; ms += 16; if (ms == 160) mid = p }
        // shelf.js stepSpring, frame for frame: critically damped, 2.125 of the way at 160 ms, at rest at 832 ms
        assertEquals(2.1252f, mid, 1e-3f)
        assertEquals(832, ms)
        assertEquals(3f, p)
        val g = ShelfGeometry.of(390f, 700f)
        assertEquals(6f, ShelfMotion.release(g, 5f, 5.6f, -60f, -0.3f, 300).settle)
        assertEquals(4f, ShelfMotion.release(g, 5f, 4.4f, 60f, 0.3f, 300).settle)
        val flick = ShelfMotion.release(g, 5f, 7f, -200f, -2.5f, 250)
        assertEquals(1, flick.spinDir)
        // A short shelf (three or fewer) stands in a row and gives at its ends; a long one revolves.
        assertFalse(ShelfMotion.wraps(3))
        assertEquals(-0.3f, ShelfMotion.rubber(-1f, 3), 1e-6f)
        assertEquals(2f, ShelfMotion.settleTarget(5f, 3))
        assertEquals(-2, ShelfMotion.nearest(0, 8, 10))
    }

    @Test fun `the playing record is found by its id, else by its title and a whole name of its credit`() {
        val list = lib.albums
        assertEquals(2, ShelfPlaying.index(list, "3", "", ""))
        assertEquals(1, ShelfPlaying.index(list, null, "Abbey Road", "The Beatles & Billy Preston"))
        // Never "contains": a title only one album has is still found; a name sharing no whole name is not.
        assertEquals(0, ShelfPlaying.index(list, null, "The Wall", "Someone"))
        assertEquals(-1, ShelfPlaying.index(list.take(1) + list[0].copy(offset = 9, artist = "Other"), null, "The Wall", "Pink"))
        assertEquals(-1, ShelfPlaying.index(list, "phone:x", "", ""))
    }

    @Test fun `a set's tracks under each disc, and the chosen ones sent in album order`() {
        val rows = ShelfPlaying.rows(listOf("a", "b", "c"), listOf(1, 2, 2))
        assertEquals(listOf("Disc 1", null, "Disc 2", null, null), rows.map { it.disc })
        assertEquals(listOf(1, 1, 2), rows.filter { it.disc == null }.map { it.number })
        assertEquals(listOf(null, null), ShelfPlaying.rows(listOf("a", "b"), listOf(0, 0)).map { it.disc })
        assertEquals(listOf(5, 2, 1), ShelfPlaying.sendOrder(setOf(2, 5, 1), "play_next"))
        assertEquals(listOf("play_now", "queue"), listOf(ShelfPlaying.kindFor("play_now", 0), ShelfPlaying.kindFor("play_now", 1)))
        assertEquals("2 tracks play next in Kitchen", ShelfPlaying.tracksSaid("play_next", 2, 2, "Kitchen", ""))
        assertEquals("Playing 1 track — 1 didn’t go. A track couldn’t be played.", ShelfPlaying.tracksSaid("play_now", 1, 2, "", "A track couldn’t be played."))
        assertEquals("2 tracks · 1:05:05", ShelfPlaying.queueSum(listOf(QueueItem(1, "a", "", "", null, 3600), QueueItem(2, "b", "", "", null, 305))))
        assertEquals(ShelfZone("z2", "Study", "playing"), ShelfZone.pick(listOf(ShelfZone("z1", "Kitchen", "stopped"), ShelfZone("z2", "Study", "playing")), "gone"))
        assertEquals("z1", ShelfZone.pick(listOf(ShelfZone("z1", "Kitchen", "stopped")), "kitchen")?.id)
    }
}
