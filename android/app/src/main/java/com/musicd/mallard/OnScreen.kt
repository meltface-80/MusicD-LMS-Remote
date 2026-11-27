package com.musicd.mallard

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Until the app is on screen ([NativeShell.onScreen]): a pane's look again and again waits here while it isn't (bug list
 * item 158: with the player keeping the app alive, Settings → Folders read the phone's folders every second and a half
 * all night, Downloads every two seconds).
 */
internal suspend fun untilOnScreen() {
    snapshotFlow { NativeShell.onScreen.value }.first { it }
}
