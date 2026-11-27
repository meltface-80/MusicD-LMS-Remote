package com.musicd.mallard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.MallardRelease
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Mallard keeps itself up to date: it reads the `mallard.json` CI publishes beside each APK (core's [MallardRelease]
 * says which releases, and what is newer), offers a newer build on Home and in Settings → System, and Update hands the
 * APK's link to the browser, which downloads it and installs it over the top (the same signing key every time, so
 * nothing is uninstalled and the app's settings stay).
 *
 * THE APP NEVER INSTALLS AN APK ITSELF (no REQUEST_INSTALL_PACKAGES, no download-and-launch-the-installer). Mallard
 * 0.2.0-t32 to t41 did, as Mandarin's app does, and Google Play Protect blocked every one of them as a "Harmful app",
 * even signed with the owner's private key: a new, sideloaded app with no install history that fetches and installs
 * APKs is the pattern its dropper model blocks, where Mandarin's years of installs vouch for it. The browser has that
 * permission already, and the download is then the browser's, scanned as any download is.
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    private const val CHECK_EVERY_MS = 60L * 60 * 1000

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lookedAt = 0L
    @Volatile private var looking = false

    /** The newest Mallard as last looked up (null: not looked, couldn't, or none published). */
    val latest = mutableStateOf<MallardRelease?>(null)

    /** Whether [latest] is newer than this build. */
    fun available(): Boolean = latest.value?.newerThan(BuildConfig.VERSION_CODE) == true

    /** Look for a newer build (at most once an hour unless [force]); [done] on the main thread with whether it could. */
    fun lookup(force: Boolean, done: (Boolean) -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (looking || (!force && lookedAt > 0 && now - lookedAt < CHECK_EVERY_MS)) { main.post { done(true) }; return }
        looking = true
        work.execute {
            val found = MallardRelease.tagsFor(BuildConfig.VERSION_NAME).map { tag ->
                runCatching { read(MallardRelease.manifestUrl(tag)) }.onFailure { Log.i(TAG, "no manifest on $tag: ${it.message}") }
            }
            val ok = found.any { it.isSuccess }
            val newest = MallardRelease.newest(found.map { it.getOrNull() })
            main.post {
                if (ok) { latest.value = newest; lookedAt = System.currentTimeMillis() }
                looking = false
                done(ok)
            }
        }
    }

    /** [a] closed or destroyed (the phone turned while it downloaded): no dialog, no installer, from it. */
    private fun gone(a: Activity) = a.isFinishing || a.isDestroyed

    /** Update: the newer build's APK opened in the browser, which downloads it and offers to install it. */
    fun installNow(activity: Activity) {
        if (gone(activity)) return
        val r = latest.value
        if (r != null && r.newerThan(BuildConfig.VERSION_CODE)) { openInBrowser(activity, r); return }
        lookup(true) { ok ->
            if (gone(activity)) return@lookup
            val n = latest.value
            if (n != null && n.newerThan(BuildConfig.VERSION_CODE)) openInBrowser(activity, n)
            else toast(activity, if (!ok) "Couldn't reach GitHub to look for an update" else "Mallard is up to date (v${BuildConfig.VERSION_NAME})")
        }
    }

    private fun openInBrowser(activity: Activity, r: MallardRelease) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { toast(activity, "Downloading Mallard ${r.version} — open it when it's done to install") }
            .onFailure { toast(activity, "Couldn't open the browser: " + Failure.said(it, "no answer")) }
    }

    /** A release's manifest; a release with none yet (no stable Mallard published) is no release, not a failure. */
    private fun read(url: String): MallardRelease? {
        val c = open(url)
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return MallardRelease.parse(JSONObject(c.inputStream.bufferedReader().readText()))
        } finally {
            c.disconnect()
        }
    }

    /** GitHub answers releases/download with a redirect to its file host, which HttpURLConnection follows (https → https). */
    private fun open(url: String, readTimeout: Int = 15_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            this.readTimeout = readTimeout
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Mallard/${BuildConfig.VERSION_NAME}")
        }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
}
