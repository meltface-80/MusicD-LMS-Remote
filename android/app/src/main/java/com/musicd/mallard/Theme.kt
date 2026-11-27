package com.musicd.mallard

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette

/*
 * Mallard's colours and type, Mandarin's own (the Mandarin build's shell): the page's graphite and
 * brass (Palette, held to style.css by ShellTest) and its faces, Manrope and Young Serif, as Material 3's theme.
 * Nothing is drawn here of its own; NativeShell.kt wraps the screens in it.
 */

/** One of the page's colours (Palette, ARGB) as Compose draws it. */
internal fun paletteColor(argb: Long): Color = Color(argb.toInt())

/**
 * The page's type: Manrope (public/fonts/manrope.woff2, the hifi palette's
 * `--font-sans`), as TrueType fonts in res/font, one a weight (Regular to
 * ExtraBold), each a static instance of the page's variable font written by
 * fontTools' instancer. Static, not the variable file itself: that file's
 * default instance is ExtraLight (200), and the app drew every weight with
 * it, the whole screen light (bug list item 40).
 */
private val manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.W400), Font(R.font.manrope_medium, FontWeight.W500), Font(R.font.manrope_semibold, FontWeight.W600),
    Font(R.font.manrope_bold, FontWeight.W700), Font(R.font.manrope_extrabold, FontWeight.W800)
)

/**
 * The page's display face (public/fonts/young-serif.woff2, the hifi palette's `--font-display`): a grid screen's
 * heading, Home's greeting and a settings pane's title are set in it, at weight 400 (style.css, "Titles in the
 * serif; the rest in Manrope"); the same font as TrueType in res/font, written by fontTools from the page's own file.
 */
internal val youngSerif = FontFamily(Font(R.font.young_serif, FontWeight.W400))

/**
 * Material's type scale in Manrope, with no letter-spacing: the page sets none unless it says so (its kickers and
 * tabs set their own, and so do the app's), where Material's body line puts half a pixel between every two glyphs,
 * which made a tile's title a twentieth wider than the page's (bug list item 54).
 */
private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.ours() = copy(fontFamily = manrope, letterSpacing = 0.sp)
    return Typography(
        displayLarge = t.displayLarge.ours(), displayMedium = t.displayMedium.ours(), displaySmall = t.displaySmall.ours(),
        headlineLarge = t.headlineLarge.ours(), headlineMedium = t.headlineMedium.ours(), headlineSmall = t.headlineSmall.ours(),
        titleLarge = t.titleLarge.ours(), titleMedium = t.titleMedium.ours(), titleSmall = t.titleSmall.ours(),
        bodyLarge = t.bodyLarge.ours(), bodyMedium = t.bodyMedium.ours(), bodySmall = t.bodySmall.ours(),
        labelLarge = t.labelLarge.ours(), labelMedium = t.labelMedium.ours(), labelSmall = t.labelSmall.ours()
    )
}

/**
 * Material 3 in the page's graphite and brass (Palette, held to style.css by ShellTest), and its type. A list pulled past
 * its top or its foot doesn't stretch (v1.0.7, the page's overscroll-behavior: none): Android's own stretch is off for
 * every screen, as it was for the WebView (bug list item 111).
 */
@Composable
internal fun MallardTheme(content: @Composable () -> Unit) {
    val c = ::paletteColor
    CompositionLocalProvider(LocalOverscrollFactory provides null) { MaterialTheme(
        typography = typography(),
        colorScheme = darkColorScheme(
            primary = c(Palette.ACCENT), onPrimary = c(Palette.ON_ACCENT),
            secondary = c(Palette.ACCENT_TEXT), onSecondary = c(Palette.ON_ACCENT),
            background = c(Palette.BG), onBackground = c(Palette.TEXT),
            surface = c(Palette.BG), onSurface = c(Palette.TEXT),
            surfaceVariant = c(Palette.BG_ELEV), onSurfaceVariant = c(Palette.TEXT_DIM),
            outline = c(Palette.BORDER), outlineVariant = c(Palette.BORDER),
            error = c(Palette.DANGER)
        ),
        content = content
    ) }
}
