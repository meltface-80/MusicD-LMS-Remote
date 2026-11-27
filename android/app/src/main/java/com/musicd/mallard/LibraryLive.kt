package com.musicd.mallard

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.AlbumsMark
import com.musicd.mallard.client.Retry
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The server's albums followed while the app is on screen (bug list item 232, [AlbumsMark]): the mark of which albums
 * there are waited on, as a room is ([followRoom]), and when it moves the Library wall held is marked behind and
 * [moves] goes up, so the screens on show read their albums again (the wall at its top, Home's Library row) and the
 * rest when next opened. An album copied into a watched folder is on the wall sorted by Recently added within a moment
 * of its scan, nothing touched. Not while offline: the phone's own answers don't move under the app.
 */
internal object LibraryLive {
    /** How many times the albums have moved while the app ran: what the screens read their albums again on. */
    var moves by mutableIntStateOf(0)
        private set
    private val mark = AlbumsMark()

    /** The albums moved: the wall held behind, the artists' pages kept asked afresh when next open (item 357), and the screens told. */
    fun moved() {
        LibraryWall.fallBehind()
        ArtistPages.kept.stale()
        moves++
    }

    /** The mark followed, from the shell, while the app is on screen and the server answers. */
    @Composable
    fun Follow(client: () -> ServerClient?) {
        val onScreen by NativeShell.onScreen
        val offline by OfflineNow.kind
        LaunchedEffect(onScreen, offline) {
            if (!onScreen || offline != null) return@LaunchedEffect
            var missed = 0
            while (isActive) {
                val c = client()
                val had = mark.last
                val t0 = SystemClock.elapsedRealtime()
                val got = if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.libraryMark(had) }.getOrNull() }
                // Not answered (the server away, or one from before the mark): asked again less and less often, as a room.
                if (got == null) { delay(Retry.ms(++missed)); continue }
                missed = 0
                val took = SystemClock.elapsedRealtime() - t0
                val went = mark.seen(got)
                if (went) { delay(AlbumsMark.GATHER_MS); moved() }
                delay(AlbumsMark.pauseMs(had != null, went, took))
            }
        }
    }
}
