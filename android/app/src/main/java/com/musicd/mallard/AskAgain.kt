package com.musicd.mallard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.musicd.mallard.client.Retry
import kotlinx.coroutines.delay

/**
 * The server reached again, told to the screens (bug list item 157): [epoch] goes up whenever the screens go to the
 * server (a start, the way to it changed, back online), whenever it answers, and whenever the app comes back on
 * screen. A screen whose load failed asks again then ([AskAgain]). On the main thread.
 */
internal object Reach {
    var epoch by mutableIntStateOf(0)
        private set

    fun bump() { epoch++ }
}

/**
 * A screen's load that [failed] (not reached, or the server's own trouble: [com.musicd.mallard.client.Failure.worthAgain])
 * asked again with [ask]: at once when the server is reached again ([Reach.epoch]), else after a while — 2 s, doubled
 * up to half a minute — for as long as it fails and the screen is up. Item 157: the Library said "Couldn't load" until
 * it was opened again, though the server had answered a moment after.
 */
@Composable
internal fun AskAgain(failed: Boolean, ask: () -> Unit) {
    val epoch = Reach.epoch
    var seen by remember { mutableIntStateOf(epoch) }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(failed, epoch) {
        if (!failed) { tries = 0; seen = epoch; return@LaunchedEffect }
        if (epoch != seen) { seen = epoch; ask() }
        while (true) { delay(Retry.ms(++tries)); ask() }
    }
}
