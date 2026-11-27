package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who an act is on their page (item 131): the facts line, members and bands, the albums credited on (no links). */
class ArtistAboutTest {
    private val band = ArtistAbout.parse(JSONObject("""{"name":"The Smiths","key":"smiths","known":true,"mbid":"40f5d9e4","type":"Group",
        "photo_key":"u-aHR0cHM6Ly9leGFtcGxlLmNvbS9zLmpwZw","begin":"1982","end":"1987-08","ended":true,"area":"Manchester",
        "members":[{"name":"Johnny Marr","begin":"1982","end":"1987","ended":true,"roles":["guitar","keyboard"],"photo_key":null,"in_library":true},
                   {"name":"Craig Gannon","begin":"1986","end":null,"ended":true,"roles":[],"photo_key":null,"in_library":false}],
        "member_of":[],"credited_on":[{"offset":7,"title":"Hatful of Hollow","subtitle":"The Smiths","roles":["performer"]},
                                      {"offset":9,"title":"Rank","subtitle":"The Smiths","roles":["producer"]}],"in_library":true,
        "band_albums":[{"band":"The Smiths","albums":[{"offset":11,"title":"Meat Is Murder","subtitle":"The Smiths"}]},{"band":"Empty","albums":[]}],
        "genres":["Indie Rock","Jangle Pop"]}"""))

    @Test fun `what they are, where from and when, on one line`() {
        assertEquals("Band · Manchester · 1982–1987", band.facts)
        assertEquals("born 1959", ArtistAbout.years("1959-05-22", null, false, "Person"))
        assertEquals("1946–2016", ArtistAbout.years("1946", "2016-01-10", true, "Person"))
        assertEquals("since 2001", ArtistAbout.years("2001", null, false, "Group"))
        assertEquals("1970–", ArtistAbout.years("1970", null, true, "Group"))
        assertNull(ArtistAbout.years(null, null, false, "Group"))
        val person = band.copy(type = "Person", area = null, begin = "1963", end = null, ended = false)
        assertEquals("born 1963", person.facts)
        assertNull(band.copy(type = null, area = null, begin = null, end = null).facts)
    }

    @Test fun `members with what they played and when, and albums credited on beyond their own`() {
        assertTrue(band.known)
        assertEquals(listOf("Johnny Marr", "Craig Gannon"), band.members.map { it.name })
        assertEquals("guitar, keyboard · 1982–1987", band.members[0].line)
        assertEquals("1986–", band.members[1].line)
        assertTrue(band.members[0].inLibrary)
        assertFalse(band.members[1].inLibrary)
        assertEquals("u-aHR0cHM6Ly9leGFtcGxlLmNvbS9zLmpwZw", band.photoKey)
        assertEquals(listOf("Rank"), band.creditedBeyond(setOf(7L)).map { it.album.title })
        assertEquals(listOf("producer"), band.creditedBeyond(setOf(7L))[0].roles)
        // Their bands' albums from their years in them (item 133), a band with none left out.
        assertEquals(listOf("The Smiths"), band.bandAlbums.map { it.band })
        assertEquals(listOf("Meat Is Murder"), band.bandAlbums[0].albums.map { it.title })
        // Their genres, the most used first (item 139).
        assertEquals(listOf("Indie Rock", "Jangle Pop"), band.genres)
        val unknown = ArtistAbout.parse(JSONObject("""{"name":"Nobody","known":false,"members":[],"member_of":[],"credited_on":[]}"""))
        assertFalse(unknown.known)
        assertNull(unknown.facts)
        // Still being looked up as the act its albums are by (item 341): asked again; said or not, it is not pending.
        assertFalse(unknown.pending)
        assertTrue(ArtistAbout.parse(JSONObject("""{"name":"Airbag","known":false,"pending":true}""")).pending)
    }
}
