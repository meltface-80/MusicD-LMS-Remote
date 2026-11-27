package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search read as the server answers it (item 61): the library's answer, its count as the page writes it beside
 * the field, and the services' and Pitchfork's parts (their shape as test/writeups.test.js and test/qobuz.test.js hold
 * the server to; the library's own answer is the recorded one, LibraryTest).
 */
class SearchTest {
    private fun album(t: String) = JSONObject().put("offset", 1).put("title", t).put("subtitle", "A")

    @Test fun `the count beside the field, as the page words it`() {
        val one = SearchResults.parse(JSONObject().put("query", "a").put("results", org.json.JSONArray().put(album("X")))
            .put("artists", org.json.JSONArray().put(JSONObject().put("name", "Artist A").put("count", 1)))
            .put("labels", org.json.JSONArray().put(JSONObject().put("display", "Parlophone").put("albumCount", 3))))
        assertEquals("a label an older server sends is passed over (item 492)", "1 artist, 1 album", one.status())
        val many = SearchResults(  "a", List(60) { AlbumSummary.parse(album("X$it")) }, listOf("A", "B"))
        assertEquals("2 artists, 60+ albums", many.status())
        assertEquals("", SearchResults("a", emptyList(), emptyList()).status())
        assertTrue(SearchResults("a", emptyList(), emptyList()).empty)
    }

    @Test fun `the index still being built, how far, and nothing found yet`() {
        val r = SearchResults.parse(JSONObject().put("query", "a").put("results", org.json.JSONArray()).put("building", true).put("progress", 0.426))
        assertTrue(r.building)
        assertEquals("Building index… 43%", r.status())
        assertFalse(SearchResults.parse(JSONObject().put("query", "a")).building)
    }

    @Test fun `the services' and Pitchfork's parts`() {
        val qobuz = ExternalSearch.parse(JSONObject("""{"query":"artist","qobuz":[{"id":"1001","title":"Q Album","version":"Deluxe","artist":"Q Artist","image":"http://x/1001.jpg",
            "favourited":true,"quality":"24/96","hires":true}],"qobuz_artists":[{"id":"1","name":"Q Artist","image":null}],"tidal":null,"tidal_artists":null,"pitchfork":[]}"""))
        assertEquals(listOf(ServiceAlbum("qobuz", "1001", "Q Album", "Deluxe", "Q Artist", "http://x/1001.jpg", true)), qobuz.qobuz)
        assertEquals(listOf(ServiceArtist("qobuz", "1", "Q Artist", null)), qobuz.qobuzArtists)
        assertNull("not signed in to Tidal: no section", qobuz.tidal)
        assertEquals(2, qobuz.count("qobuz"))
        assertEquals(0, qobuz.count("tidal"))
        val pf = ExternalSearch.parse(JSONObject("""{"query":"album one","qobuz":null,"qobuz_artists":null,"tidal":null,"tidal_artists":null,"pitchfork":[
            {"url":"https://pitchfork.com/reviews/albums/artist-a-album-one/","album":"Album One","artist":"Artist A","cover":"https://media.pitchfork.com/a.jpg","score":null,"isBestNewMusic":false,"date":"Wed"},
            {"url":"https://pitchfork.com/reviews/albums/artist-b-hi-res/","album":"Hi Res","artist":"Artist B","cover":null,"score":9.1,"isBestNewMusic":true,"date":"2026-10-01"}]}"""))
        assertEquals(listOf(null, "9.1"), pf.pitchfork.map { it.scoreText })
        assertEquals(listOf(false, true), pf.pitchfork.map { it.bestNew })
        assertNull(pf.pitchfork[1].cover)
        assertEquals(2, pf.count("pitchfork"))
        // Handed to the page's magazine as its own item.
        val back = JSONObject(pf.pitchfork[1].json())
        assertEquals(listOf("https://pitchfork.com/reviews/albums/artist-b-hi-res/", 9.1, true), listOf(back.getString("url"), back.getDouble("score"), back.getBoolean("isBestNewMusic")))
        assertEquals(listOf("qobuz", "tidal", "pitchfork"), ExternalSearch.PARTS)
    }

    @Test fun `the search's route, and the client's paths`() {
        assertEquals("search?q=Artist%20A&zone=z", ShellRoute.search("Artist A", "z"))
        assertEquals("search?q={q}&zone={zone}", ShellRoute.SEARCH)
        val asked = ArrayList<String>()
        val c = LibraryClient { asked += it; JSONObject("""{"query":"x","pitchfork":[]}""") }
        c.searchExternal("a b", "qobuz")
        assertEquals(listOf("/api/search/external?q=a%20b"), asked)
    }
}
