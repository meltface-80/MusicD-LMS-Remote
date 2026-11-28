package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Mallard keeps itself up to date, as Mandarin's app does (its AppUpdate/Release): CI publishes `mallard.json` beside
 * every APK it puts on a release of this repository (.github/workflows/mallard.yml), and the app reads it.
 *
 * WHICH RELEASES: Mallard's own, by name, and never `releases/latest` — that is the SERVER's v1.0.x, which its in-app
 * updater reads; a Mallard release marked latest would offer every server install "version mallard-…". A release
 * build follows `mallard-latest` (the rolling copy of the newest Mallard release); a test build follows that AND
 * `mallard-test`, whichever is newer, so the owner testing dev is offered each new test build and, after a release,
 * the release over them.
 *
 * NEWER BY versionCode, not by name: Mallard's codes rise with every published build (mallardBase*1000 + the test
 * build's number, +999 for the release), which is the same rule Android applies before it will install over the top.
 */

/** One published Mallard: its name, its versionCode, where the APK is, its SHA-256, and its notes (null: none). */
class MallardRelease(val version: String, val code: Int, val url: String, val sha256: String, val notes: String?) {
    /** Offered over the build installed, which is [installedCode]. */
    fun newerThan(installedCode: Int): Boolean = code > installedCode

    companion object {
        const val DOWNLOADS = "https://github.com/meltface-80/MusicD-LMS-Remote/releases/download/"
        const val STABLE = "mallard-latest"
        const val TEST = "mallard-test"

        /** Where a release's manifest is. */
        fun manifestUrl(tag: String): String = "$DOWNLOADS$tag/mallard.json"

        /** A test build's name ends -tN (android/app/build.gradle.kts). */
        fun isTest(versionName: String): Boolean = Regex("-t\\d+$").containsMatchIn(versionName.trim())

        /** The releases a build follows, in the order they are asked. */
        fun tagsFor(versionName: String): List<String> = if (isTest(versionName)) listOf(TEST, STABLE) else listOf(STABLE)

        /**
         * A manifest, or null when it can't be trusted: no name, no code, a hash that isn't SHA-256, or an APK anywhere
         * but one of this repository's Mallard releases (the manifest names the file the phone will install).
         */
        fun parse(j: JSONObject?): MallardRelease? {
            if (j == null) return null
            val v = j.optString("version").trim()
            val code = j.optInt("code", 0)
            val url = j.optString("url").trim()
            val sha = j.optString("sha256").trim().lowercase()
            if (v.isEmpty() || code <= 0 || !url.startsWith(DOWNLOADS + "mallard-") || !Regex("^[0-9a-f]{64}$").matches(sha)) return null
            val notes = if (j.isNull("notes")) null else j.optString("notes").trim().takeIf { it.isNotEmpty() }
            return MallardRelease(v, code, url, sha, notes)
        }

        /** The newest of what was found (none found: null). */
        fun newest(found: List<MallardRelease?>): MallardRelease? = found.filterNotNull().maxByOrNull { it.code }

        /** Home's banner line. */
        fun offer(r: MallardRelease, installed: String): String = "Mallard v${r.version} is available (you have v$installed)"
    }
}
