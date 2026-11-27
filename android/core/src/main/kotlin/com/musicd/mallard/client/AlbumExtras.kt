package com.musicd.mallard.client

import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What an album's page shows under its artists once the extras come
 * (/api/album/extras, as the page asks: the day looked up when only the
 * year is known): its release date, a Pitchfork score; and, under
 * the tracks, About this album ([review]).
 */
data class AlbumExtras(val releaseDate: String?, val year: String?, val score: Double?, val bestNewMusic: Boolean, val review: Review? = null) {
    /** The date line's date, as the page writes it: the day where it is known, else the month, else the year. */
    fun date(locale: Locale = Locale.getDefault()): String = ReleaseDate.format(releaseDate, locale).ifEmpty { year.orEmpty() }

    /** The score as the page writes it: "8.0", "7.4". */
    val scoreText: String? get() = score?.let { if (it % 1.0 == 0.0) "%.1f".format(Locale.ROOT, it) else it.toString() }

    companion object {
        // A field that can be null is asked whether it is, first: Android's org.json answers optString of a JSON
        // null with the word "null" (the JVM's, which the tests run on, with the default), and the album page
        // showed "6 June 2024 · null" for an album with no label (bug list item 37).
        private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k, "").ifEmpty { null }

        fun parse(j: JSONObject): AlbumExtras {
            val a = j.optJSONObject("album")
            val year = j.str("year") ?: a?.str("year") ?: a?.optInt("year", 0)?.takeIf { it > 0 }?.toString()
            return AlbumExtras(
                releaseDate = j.str("release_date"),
                year = year,
                score = a?.takeIf { it.has("score") && !it.isNull("score") }?.optDouble("score")?.takeIf { !it.isNaN() },
                bestNewMusic = a?.optBoolean("isBestNewMusic", false) ?: false,
                review = a?.let { Review.of(it.str("description"), (it.str("description_source") ?: it.str("descriptionSource")), it.str("description_url"), it.str("source"), it.str("url")) }
            )
        }
    }
}

/**
 * About this album, as the page shows it (public/app/album-extras.js): the
 * write-up ([text], whose words they are when that isn't where the link
 * goes: [textSource]), and the link to its page ([url] at [source]).
 * Pitchfork's own prose is never shown; the link is the way to read it.
 */
data class Review(val text: String?, val textSource: String?, val textSourceUrl: String?, val source: String?, val url: String?) {
    /** The link's words: "Read the full review on Pitchfork", "View on Qobuz"; none without a page to go to. */
    val linkText: String? get() = if (url == null || source == null) null else if (source == "Pitchfork") "Read the full review on Pitchfork" else "View on $source"

    /** "From Wikipedia", under the text, when its words aren't the link's page's. */
    val attribution: String? get() = if (text != null && textSource != null && textSource != source) "From $textSource" else null

    companion object {
        /** The section, or null when there is neither a write-up nor a page to link. */
        fun of(text: String?, textSource: String?, textSourceUrl: String?, source: String?, url: String?): Review? =
            if (text == null && (url == null || source == null)) null else Review(text, textSource, textSourceUrl, source, url)
    }
}

/** A release date ("2006", "2006-11", "2006-11-20") written as the page writes it, in the phone's language. */
object ReleaseDate {
    fun format(s: String?, locale: Locale): String {
        val m = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$").find(s.orEmpty()) ?: return ""
        val (y, mo, d) = m.destructured
        if (mo.isEmpty()) return y
        return runCatching {
            if (d.isEmpty()) YearMonth.of(y.toInt(), mo.toInt()).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            else LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
        }.getOrDefault(y)   // "2026-13-40" is no date: the year is still right
    }
}

/** An album's length as its page writes it: 47:01, or 1:02:15 past the hour. */
fun albumLength(tracks: List<Track>): String {
    val secs = tracks.sumOf { it.length }
    return if (secs > 0) NowPlayingView.clock(Math.round(secs).toDouble()) else ""
}
