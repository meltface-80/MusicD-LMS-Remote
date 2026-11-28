package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.CachePlan
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PhoneStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Settings → Audio Devices → This phone (owner request: this phone's own settings on its own page, not in Away from
 * home): how it streams away from home, then its cache of tracks kept ahead (Mandarin's "Playing on this phone", owner
 * request "add caching like Mandarin has"): how many on Wi-Fi and on mobile data, the space it may take, what it holds
 * now, and Clear. Read again every two seconds while shown.
 */
@Composable
internal fun PhoneStreamRows() {
    val context = LocalContext.current
    // How the phone's own player is streamed away (the server makes it): Opus 256 kbps, or bit-perfect.
    var quality by remember { mutableStateOf(Store.awayQuality(context)) }
    val choices = PhoneStream.QUALITIES.map { (v, w) -> Choice(v, w) }
    SetupRow("Stream away as", Modifier, null) {
        SelectBox(choiceWords(choices, quality), choices, Modifier, Modifier.clickable {
            pickChoice(context, choices, quality) { v -> quality = v; Store.setAwayQuality(context, v) }   // the next track queued takes it (a track keeps the form it started in)
        })
    }
    SetupNote(PhoneStream.NOTE, Modifier)
    PhoneCacheRows()
}

@Composable
private fun PhoneCacheRows() {
    val context = LocalContext.current
    var wifi by remember { mutableIntStateOf(Store.cacheWifi(context)) }
    var mobile by remember { mutableIntStateOf(Store.cacheMobile(context)) }
    var gb by remember { mutableIntStateOf(Store.cacheGb(context)) }
    var used by remember { mutableLongStateOf(0L) }
    var status by remember { mutableStateOf(PhoneAhead.last) }
    LaunchedEffect(Unit) {
        while (true) {
            used = withContext(Dispatchers.IO) { StreamCache.usedBytes(context) }
            status = PhoneAhead.last
            delay(2_000)
        }
    }
    val dim = paletteColor(Palette.TEXT_DIM)
    SetupPick("Cached ahead on Wi-Fi", CachePlan.WIFI_CHOICES, wifi, "tracks") { wifi = it; Store.setCache(context, "cache_wifi", it) }
    SetupPick("Cached ahead on mobile data", CachePlan.MOBILE_CHOICES, mobile, "tracks") { mobile = it; Store.setCache(context, "cache_mobile", it) }
    SetupPick("Cache size", CachePlan.SIZE_CHOICES_GB, gb, "GB") { gb = it; Store.setCache(context, "cache_gb", it); StreamCache.setLimit(context, it) }
    SetupRow("Cached now", Modifier, null) {
        val s = status
        Text((if (s != null && s.wanted > 0) "${s.have} of ${s.wanted} ahead" + (if (s.metered) " (mobile data)" else "") + " · " else "") + size(used) + " used",
            color = dim, fontSize = 14.sp)
    }
    SetupRow("", Modifier, null) {
        SetupButton("Clear the cache", false, Modifier.clickable {
            Thread { StreamCache.clear(context) }.start(); used = 0; setupToast(context, "Cleared — tracks are fetched again as they play")
        })
    }
    SetupNote("Playing on this phone, the next tracks in the queue are kept on the phone ahead of time, so they play without the network — " +
        "through a dead spot, or on mobile data without using any. On mobile data $mobile tracks is about ${Math.round(mobile * 9.0)} MB as Opus 256. " +
        "Tracks stay until the space is needed, so one played again is already here.", Modifier)
}

/** A number chosen from a list ([unit] after it): its name, the list as a select box. */
@Composable
private fun SetupPick(label: String, values: List<Int>, now: Int, unit: String, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val choices = values.map { Choice(it.toString(), "$it $unit") }
    SetupRow(label, Modifier, null) {
        SelectBox(choiceWords(choices, now.toString()), choices, Modifier, Modifier.clickable {
            pickChoice(context, choices, now.toString()) { v -> v.toIntOrNull()?.let(onPick) }
        })
    }
}

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
    else -> "${b / 1024} KB"
}
