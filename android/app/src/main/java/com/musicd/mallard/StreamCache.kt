package com.musicd.mallard

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * This phone playing: tracks kept on the phone ahead of time (Mandarin's StreamCache; PhoneAhead fetches into it, and
 * com.musicd.mallard.client.CachePlan says which). One cache for the app's process, in the app's own storage. Each track
 * is kept under its LMS id and form ("t12:orig", "t12:opus"). When the space chosen in Settings → Away from home → This
 * phone runs out, the tracks played longest ago go first; nothing else removes them, so a track played again is here.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private const val TAG = "StreamCache"
    private const val GB = 1024L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null
    private val evictor = LruEvictor()

    @Synchronized
    fun get(c: Context): SimpleCache {
        cache?.let { return it }
        val app = c.applicationContext
        evictor.maxBytes = Store.cacheGb(app) * GB
        val dir = File(app.filesDir, "stream-cache")
        return SimpleCache(dir, evictor, StandaloneDatabaseProvider(app)).also { cache = it }
    }

    /** The size limit changed in Settings: applied now (the oldest go if it shrank). */
    fun setLimit(c: Context, gb: Int) {
        evictor.maxBytes = gb * GB
        runCatching { evictor.trim(get(c)) }
    }

    /** A track wholly on the phone under [key]. */
    fun complete(c: Context, key: String): Boolean = runCatching {
        val sc = get(c)
        val len = ContentMetadata.getContentLength(sc.getContentMetadata(key))
        len > 0 && sc.isCached(key, 0, len)
    }.getOrDefault(false)

    fun usedBytes(c: Context): Long = runCatching { get(c).cacheSpace }.getOrDefault(0L)

    /** Everything removed (tracks being written just now are left to finish). */
    fun clear(c: Context) {
        val sc = runCatching { get(c) }.getOrNull() ?: return
        for (k in sc.keys.toList()) runCatching { sc.removeResource(k) }.onFailure { Log.i(TAG, "kept $k: ${it.message}") }
    }

    /**
     * Least recently used first, like Media3's own, but with a limit that can
     * change while the cache is open (Settings).
     */
    private class LruEvictor : CacheEvictor {
        @Volatile var maxBytes = 2 * GB
        private var size = 0L
        private val spans = TreeSet<CacheSpan> { a, b ->
            val t = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
            if (t != 0) t else a.compareTo(b)
        }

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C_LENGTH_UNSET) evict(cache, length)
        }

        @Synchronized override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans.add(span); size += span.length
            evict(cache, 0)
        }
        @Synchronized override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (spans.remove(span)) size -= span.length
        }
        @Synchronized override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
        }

        fun trim(cache: Cache) = evict(cache, 0)

        @Synchronized private fun evict(cache: Cache, needed: Long) {
            while (size + needed > maxBytes && spans.isNotEmpty()) {
                val oldest = spans.first()
                runCatching { cache.removeSpan(oldest) }
                // Not taken out (already gone from the cache): forget it, or this never ends.
                if (spans.isNotEmpty() && spans.first() === oldest && spans.remove(oldest)) size -= oldest.length
            }
        }

        companion object { const val C_LENGTH_UNSET = -1L }
    }
}
