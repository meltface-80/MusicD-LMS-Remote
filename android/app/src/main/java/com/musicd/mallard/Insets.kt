package com.musicd.mallard

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation

/**
 * Keep content out from under the status and navigation bars.
 *
 * From Android 15 a window is drawn edge to edge whatever the theme says, so
 * without this the page's top bar would sit under the clock. The window
 * background is the page's own colour, so the padded strips are invisible.
 *
 * The insets are CONSUMED here. Passed on, they reach the WebView inside,
 * which (in current versions) hands the same bar sizes to the page as CSS
 * safe-area insets — and the page, built for the iPhone home-screen app, then
 * leaves the room a second time: a band above the top buttons, the mini
 * player floating, Now playing squeezed.
 *
 * Their animations (the keyboard coming and going, the bars sliding in) are
 * kept here too (item 534). Android hands those to every view inside whatever
 * this one consumed, so the native screens' Compose took the whole bars from
 * one; never told otherwise after it (the insets stop here), it kept them, and
 * Settings, which keeps out from under the bars itself, left the status bar's
 * room a second time: its heading a band lower, until the app was closed.
 */
object Insets {
    fun pad(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets = insets
            })
        }
        view.requestApplyInsets()
    }
}
