package com.musicd.mallard.client

/**
 * An artist line split into its artists, each its own link, exactly as MusicD LMS Remote splits one: on " / ", "; ",
 * ", ", " & ", " + " and feat./featuring/ft. (owner decision v1.0.5 — a band name with " & " splits too, knowingly).
 * Kept in step with lib/search.js ANY_SPLIT_RE and public/app.js ARTIST_SPLIT_RE.
 */
object Artists {
    private val SPLIT = Regex(" / |; |, | & | \\+ | feat\\.? | featuring | ft\\.? ", RegexOption.IGNORE_CASE)

    fun split(line: String): List<String> = line.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The first artist of [line], the one an album's page leads with; "" for none. */
    fun lead(line: String): String = split(line).firstOrNull().orEmpty()
}
