package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/*
 * Record labels (the web app's Labels screen, public/app.js; routes in index.js over lib/labels.js): every label the
 * server's scan found, each a tile (its logo, else its name in words), its albums, logos chosen from Discogs or pasted,
 * and labels merged into one. The words are the page's.
 */

/** A label as the list has it: [key] its identity, [mergedFrom] the labels merged into it. */
data class LabelEntry(val key: String, val title: String, val subtitle: String, val albumCount: Int, val logoUrl: String?, val mergedFrom: List<Pair<String, String>>) {
    companion object {
        private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
        fun parse(j: JSONObject): LabelEntry {
            val m = j.optJSONArray("mergedFrom") ?: JSONArray()
            return LabelEntry(j.optString("key"), j.optString("title"), j.optString("subtitle"), j.optInt("albumCount", 0), j.text("logo_url"),
                (0 until m.length()).mapNotNull { m.optJSONObject(it) }.map { it.optString("key") to it.optString("display") })
        }
    }
}

/** The list, and whether the scan is still at it ([progress] 0..1). */
data class LabelList(val labels: List<LabelEntry>, val scanning: Boolean, val progress: Double) {
    /** The labels with at least [min] albums (Settings → Labels → Minimum albums per label). */
    fun atLeast(min: Int): List<LabelEntry> = labels.filter { it.albumCount >= min }

    /** What the screen says with none to show, as the page says it. */
    fun empty(): String = when {
        scanning && progress > 0 -> "Scanning for record labels… ${(progress * 100).toInt()}% complete."
        scanning -> "Building library index…"
        else -> "No labels found yet — the background scan looks up labels via iTunes and MusicBrainz. This can take a few minutes for large libraries."
    }

    companion object {
        fun parse(j: JSONObject) = LabelList((j.optJSONArray("labels") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(LabelEntry::parse) },
            j.optBoolean("scanning"), j.optDouble("progress", 0.0))
    }
}

/** A logo found on Discogs: its picture's address and its title. */
data class LogoCandidate(val img: String, val title: String)

object LabelMerge {
    /** The merge bar's words: nothing picked, one (the target), or several. */
    fun said(picked: List<LabelEntry>): String = when (picked.size) {
        0 -> "Tap labels to select"
        1 -> "${picked[0].title} — select more to merge"
        else -> "Merge ${picked.size} into ${picked[0].title}"
    }

    /** What Merge sends: the first picked is the target, the rest merge into it. */
    fun body(picked: List<LabelEntry>): JSONObject = JSONObject().put("items", JSONArray().apply { for (l in picked) put(JSONObject().put("key", l.key).put("display", l.title)) })
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

fun ServerClient.labelList(): LabelList = LabelList.parse(getJson("/api/filters/labels", 30_000))

/** A label's albums, [order] "alpha" or "random" (Settings → Labels → Label album order). */
fun ServerClient.labelAlbums(label: String, order: String): List<AlbumSummary> {
    val a = getJson("/api/label-albums?label=" + enc(label) + "&order=" + enc(order), 30_000).optJSONArray("albums") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { runCatching { AlbumSummary.parse(it) }.getOrNull() }
}

/** The scan asked to run again: only what is missing, or ([force]) everything. Whether it started. */
fun ServerClient.labelsRescan(force: Boolean): Boolean = post(if (force) "/api/labels/rescan-force" else "/api/labels/rescan", JSONObject()).optBoolean("ok")

fun ServerClient.logoCandidates(label: String): List<LogoCandidate> {
    val a = getJson("/api/labels/logo-candidates?label=" + enc(label), 30_000).optJSONArray("candidates") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c -> c.optString("img").takeIf { it.isNotEmpty() }?.let { LogoCandidate(it, c.optString("title")) } }
}

fun ServerClient.setLabelLogo(label: String, url: String) { post("/api/labels/logo", JSONObject().put("label", label).put("url", url), 60_000) }
fun ServerClient.mergeLabels(picked: List<LabelEntry>) { post("/api/labels/merge", LabelMerge.body(picked)) }
fun ServerClient.unmergeLabel(sourceKey: String) { delete("/api/labels/merge/" + enc(sourceKey).replace("+", "%20")) }
