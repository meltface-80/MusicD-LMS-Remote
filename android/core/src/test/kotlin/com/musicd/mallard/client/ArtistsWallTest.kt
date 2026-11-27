package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Artists wall (item 130): its pages, orders, find and pictures. */
class ArtistsWallTest {
    @Test fun `a page asked in its order, at random with its seed, found by part of a name`() {
        assertEquals("/api/artists?sort=az&offset=0&limit=120", ArtistsWall.path("az", 0))
        assertEquals("/api/artists?sort=albums&offset=240&limit=120", ArtistsWall.path("albums", 240))
        assertEquals("/api/artists?sort=random&offset=0&limit=120&seed=7", ArtistsWall.path("random", 0, seed = 7))
        assertEquals("/api/artists?sort=az&offset=0&limit=120&q=sigur%20r%C3%B3s", ArtistsWall.path("az", 0, " sigur rós "))
        // An order it doesn't know is A to Z; never a negative offset.
        assertEquals("/api/artists?sort=az&offset=0&limit=120", ArtistsWall.path("plays", -5))
    }

    @Test fun `Sort goes round the three orders and says each`() {
        assertEquals(listOf("albums", "random", "az"), listOf("az", "albums", "random").map(ArtistsWall::nextSort))
        assertEquals("albums", ArtistsWall.nextSort("nonsense"))
        assertEquals(listOf("A–Z", "Most albums", "Random"), ArtistsWall.SORTS.map(ArtistsWall::sortWord))
    }

    @Test fun `their own picture once found, else their first album's cover`() {
        val p = ArtistPage.parse(JSONObject("""{"artists":[
            {"name":"Björk","albumCount":3,"image_key":"al-11-abc","photo_key":"u-aHR0cHM6Ly9leGFtcGxlLmNvbS9iLmpwZw"},
            {"name":"Yes","albumCount":1,"image_key":"al-9-def"}],"offset":0,"total":2}"""))
        assertEquals("u-aHR0cHM6Ly9leGFtcGxlLmNvbS9iLmpwZw", ArtistsWall.art(p.artists[0]))
        assertEquals("al-9-def", ArtistsWall.art(p.artists[1]))
        assertNull(p.artists[1].photoKey)
        assertEquals(listOf("3 albums", "1 album"), p.artists.map(ArtistsWall::subtitle))
        assertEquals("Artists · 2", ArtistsWall.heading(p.total))
        assertEquals("Artists", ArtistsWall.heading(null))
        assertTrue(ArtistsWall.more(120, 4304))
        assertFalse(ArtistsWall.more(4304, 4304))
        assertEquals("No artist matches “zz”", ArtistsWall.none(" zz "))
        assertEquals("artists?zone=z%201", ShellRoute.artists("z 1"))
    }

    @Test fun `two acts of one name, each told apart and each opened alone`() {
        // Airbag from Norway and from Argentina (item 330): two tiles, each its act's id and word.
        val p = ArtistPage.parse(JSONObject("""{"artists":[
            {"name":"Airbag","albumCount":2,"image_key":"al-1-a","artist_id":7,"note":"Norwegian progressive rock band"},
            {"name":"Airbag","albumCount":1,"image_key":"al-2-b","artist_id":9,"note":"2011"},
            {"name":"Yes","albumCount":1,"image_key":"al-3-c"}],"offset":0,"total":3}"""))
        assertEquals(listOf(7L, 9L, null), p.artists.map { it.artistId })
        assertEquals(listOf("2 albums · Norwegian progressive rock band", "1 album · 2011", "1 album"), p.artists.map(ArtistsWall::subtitle))
        // Opened with its act; a plain name is every act of it, as ever.
        val opened = p.artists.map { ArtistAct.of(it.name, it.artistId) }
        assertEquals(listOf("Airbag" to 7L, "Airbag" to 9L, "Yes" to null), opened.map(ArtistAct::parse))
        assertEquals("Yes", opened[2])
        var asked = ""
        val lib = LibraryClient { asked = it; JSONObject("""{"artist":"Airbag","primary":[],"featured":[],"note":"2011"}""") }
        assertEquals("2011", lib.artistAlbums(opened[1]).note)
        assertEquals("/api/artist-albums?artist=Airbag", asked)
        lib.artistAlbums("Yes")
        assertEquals("/api/artist-albums?artist=Yes", asked)
        // An album says whose it is, for its page's link to its artist.
        val al = AlbumSummary.parse(JSONObject("""{"offset":4,"title":"Libre","subtitle":"Airbag","artist_id":9,"artist_note":"2011"}"""))
        assertEquals(9L to "2011", al.artistId to al.artistNote)
        assertNull(AlbumSummary.parse(JSONObject("""{"offset":5,"title":"Close to the Edge","subtitle":"Yes"}""")).artistId)
    }
}
