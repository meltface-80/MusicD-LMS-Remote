package com.musicd.mallard

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/*
 * The log's watch on the shell (bug list item 121, while logging is on; the log build's, item 116): each move with the
 * back stack after it and how long after the last tap it came, what is shown each time it changes (the screen on top,
 * the side menu or a sheet over it), and each tap with where it fell. UiTrace (core) finds in them what a smooth app
 * never does. It draws nothing of its own.
 */

private val main = android.os.Handler(android.os.Looper.getMainLooper())

/** A screen's name in the log: its route's head, Settings with its page, an album with its number. */
internal fun logName(e: NavBackStackEntry?): String {
    val route = e?.destination?.route ?: return "none"
    val head = route.substringBefore('/').substringBefore('?')
    val a = e.arguments ?: return head
    return when (head) {
        "settings" -> "settings/" + (a.getString("pane") ?: "home")
        "album" -> "album/" + a.getLong("offset")
        else -> head
    }
}

/** The moves, and what is shown, logged and watched. */
@Composable
internal fun LogTrace(nav: NavHostController) {
    DisposableEffect(nav) {
        // A pop and a move together are one move, logged once (item 159: two NAV lines, both naming where it ended).
        var queued = false
        val moved = NavController.OnDestinationChangedListener { c, _, _ ->
            val now = SystemClock.uptimeMillis()
            if (queued) return@OnDestinationChangedListener
            queued = true
            // The stack read once the move has settled: read at once, a screen closed was still in it.
            main.post {
                queued = false
                val stack = c.currentBackStack.value.filter { it.destination !is NavGraph }.map { logName(it) }
                // A move with no tap just before it: Back (a screen's own, which isn't seen), or the app itself.
                val after = AppLog.lastTap.takeIf { it > 0 && now - it < 1_500 }?.let { " (${now - it} ms after the last tap)" } ?: " (not after a tap: Back, or the app itself)"
                AppLog.line("NAV", "to ${logName(c.currentBackStackEntry)}$after; stack: " + stack.joinToString(" > "))
                AppLog.found(AppLog.trace.stack(stack, now))
            }
        }
        nav.addOnDestinationChangedListener(moved)
        onDispose { nav.removeOnDestinationChangedListener(moved) }
    }
    val entry by nav.currentBackStackEntryAsState()
    val menu by NativeShell.menuOpen
    val ask by RoomAsk.drawn
    val shown = logName(entry) + (if (menu) "+menu" else "") + (if (ask) "+move-question" else "")
    SideEffect {
        val now = SystemClock.uptimeMillis()
        val found = AppLog.trace.shown(shown, now)
        if (found.isNotEmpty() || AppLog.shownLast != shown) AppLog.line("SHOWN", shown)
        AppLog.shownLast = shown
        AppLog.found(found)
    }
}

/** Each tap on the shell logged with where it fell and on what (it takes nothing from the screens: it only looks). */
internal fun Modifier.logTaps(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val t0 = SystemClock.uptimeMillis()
        AppLog.lastTap = t0
        AppLog.line("TAP", "at ${down.position.x.toInt()},${down.position.y.toInt()} px on ${AppLog.shownLast}")
    }
}
