package com.musicd.mallard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.musicd.mallard.client.Failure
import com.musicd.mallard.client.MallardRelease
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Mallard keeps itself up to date, as Mandarin's app does: it reads the `mallard.json` CI publishes beside each APK
 * (core's [MallardRelease] says which releases, and what is newer), and when there is a newer build it fetches it,
 * checks its SHA-256 and hands it to Android's installer, which installs it over the top — the same signing key every
 * time, so nothing is uninstalled and the app's settings stay.
 *
 * Home's banner (NoticeBanners) and Settings → System ask here; [latest] is Compose state, so both repaint on an answer.
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    private const val CHECK_EVERY_MS = 60L * 60 * 1000

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
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

    /** Update: fetch the newer build and hand it to Android's installer; looked for first if need be. */
    fun installNow(activity: Activity) {
        if (gone(activity)) return
        val r = latest.value
        if (r != null && r.newerThan(BuildConfig.VERSION_CODE)) { download(activity, r); return }
        lookup(true) { ok ->
            if (gone(activity)) return@lookup
            val n = latest.value
            if (n != null && n.newerThan(BuildConfig.VERSION_CODE)) download(activity, n)
            else toast(activity, if (!ok) "Couldn't reach GitHub to look for an update" else "Mallard is up to date (v${BuildConfig.VERSION_NAME})")
        }
    }

    private fun apkFile(c: Context, r: MallardRelease) = File(File(c.cacheDir, "updates").apply { mkdirs() }, "mallard-${r.code}.apk")

    private fun download(activity: Activity, r: MallardRelease) {
        if (busy) return
        val dest = apkFile(activity, r)
        // An earlier download of this build: checked off the screen thread (hashing megabytes on it froze the app),
        // then installed — or fetched again.
        if (dest.exists()) {
            busy = true
            work.execute {
                val ok = runCatching { sha256(dest) == r.sha256 }.getOrDefault(false)
                if (!ok) dest.delete()
                main.post {
                    busy = false
                    if (gone(activity)) return@post
                    if (ok) install(activity, dest) else download(activity, r)
                }
            }
            return
        }
        val progress = AlertDialog.Builder(activity)
            .setTitle("Updating Mallard")
            .setMessage("Downloading version ${r.version}…")
            .setCancelable(false)
            .show()
        busy = true
        work.execute {
            val result = runCatching {
                dest.parentFile?.listFiles()?.forEach { if (it != dest) it.delete() }   // only the newest is kept
                fetch(r.url, dest) { pct -> main.post { progress.setMessage("Downloading version ${r.version}… $pct%") } }
                if (sha256(dest) != r.sha256) {
                    dest.delete()
                    throw IOException("the download was damaged — try again")
                }
            }
            main.post {
                busy = false
                runCatching { progress.dismiss() }
                if (gone(activity)) return@post
                result.onSuccess { install(activity, dest) }
                    .onFailure { toast(activity, "Couldn't download the update: " + Failure.said(it, "no answer")) }
            }
        }
    }

    /** Hand the APK to Android's installer — which asks, once, to allow installs from Mallard. */
    private fun install(activity: Activity, apk: File) {
        if (gone(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow Mallard to install updates")
                .setMessage("Android asks once: turn on \"Allow from this source\" for Mallard, come back, and tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".shares", apk)
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(activity, "Couldn't open the installer: " + Failure.said(it, "no answer")) }
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

    private fun fetch(url: String, dest: File, onProgress: (Int) -> Unit) {
        val c = open(url, readTimeout = 60_000)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var got = 0L
            var shown = -1
            c.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != shown) { shown = pct; onProgress(pct) }
                        }
                    }
                }
            }
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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
}
