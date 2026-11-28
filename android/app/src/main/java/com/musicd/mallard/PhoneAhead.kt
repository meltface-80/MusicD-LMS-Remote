package com.musicd.mallard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import com.musicd.mallard.client.CachePlan
import com.musicd.mallard.client.PhoneStream
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.phoneAhead
import java.util.concurrent.Executors

/**
 * This phone's tracks kept ahead (Mandarin's fetchAhead, owner request "add caching like Mandarin has"): the next tracks
 * of its queue — 20 on Wi-Fi, 10 on mobile data unless chosen — fetched into the phone's cache (StreamCache) by their LMS
 * id, in the form the phone plays them where it is now (the file, or away its Opus), so they play without the network:
 * through a dead spot, or on mobile data without using any. LMS hands its player only one track ahead, so the queue
 * ahead is asked of the server (/api/phone/ahead). The playing track first (Mandarin's v0.8.17): the tracks ahead wait
 * while the player is short of what it plays ([CachePlan.aheadWaits]).
 */
@OptIn(UnstableApi::class)
internal class PhoneAhead(
    private val c: Context, private val player: ExoPlayer, private val cache: CacheDataSource.Factory,
    private val client: () -> ServerClient?, private val playerId: String, private val opus: () -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ahead").apply { isDaemon = true } }
    @Volatile private var gen = 0
    @Volatile private var writer: CacheWriter? = null
    @Volatile private var waits = true
    @Volatile private var running = true

    /** The player's state, looked at on the main thread each second while fetching: whether the tracks ahead wait. */
    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            waits = CachePlan.aheadWaits(waits, player.playbackState == Player.STATE_BUFFERING, player.isLoading, player.totalBufferedDuration)
            main.postDelayed(this, 1000)
        }
    }

    /** The queue moved on, or a track was added: looked at again in a moment (once, however often asked). */
    fun soon() { if (running) { main.removeCallbacks(go); main.postDelayed(go, 2_000) } }

    fun stop() { running = false; gen++; writer?.cancel(); main.removeCallbacks(go); main.removeCallbacks(watch); fetcher.shutdownNow() }

    private fun metered() = runCatching { c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    private val go = Runnable {
        if (!running) return@Runnable
        val g = ++gen
        writer?.cancel()
        val asOpus = opus()
        val count = CachePlan.count(metered(), Store.cacheWifi(c), Store.cacheMobile(c))
        main.removeCallbacks(watch); watch.run()
        fetcher.execute {
            val ids = runCatching { client()?.phoneAhead(playerId, count) ?: emptyList() }.getOrElse { AppLog.line("CACHE", "the queue ahead didn't come: ${it.message}"); return@execute }
            val todo = CachePlan.toFetch(ids.map { CachePlan.key(it, asOpus) }, count) { StreamCache.complete(c, it) }
            var done = 0
            for (key in todo) {
                val id = key.removePrefix("t").substringBefore(':')
                while (waits) { if (g != gen || !running) return@execute; try { Thread.sleep(500) } catch (e: InterruptedException) { return@execute } }
                if (g != gen || !running) return@execute
                val w = CacheWriter(cache.createDataSource(), DataSpec.Builder().setUri(Uri.parse(PhoneStream.filePath(id, asOpus))).setKey(key).build(), null, null)
                writer = w
                try { w.cache(); done++ } catch (e: Exception) {
                    if (g == gen) { AppLog.line("CACHE", "couldn't fetch ahead: ${e.message}"); main.postDelayed({ if (g == gen) soon() }, 30_000) }
                    break
                } finally { if (writer === w) writer = null }
            }
            if (done > 0) AppLog.line("CACHE", "kept $done track(s) ahead on the phone" + if (asOpus) " as Opus" else "")
            last = Status(ids.size, ids.count { StreamCache.complete(c, CachePlan.key(it, asOpus)) }, metered())
        }
    }

    /** What the cache holds of the queue ahead, for Settings: wanted, on the phone, on mobile data. */
    data class Status(val wanted: Int, val have: Int, val metered: Boolean)

    companion object { @Volatile var last: Status? = null }
}
