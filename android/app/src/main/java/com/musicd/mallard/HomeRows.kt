package com.musicd.mallard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.GenreCard
import com.musicd.mallard.client.HomeLayoutSync
import com.musicd.mallard.client.HomeRow
import com.musicd.mallard.client.HomeRowsSettings
import com.musicd.mallard.client.LibraryClient
import com.musicd.mallard.client.LibraryView
import com.musicd.mallard.client.Pick
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Home's rows as last loaded, kept while the app runs (bug list item 57; the
 * page's home.js to home-picks.js): each row's tiles, or its one line of
 * words where it has none, loaded as the page loads them (the Not played row
 * kept five minutes once it has answered, Random new on every visit, the rest
 * asked for again on each visit; item 63) and
 * the place Home was scrolled to, so Back finds it as it was. The app's own
 * rows, Downloaded albums and Music on device, are read from the phone. The
 * screens contract's `home` (HomeScreen.kt draws them); nothing is drawn here.
 */

/** A row of albums as last loaded: its tiles, or its one line ([note]: "Loading…", "No albums.") where it has none. */
internal class RowKept {
    val albums = mutableStateListOf<AlbumSummary>()
    var note by mutableStateOf<String?>(null)
    var at = 0L
}

internal object HomeKept {
    /** Home's Random albums row: as many as a phone's random wall shows ([randomWallCount]), so the row is the wall's head (item 537). */
    const val RANDOM_ROW = 24
    /** The Not played row is kept five minutes once it has answered, as the page's (rowsTtlFresh). */
    private const val TTL_MS = 5 * 60 * 1000L
    /** The rows in the page's own order (HOME_ROWS), the app's two first. */
    val ROW_IDS = HomeRowsSettings.TITLES.keys.toList()

    /** The rows in the owner's order, each on or off (/api/settings/home-rows), the app's own at the top where the server doesn't know them. */
    val layout = mutableStateListOf<HomeRow>()
    val rows: Map<String, RowKept> = listOf("unplayed", "favourites", "history", "random", "library", "lotw").associateWith { RowKept() }
    /** Label of the week's label, for its row's title. */
    var lotwLabel by mutableStateOf<String?>(null)
    val picks = mutableStateListOf<Pick>()
    val genres = mutableStateListOf<GenreCard>()
    var genresNote by mutableStateOf<String?>(null)
    /** A row of the last visit's failed, worth asking again (item 157: Home asks again — [AskAgain] — rather than keep "Couldn’t load."). */
    var failed by mutableStateOf(false)
    /** Today's album, under the greeting, all day (item 236: it was gone once played, the space beside Random Album left empty). */
    var aotd by mutableStateOf<AlbumSummary?>(null)
    /** Where Home is scrolled to, kept for Back; [toTop] asks for its top (Home tapped on the row from another screen). */
    val list = LazyListState()
    var toTop by mutableStateOf(false)
    private var rowsAt = 0L
    /** Whether Not played has answered since the clock was last started (an empty answer counts; a failure doesn't). */
    private var unplayedLoaded = false
    private var genresLoaded = false

    /** Everything kept forgotten, for the rows to be read afresh: the app gone offline, or back online (item 109). */
    fun reset() {
        rows.values.forEach { it.albums.clear(); it.note = null; it.at = 0 }
        picks.clear(); genres.clear(); genresNote = null; aotd = null
        lotwLabel = null
        rowsAt = 0; unplayedLoaded = false; genresLoaded = false
    }

    /** Whether a row hides itself when it has nothing to show (the page's rowHidesWhenEmpty). */
    fun hidesWhenEmpty(id: String) = id in setOf("unplayed", "history", "picks", "favourites", "lotw")

    /** Whether [row] is on. */
    fun on(row: HomeRow): Boolean = row.unavailable == null && row.on

