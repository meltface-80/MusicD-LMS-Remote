package com.musicd.mallard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.Retry
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The server's albums followed while the app is on screen: MusicD LMS Remote's album count (/api/library-stats) looked
 * at once a minute, and when it moves (a rescan finished, albums merged) the Library wall held is marked behind and
 * [moves] goes up, so the screens on show read their albums again (the wall at its top, Home's Library row) and the
 * rest when next opened. The server can't be asked to wait for a change, so this is a slow look, never a fast poll.
 */
internal object LibraryLive {
    /** How many times the albums have moved while the app ran: what the screens read their albums again on. */
    var moves by mutableIntStateOf(0)
        private set
    private var last: Int? = null

    /** The albums moved: the wall held behind, the artists' pages kept asked afresh when next open, and the screens told. */
    fun moved() {
        LibraryWall.fallBehind()
        ArtistPages.kept.stale()
        moves++
    }

    /** The count followed, from the shell, while the app is on screen and the server answers. */
    @Composable
    fun Follow(client: () -> ServerClient?) {
        val onScreen by NativeShell.onScreen
        LaunchedEffect(onScreen) {
            if (!onScreen) return@LaunchedEffect
            var missed = 0
            while (isActive) {
                val c = client()
                val got = if (c == null) null else withContext(Dispatchers.IO) { runCatching { c.getJson("/api/library-stats").optInt("albums", -1) }.getOrNull() }
                if (got == null || got < 0) { delay(Retry.ms(++missed).coerceAtLeast(60_000)); continue }
                missed = 0
                val had = last
                last = got
                if (had != null && had != got) moved()
                delay(60_000)
            }
        }
    }
}
