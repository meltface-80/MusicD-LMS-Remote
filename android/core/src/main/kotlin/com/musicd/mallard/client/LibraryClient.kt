package com.musicd.mallard.client

import org.json.JSONObject
import java.net.URLEncoder

/**
 * The library's calls, typed (stage N1 of old/plan-android-native.md):
 * what the native screens will ask, each answer as its model (Library.kt).
 * [get] is the server's GET of a path, its answer as JSON; a [ServerClient]'s
 * by default, the recorded answers in the tests (LibraryTest).
 */
class LibraryClient(private val get: (String) -> JSONObject) {
    constructor(server: ServerClient) : this({ path -> server.getJson(path) })

    /**
     * A page of the library's albums, as the Library wall lists them: [count] from [offset], in the wall's [view]
     * (the page's query string: the sort and its direction, the focus; "" for the library as it is).
     */
    fun albums(offset: Int = 0, count: Int = 60, view: String = ""): AlbumPage =
        AlbumPage.parse(get("/api/library/albums" + (if (offset > 0) "?offset=$offset&count=$count" else "?count=$count") + (if (view.isEmpty()) "" else "&$view")))

    /** An album's page, by its [offset] (the id every list gives it). A 404 means the offset has moved on. */
    fun album(offset: Long): Album = Album.parse(get("/api/album?offset=$offset"), offset)

    /** What its page shows under its artists: year, label, Pitchfork score and link, a review from LMS's own plugin. */
    fun albumExtras(title: String, artist: String): AlbumExtras = AlbumExtras.parse(get("/api/album/extras?title=" + enc(title) + "&artist=" + enc(artist)))
    /** What the share card says (ShareCard.kt), from the same answer. */
    fun shareCard(title: String, artist: String): CardFields = CardFields.parse(get("/api/album/extras?title=" + enc(title) + "&artist=" + enc(artist)))

    /** An artist's albums: their own, and those they are on. */
    fun artistAlbums(artist: String): ArtistAlbums {
        val (name, _) = ArtistAct.parse(artist)
        return ArtistAlbums.parse(get("/api/artist-albums?artist=" + enc(name)))
    }

    fun search(query: String): SearchResults = SearchResults.parse(get("/api/search?q=" + enc(query) + "&limit=60"))

    /** The search beyond the library: Pitchfork's reviews (the server's Qobuz catalogue search is left to the page). */
    fun searchExternal(query: String, part: String): ExternalSearch {
        // The server's Qobuz results are replayable menu tokens, not albums with ids: only the reviews are taken.
        val j = get("/api/search/external?q=" + enc(query))
        return ExternalSearch.parse(JSONObject().put("query", j.optString("query", query)).put("pitchfork", j.optJSONArray("pitchfork") ?: org.json.JSONArray()))
    }

    /** Home's rows in the owner's order, each on or off. */
    fun homeRows(): List<HomeRow> = HomeRows.parse(get("/api/settings/home-rows"))

    /** Recently played: the albums played in the last 30 days, the latest first. */
    fun recentlyPlayed(): AlbumRow = AlbumRow.parse(get("/api/home/history?count=24"))

    /** Not played: a random handful of the albums not played for six months. */
    fun notPlayed(): AlbumRow = AlbumRow.parse(get("/api/home/unplayed?months=6&count=30"))

    /** The Focus sheet's facets. */
    fun facets(): Facets = Facets.parse(get("/api/library/facets"))