    /** Every row loaded as the page loads it on a visit: at once from what is kept, the rest behind. */
    fun refresh(scope: CoroutineScope, context: Context, client: ServerClient?, libraryView: String) {
        if (client == null) return
        failed = false
        val lib = LibraryClient { client.getJson(it) }
        val now = System.currentTimeMillis()
        // The page's rowsTtlFresh: within five minutes, Not played answered, and Random holding tiles.
        val ttlFresh = rowsAt > 0 && now - rowsAt < TTL_MS && unplayedLoaded && rows["random"]!!.albums.isNotEmpty()
        if (!ttlFresh) rowsAt = now
        // Read once the app's changes under way are made (an album just put on Listen later; item 85).
        scope.launch {
            ServerWrites.drained()
            scope.launch { layout(lib) }
            scope.launch { refreshAotd(client) }
            if (!ttlFresh) scope.launch {
                unplayedLoaded = false
                unplayedLoaded = hidingRow("unplayed", lib) { it.unplayed(6, 30).albums }
                if (!unplayedLoaded) rowsAt = 0   // asked again on the next visit, as the page's
            }
            // Random albums read on every visit: the server's one set (item 537), the first of the random wall's in its order,
            // as many as a phone's wall shows; new only on the wall's Refresh (or Android Auto's), or after a day.
            scope.launch { plainRow("random", lib) { it.randomAlbums(RANDOM_ROW).albums } }
            scope.launch { hidingRow("favourites", lib) { it.favourites(30).albums } }
            scope.launch { hidingRow("history", lib) { it.history(30).albums } }
            // The Library row in the Library's order alone: its Focus and name filter narrow the wall, never the row (the page's libSortKey).
            val order = LibraryView.parse(libraryView).sortQuery()
            scope.launch { plainRow("library", lib) { it.albums(0, 30, order).albums } }
            scope.launch { val got = withContext(Dispatchers.IO) { runCatching { lib.smartPicks().picks }.getOrNull() }; if (got != null && (got.isNotEmpty() || picks.isEmpty())) { picks.clear(); picks.addAll(got) } }
            // Read again when the other rows are (not only once an app's run, as they were): what the server says of them
            // changes, its genres in English since it said them in French; the cards shown stay until the answer comes.
            if (!genresLoaded || !ttlFresh) scope.launch { genres(lib) }
            scope.launch {
                val got = withContext(Dispatchers.IO) { runCatching { client.getJson("/api/home/label-of-the-week") }.getOrNull() }
                if (got != null) {
                    lotwLabel = got.optString("label", "").takeIf { it.isNotEmpty() && !got.isNull("label") }
                    val a = got.optJSONArray("albums")
                    val list = if (a == null || lotwLabel == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { AlbumSummary.parse(o) }.getOrNull() } }
                    rows["lotw"]!!.albums.clear(); rows["lotw"]!!.albums.addAll(list)
                }
            }
        }
    }

    /** Home's Library row alone read again, in the Library's order (item 232: the server's albums moved while Home is on screen). */
    fun libraryRow(scope: CoroutineScope, client: ServerClient?, libraryView: String) {
        if (client == null) return
        val order = LibraryView.parse(libraryView).sortQuery()
        scope.launch { plainRow("library", LibraryClient { client.getJson(it) }) { it.albums(0, 30, order).albums } }
    }

    /** Today's album, as the server says now (none only when the library has nothing to offer), kept as it was when the server can't say. */
    suspend fun refreshAotd(client: ServerClient) {
        withContext(Dispatchers.IO) { runCatching { LibraryClient { client.getJson(it) }.albumOfTheDay().album } }.onSuccess { aotd = it }
    }

    /** The layout's reads and saves, one at a time in the order asked (item 83): Home's read waits for a save under way. */
    private val layoutSync = HomeLayoutSync(ServerWrites.lane)
    /** Saves outlive the screen that asked (Settings → Home Screen left before the server answered). */
    private val saving = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The layout as the server keeps it, read after any save under way; none when it can't say. */
    suspend fun readLayout(lib: LibraryClient): List<HomeRow>? {
        val read = layoutSync.load { lib.homeRows() }
        return withContext(Dispatchers.IO) { runCatching { read.get() }.getOrNull() }
    }

    private suspend fun layout(lib: LibraryClient) {
        val got = readLayout(lib)
        // A row this phone has that the saved layout doesn't know (an older server): at the top, on, as the page does.
        setLayout(got ?: layout.toList())
    }

    /**
     * [rows] saved from Settings → Home Screen: Home laid out from them at once, the server's answer taken when it comes
     * (unless a later save has been asked for since), and [answered] told it, or what went wrong. Ask on the main thread.
     */
    fun saveLayout(client: ServerClient, rows: List<HomeRow>, answered: (Result<List<HomeRow>?>) -> Unit) {
        setLayout(rows)
        val save = layoutSync.save(rows) { client.saveSetting("home-rows", it) }
        saving.launch {
            val r = withContext(Dispatchers.IO) { runCatching { try { save.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } } }
            r.getOrNull()?.let(::setLayout)
            answered(r)
        }
    }

    /** The layout as the server keeps it ([saved]: Settings → Home Screen's answer too), made whole for this phone. */
    fun setLayout(saved: List<HomeRow>) {
        val next = HomeRowsSettings.complete(saved, ROW_IDS)
        layout.clear(); layout.addAll(next)
    }

    /** A row that hides itself when empty: its tiles, or none; what it had kept when the server can't say. True once answered. */
    private suspend fun hidingRow(id: String, lib: LibraryClient, load: (LibraryClient) -> List<AlbumSummary>): Boolean {
        val row = rows[id]!!
        val got = withContext(Dispatchers.IO) { runCatching { load(lib) } }.getOrElse { e -> if (Failure.worthAgain(e)) failed = true; return false }
        row.albums.clear(); row.albums.addAll(got); row.at = System.currentTimeMillis()
        return true
    }

    /** The Random and Library rows (the page's renderAlbumRow): "Loading…" until the first answer, "No albums." for none, the server's trouble said where there are no tiles to keep. */
    private suspend fun plainRow(id: String, lib: LibraryClient, load: (LibraryClient) -> List<AlbumSummary>) {
        val row = rows[id]!!
        if (row.albums.isEmpty()) row.note = "Loading…"
        val r = withContext(Dispatchers.IO) { runCatching { load(lib) } }
        r.onSuccess { got ->
            if (got.isNotEmpty() || row.albums.isEmpty()) { row.albums.clear(); row.albums.addAll(got); row.note = if (got.isEmpty()) "No albums." else null; row.at = System.currentTimeMillis() }
        }.onFailure { e ->
            if (row.albums.isEmpty()) row.note = if (e is ServerClient.ServerException && e.status == 503) "Reading your music library…" else "Couldn’t load."
            if (Failure.worthAgain(e)) failed = true
        }
    }

    private suspend fun genres(lib: LibraryClient) {
        if (genres.isEmpty()) genresNote = "Loading…"
        val r = withContext(Dispatchers.IO) { runCatching { lib.genreCards() } }
        r.onSuccess { cards ->
            if (cards.isNotEmpty()) { genres.clear(); genres.addAll(cards); genresNote = null; genresLoaded = true }
            else if (genres.isEmpty()) genresNote = "No genres found."
        }.onFailure { e ->
            if (genres.isEmpty()) genresNote = if (e is ServerClient.ServerException && e.status == 503) "Reading your music library…" else "Couldn’t load genres."
            if (Failure.worthAgain(e)) failed = true
        }
    }

}
