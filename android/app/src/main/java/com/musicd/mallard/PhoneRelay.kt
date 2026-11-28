package com.musicd.mallard

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.musicd.mallard.client.PhoneStream
import com.musicd.mallard.client.RelayCommand
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.phoneReport
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Untouched away from home (owner request: bit-perfect through a USB DAC): the server's relay player
 * (lib/relayplayer.js) hands the phone each track LMS plays as the file's own bytes, and this plays them — one
 * ExoPlayer item per track, so gapless — and tells the server what it plays (started, where it is, the end), which the
 * relay tells LMS. Commands arrive on the relay's event channel (Server-Sent Events), read on a thread of their own and
 * acted on on the main thread. The sound goes where PhoneStreamService's sinks send it: the USB DAC with USB direct on.
 *
 * Lyrion's volume arrives as dB and moves the DAC's own volume control (bit-perfect: the samples are never scaled); a
 * DAC with none plays at full. Without a DAC the phone's player volume follows it.
 */
internal class PhoneRelay(private val player: ExoPlayer, private val client: ServerClient, private val playerId: String) {
    private val main = Handler(Looper.getMainLooper())
    private val reports = Executors.newSingleThreadExecutor()
    @Volatile private var running = true
    private var reader: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null
    private val started = HashSet<Int>()
    /** Where in a track the phone was when it started it: a stream begun mid-track (a seek) counts from there, as LMS does. */
    private val base = HashMap<Int, Long>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (player.isPlaying) startedNow() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) startedNow(); pos() }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) { val s = seq(); report("drained", s, 0, false) }
        }
        override fun onPlayerError(error: PlaybackException) {
            val s = seq()
            AppLog.line("AWAY", "an untouched track couldn't play (${error.errorCodeName}); skipped", anomaly = true)
            report("failed", s, 0, false)
            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying) pos()
            // Lyrion's volume came before the DAC's stream opened (which starts it low): given to the stream once it is open.
            val open = UsbDriver.current
            if (open != null && open !== volumeFor) wanted?.let { volume(it) }
            main.postDelayed(this, 1000)
        }
    }
    /** Lyrion's last volume, and the DAC stream it was given to. */
    private var wanted: RelayCommand? = null
    private var volumeFor: Any? = null

    fun start() {
        player.addListener(listener)
        main.postDelayed(ticker, 1000)
        reader = Thread({ listen() }, "relay-events").apply { isDaemon = true; start() }
        AppLog.line("AWAY", "untouched: this phone plays Lyrion's tracks as their own files")
    }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
        reader?.interrupt()
        main.removeCallbacks(ticker)
        player.removeListener(listener)
        reports.shutdownNow()
    }

    private fun seq(): Int = player.currentMediaItem?.mediaId?.toIntOrNull() ?: 0

    private fun startedNow() {
        val s = seq()
        if (s <= 0 || !started.add(s)) return
        base[s] = player.currentPosition.coerceAtLeast(0)
        report("started", s, 0, true)
    }

    private fun pos() {
        val s = seq()
        if (s <= 0 || s !in started) return
        report("pos", s, (player.currentPosition - (base[s] ?: 0)).coerceAtLeast(0), player.isPlaying)
    }

    private fun report(ev: String, s: Int, ms: Long, playing: Boolean) {
        if (!running) return
        runCatching { reports.execute { runCatching { client.phoneReport(playerId, ev, s, ms, playing) } } }
    }

    /** The relay's event channel, held open; taken up again after a moment if it drops. */
    private fun listen() {
        while (running) {
            runCatching {
                val c = URL(client.address.baseUrl + PhoneStream.eventsPath(playerId)).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 10_000; c.readTimeout = 45_000   // the server pings every 15 s
                c.setRequestProperty("Accept", "text/event-stream")
                if (c.responseCode != 200) throw IllegalStateException("events: HTTP ${c.responseCode}")
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val cmd = RelayCommand.parse(line.removePrefix("data:").trim()) ?: continue
                        main.post { act(cmd) }
                    }
                }
            }.onFailure { if (running) AppLog.line("AWAY", "the relay's channel dropped (${it.message}); again in a moment") }
            if (running) runCatching { Thread.sleep(2_000) }
        }
    }

    /** One command, on the main thread. */
    private fun act(c: RelayCommand) {
        if (!running) return
        when (c.cmd) {
            "add" -> {
                val item = MediaItem.Builder().setUri(client.address.baseUrl + PhoneStream.trackPath(playerId, c.seq)).setMediaId(c.seq.toString())
                    .apply { if (c.mime.isNotEmpty()) setMimeType(c.mime) }.build()
                if (c.fresh) {
                    player.setMediaItem(item)
                    player.prepare()
                    player.playWhenReady = c.play
                } else {
                    player.addMediaItem(item)
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) { player.prepare(); if (c.play) player.play() }
                }
            }
            "drop" -> for (i in player.mediaItemCount - 1 downTo 0) {
                if (i != player.currentMediaItemIndex && player.getMediaItemAt(i).mediaId == c.seq.toString()) player.removeMediaItem(i)
            }
            "stop" -> { player.stop(); player.clearMediaItems(); started.clear(); base.clear() }
            "pause" -> player.pause()
            "resume" -> player.play()
            "volume" -> volume(c)
        }
    }

    private fun volume(c: RelayCommand) {
        wanted = c
        volumeFor = UsbDriver.current
        val usb = UsbDriver.active
        when {
            usb && UsbDriver.hasVolume && !UsbDriver.fixed() -> { UsbDriver.setVolume(RelayCommand.level(c.db)); UsbDriver.setMute(c.muted) }
            usb -> Unit   // no volume control of its own (or fixed): full, untouched
            else -> player.volume = if (c.muted) 0f else c.db?.let { Math.pow(10.0, it.coerceAtMost(0.0) / 20.0).toFloat() } ?: 0f
        }
    }
}
