package com.musicd.mallard

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import com.musicd.mallard.client.CoverDisk
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The covers a wall's tiles show, kept once fetched (bug list item 51): the
 * last 48 MB of them by their key and size (not their address: home or away
 * it's the same cover, item 158), so a wall scrolled back to, or come back
 * to from an album, draws at once; a tile asks for its own size, so a
 * three-across wall doesn't fetch 500px covers. Kept on the phone's disk too
 * (item 158: every start away fetched every cover again), at most six fetched
 * at once, and a tile scrolled away before its turn fetches nothing. Nothing
 * is drawn here (the screens contract's shell, docs/specs/screens.json).
 */
internal object Covers {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val fetching = Semaphore(6)
    private const val DISK_LIMIT = 200L * 1024 * 1024
    private val writes = AtomicInteger(0)

    fun get(id: String): ImageBitmap? = cache.get(id)
    fun put(id: String, image: ImageBitmap) { cache.put(id, image) }

    /** Android asking for memory back ([level], onTrimMemory's): half let go, or all once the app is in the background. */
    fun trim(level: Int) { if (level >= 40) cache.evictAll() else if (level >= 15) cache.trimToSize(cache.maxSize() / 2) }

    /** The server's cover [key] at [size]: kept in memory, kept on disk, or fetched (and kept both ways). */
    suspend fun server(context: Context, c: ServerClient, key: String, size: Int): ImageBitmap? {
        val id = "$key@$size"
        get(id)?.let { return it }
        withContext(Dispatchers.IO) { runCatching { disk(context, key, size)?.let { decode(it) }?.also { put(id, it) } }.getOrNull() }?.let { return it }
        return fetching.withPermit {
            // Kept within the fetch, so one called off on the way (the tile gone) is still kept, not fetched again.
            get(id) ?: withContext(Dispatchers.IO) {
                runCatching { c.bytes(c.imageUrl(key, size)).let { b -> decode(b)?.also { put(id, it); keep(context, key, size, b) } } }.getOrNull()
            }
        }
    }

    private fun decode(b: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()

    private fun dir(context: Context) = File(context.cacheDir, "covers")

    private fun disk(context: Context, key: String, size: Int): ByteArray? {
        val f = File(dir(context), CoverDisk.name(key, size))
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return f.readBytes()
    }

    private fun keep(context: Context, key: String, size: Int, b: ByteArray) {
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, CoverDisk.name(key, size) + ".part")
        tmp.writeBytes(b)
        tmp.renameTo(File(d, CoverDisk.name(key, size)))
        if (writes.incrementAndGet() % 50 == 0) {
            val files = d.listFiles()?.map { CoverDisk.Kept(it.name, it.length(), it.lastModified()) }.orEmpty()
            for (n in CoverDisk.trim(files, DISK_LIMIT)) File(d, n).delete()
        }
    }
}

/** The cover [key] at [size] pixels for a tile, kept or fetched and kept; null until then, or with no key. */
@Composable
internal fun rememberTileCover(key: String?, size: Int, client: () -> ServerClient?): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(key, size) { mutableStateOf(key?.let { Covers.get("$it@$size") }) }
    LaunchedEffect(key, size) {
        if (key == null || image != null) return@LaunchedEffect
        image = Covers.server(context, client() ?: return@LaunchedEffect, key, size)
    }
    return image
}

/**
 * A picture at a web address of a service's or Pitchfork's own (the search's covers and artists' portraits, item 61), kept
 * as a tile's cover is; null until fetched, or with none. Asked without the server's token: the address isn't the server's.
 */
@Composable
internal fun rememberWebImage(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let { Covers.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || image != null || !(url.startsWith("https://") || url.startsWith("http://"))) return@LaunchedEffect
        image = withContext(Dispatchers.IO) { fetchWebImage(url) }
    }
    return image
}

/** The picture at [url] on the web, fetched (on the caller's thread) and kept; null when none came or it isn't a picture. */
internal fun fetchWebImage(url: String): ImageBitmap? = Covers.get(url) ?: runCatching {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 5000; c.readTimeout = 15000
    try { if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } else null } finally { c.disconnect() }
}.getOrNull()?.also { Covers.put(url, it) }