    /**
     * The favourites, newest first: all of them, or the first [count] for a row. A favourite the library hasn't got
     * right now (an album since removed, a catalogue album never scanned) has no offset and can't be opened, so it is
     * left off a wall rather than shown as a tile that opens nothing.
     */
    fun favourites(count: Int? = null): AlbumRow {
        val j = get("/api/favourites")
        val a = j.optJSONArray("albums") ?: org.json.JSONArray()
        val kept = org.json.JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.isNull("offset") || !o.has("offset")) continue
            o.put("favourite", true)
            kept.put(o)
            if (count != null && kept.length() >= count) break
        }
        return AlbumRow.parse(JSONObject().put("albums", kept))
    }
    fun history(count: Int): AlbumRow = AlbumRow.parse(get("/api/home/history?count=" + count.coerceIn(1, 60)))
    fun unplayed(months: Int, count: Int): Unplayed = Unplayed.parse(get("/api/home/unplayed?months=" + months.coerceIn(1, 60) + "&count=" + count.coerceIn(1, 96)))

    /** Random albums, [count] of them (a fresh draw every time: the server keeps no set). */
    fun randomAlbums(count: Int, refresh: Boolean = false): AlbumRow =
        AlbumRow.parse(get("/api/random-albums?count=" + count.coerceIn(1, 96)))

    /** An artist's biography and photo (LMS's Music & Artist Information plugin); null when there is none. Slow cold. */
    fun artistBio(artist: String, album: String): ArtistBio? {
        val j = get("/api/artist-info?artist=" + enc(artist))
        val b = j.optJSONObject("bio") ?: return null
        val text = b.optString("text", "")
        if (text.isEmpty()) return null
        return ArtistBio(text, b.optString("attribution", "").ifEmpty { null }, j.optString("photo", "").takeIf { it.isNotEmpty() && !j.isNull("photo") })
    }

    /** Who an act is, as far as LMS's artist-information plugin knows: their photo, their members or bands. */
    fun artistAbout(artist: String): ArtistAbout {
        val (name, _) = ArtistAct.parse(artist)
        val j = get("/api/artist-info?artist=" + enc(name))
        fun stints(k: String): List<ArtistAbout.Stint> {
            val a = j.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val n = a.optJSONObject(i)?.optString("name", "") ?: a.optString(i, "")
                n.takeIf { it.isNotEmpty() }?.let { ArtistAbout.Stint(it, null, null, false, emptyList(), null, false) }
            }
        }
        val photo = j.optString("photo", "").takeIf { it.isNotEmpty() && !j.isNull("photo") }
        val members = stints("members"); val memberOf = stints("memberOf")
        return ArtistAbout(name = name, known = photo != null || members.isNotEmpty() || memberOf.isNotEmpty(), mbid = null, type = null,
            photoKey = null, disambiguation = null, begin = null, end = null, ended = false, area = null,
            members = members, memberOf = memberOf, creditedOn = emptyList(), photoUrl = photo)
    }

    /** Similar acts: LMS has no such list, so none (the page's section stays hidden). */
    fun artistSimilar(artist: String): ArtistSimilar = ArtistSimilar(null, emptyList())
    fun artistSimilarAlbums(artist: String): SimilarAlbums = SimilarAlbums(false, null, emptyList())

    fun albumOfTheDay(): AlbumOfTheDay = AlbumOfTheDay.parse(get("/api/home/album-of-the-day"))

    /** Home's other rows: today's Smart Picks; the genres, as the Browse by genre cards. */
    fun smartPicks(): SmartPicks = SmartPicks.parse(get("/api/smart-picks"))
    fun genres(): List<Genre> = Genre.list(get("/api/filters/genres"))
    fun genreGroups(): GenreGroups = GenreGroups.parse(get("/api/home/genre-groups"))
    fun genreCards(): List<GenreCard> = GenreCard.build(genres(), genreGroups())

    /** Random Album's choice, to be offered (Play now, Play next, Queue): one album not played for six months. */
    fun pickUnheard(): PickedAlbum {
        val a = get("/api/home/unplayed?months=6&count=1").optJSONArray("albums")?.optJSONObject(0)
            ?: get("/api/random-albums?count=1").optJSONArray("albums")?.optJSONObject(0)
            ?: throw java.io.IOException("No album to offer")
        return PickedAlbum(AlbumSummary.parse(a))
    }
    /** Random albums narrowed by [filter] (a genre's or a decade's wall), [count] of them. */
    fun randomAlbums(count: Int, filter: WallFilter): AlbumRow = AlbumRow.parse(get("/api/random-albums?count=" + count.coerceIn(1, 96) + filter.query()))

    /** Pitchfork's Latest Reviews or Best New Music, and the library's album a review is of. */
    fun pitchforkReviews(type: String): List<PitchforkHit> = PitchforkHit.list(get("/api/pitchfork/reviews?type=" + enc(type)))
    fun pitchforkMatch(r: PitchforkHit): Long? = PitchforkHit.match(get("/api/pitchfork/review?url=" + enc(r.url) + "&album=" + enc(r.album) + "&artist=" + enc(r.artist)))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
