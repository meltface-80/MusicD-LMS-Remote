package com.musicd.mallard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.AlbumCovers
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Library wall's albums as loaded (bug list item 51), a page of 60 at a
 * time as the page's wall loads them (library-wall.js), kept while the app
 * runs so the wall comes back as it was from an album's page (its place is
 * the list's own saved state); a wall in another view starts afresh.
 */
internal class WallState(val view: String, val offline: Boolean = false) {
    val albums = mutableStateListOf<AlbumSummary>()
    var total by mutableIntStateOf(0)
    /** The first page has answered (the skeletons go). */
    var loaded by mutableStateOf(false)
    /** The library's end came: a short page. */
    var done by mutableStateOf(false)
    var loading by mutableStateOf(false)
    /** What the banner says, and whether as an error (the page's setBanner); null: nothing. */
    var banner by mutableStateOf<Pair<String, Boolean>?>(null)
    /** The last page asked for failed, and is worth asking again (item 157: [AskAgain]). */
    var failed by mutableStateOf(false)
    /** Behind the server's albums (item 232, [LibraryLive]): read again once it is at its top, or opened there again. */
    var behind by mutableStateOf(false)
    /** No page yet, and more to come: the first asked for (again, after a failure) when the Library opens. */
    val needsFirst: Boolean get() = !done && albums.isEmpty()

    /** The next page, from [client]; nothing while one is in flight or the end came. */
    suspend fun more(client: ServerClient) {
        if (loading || done) return
        loading = true
        // Let go however the request ends: called off on the way (the Library left before the page came, as on mobile
        // data or a VPN), a flag still up kept the wall waiting for good, its skeletons up on every visit (item 99).
        try { page(client) } finally { loading = false }
    }

    private suspend fun page(client: ServerClient) {
        val first = albums.isEmpty()
        val r = withContext(Dispatchers.IO) { runCatching { LibraryClient { client.getJson(it) }.albums(albums.size, PAGE, view) } }
        r.onSuccess { p ->
            // An album already on the wall (the list moved between pages, as a scan adds albums) isn't added twice
            // (item 158: two tiles of one key end the grid).
            val have = albums.mapTo(HashSet()) { it.offset }
            albums.addAll(p.albums.filter { have.add(it.offset) })
            total = p.total
            done = p.albums.size < PAGE
            loaded = true
            failed = false
            // Nothing at all: as the page says it; otherwise whatever the banner said is over.
            banner = if (first && p.albums.isEmpty()) "Nothing matches this focus — try clearing a filter." to false else null
        }.onFailure { e ->
            // The library's index still being built (503): said, and asked again in a while (item 157: it used to wait
            // for the wall to be opened again).
            if (e is ServerClient.ServerException && e.status == 503) { banner = (e.message?.takeIf { !it.startsWith("HTTP") } ?: "Reading your music library…") to true; loaded = true }
            else if (first) { banner = "Couldn’t load: " + Failure.said(e, "no answer") to true; loaded = true }
            // Mid-scroll: what is loaded stays; the same page is asked again, as is a first that failed (item 157).
            failed = Failure.worthAgain(e)
        }
    }

    /** Read again from its first page (item 232), in place: the tiles there stay until the answer comes, and on a failure. */
    suspend fun again(client: ServerClient) {
        if (loading) return
        loading = true
        try {
            val r = withContext(Dispatchers.IO) { runCatching { LibraryClient { client.getJson(it) }.albums(0, PAGE, view) } }
            r.onSuccess { p ->
                albums.clear()
                albums.addAll(p.albums.distinctBy { it.offset })
                total = p.total
                done = p.albums.size < PAGE
                loaded = true
                failed = false
                behind = false
                banner = if (p.albums.isEmpty()) "Nothing matches this focus — try clearing a filter." to false else null
            }
        } finally { loading = false }
    }

    companion object { const val PAGE = 60 }
}

/** The one wall held: the last view opened. */
internal object LibraryWall {
    private var last: WallState? = null

    /**
     * The wall in [view]: the one held when it is that view's and was answered the same way ([offline]: by the phone),
     * else a new one (item 157: pages the phone answered offline stayed after the server was back, the wall stuck at the
     * downloads' count).
     */
    @Synchronized fun state(view: String, offline: Boolean = false): WallState {
        val l = last
        if (l != null && l.view == view && l.offline == offline) return l
        return WallState(view, offline).also { last = it }
    }

    /** The server's albums moved (item 232, [LibraryLive]): the wall held is behind them. */
    @Synchronized fun fallBehind() { last?.behind = true }

    /**
     * Album [offset]'s cover as its page has just read it ([imageKey], item 123): a tile showing another is put right in
     * every list kept from before, the Library wall's, the other walls' and the search's (a cover found in the editor has
     * a new key, and they went on drawing the old one for as long as the app ran).
     */
    fun coverIs(offset: Long, imageKey: String?) {
        synchronized(this) { last?.let { l -> for ((i, a) in AlbumCovers.fixes(l.albums, offset, imageKey)) l.albums[i] = a } }
        Walls.coverIs(offset, imageKey)
        SearchKept.lib?.let { r -> val f = AlbumCovers.fixes(r.albums, offset, imageKey); if (f.isNotEmpty()) SearchKept.lib = r.copy(albums = r.albums.toMutableList().also { l -> for ((i, a) in f) l[i] = a }) }
    }
}
