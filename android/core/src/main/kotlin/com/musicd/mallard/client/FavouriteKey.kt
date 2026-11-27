package com.musicd.mallard.client

import java.text.Normalizer

/**
 * A favourite's key, EXACTLY as MusicD LMS Remote makes it (lib/favourites.js keyFor, and the copy in public/app.js):
 * favourites are keyed on the album's folded title and artist, never on an offset, so they survive a rescan — and the
 * app decides which hearts paint filled by matching /api/favourites/keys against this. A difference here paints the
 * wrong hearts; FavouriteKeyTest runs the server's own table of cases.
 */
object FavouriteKey {
    private val MARKS = Regex("[\\u0300-\\u036f]")
    private val NOT_WORD = Regex("[^a-z0-9]+")
    private val SPACE = Regex("\\s+")

    /** Case, accents and punctuation all collapse: "Björk" == "Bjork", "Vol. 1" == "Vol 1". */
    fun norm(s: String?): String = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFKD)
        .replace(MARKS, "").replace(NOT_WORD, " ").replace(SPACE, " ").trim()

    /** A fold that KEEPS symbols, for a title made of nothing else ("<|°_°|>", "+", "!!!"). */
    fun symFold(s: String?): String = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFKD)
        .replace(MARKS, "").replace(SPACE, " ").trim()

    /** The key for [title] by [artist]; null for an album with no title. The symbol fallback is on the title only. */
    fun of(title: String?, artist: String?): String? {
        val t = norm(title).ifEmpty { symFold(title) }
        if (t.isEmpty()) return null
        return t + "|" + norm(artist)
    }

    /**
     * What to say once the heart is set: the favourite is the app's own and it landed; mirroring it into LMS's Favorites
     * is a second thing that can fail on its own ([lmsReason]: ok, no-address, not-connected, failed). "no-address" is
     * the expected answer for a catalogue album the library hasn't scanned, so it is information, not an error.
     */
    fun said(on: Boolean, lmsReason: String): String {
        val base = if (on) "Added to Favourites" else "Removed from Favourites"
        return when {
            !on || lmsReason.isEmpty() || lmsReason == "ok" -> base
            lmsReason == "no-address" -> "$base · not in LMS until the library has it"
            else -> "$base · not added to LMS"
        }
    }
}
