package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette

/*
 * Plugins are a work in progress (owner: "perhaps mark as beta"): a small brass "Beta" pill beside their names — the side
 * menu's Apps and Radio, Settings → LMS server → Plugins — and a line saying so at the top of their screens. The web
 * app's .beta-tag is the same pill.
 */

/** The pill: "BETA" in small capitals, brass on a brass wash. */
@Composable
internal fun BetaTag(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Text("BETA", modifier.background(paletteColor(Palette.ACCENT_SOFT), shape).border(1.dp, paletteColor(Palette.ACCENT), shape)
        .padding(horizontal = 6.dp, vertical = 1.dp), color = paletteColor(Palette.ACCENT_TEXT), fontSize = 10.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.W700, letterSpacing = 0.08.em)
}

/** The line at the top of a plugin screen: the pill, and why. */
@Composable
internal fun BetaNote(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BetaTag()
        Text("Plugins are still being worked on — some may not behave yet.", color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}
