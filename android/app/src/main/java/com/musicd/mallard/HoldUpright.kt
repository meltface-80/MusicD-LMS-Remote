package com.musicd.mallard

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.musicd.mallard.client.ScreenSize

/**
 * Held upright on a phone (a foldable folded among them), whatever its rotation setting; free to turn on a tablet or a
 * foldable opened (item 221, core's ScreenSize). Asked when a screen opens and again as the screen changes, folded or
 * opened ([config]).
 */
internal fun Activity.holdUprightOnPhones(config: Configuration = resources.configuration) {
    val want = if (ScreenSize.portraitOnly(config.smallestScreenWidthDp)) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    if (requestedOrientation != want) requestedOrientation = want
}
