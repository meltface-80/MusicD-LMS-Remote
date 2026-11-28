package com.musicd.mallard

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.musicd.mallard.client.CachePlan
import com.musicd.mallard.client.PhoneStream
import com.musicd.mallard.client.RelayCommand
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ThisPhone
import com.musicd.mallard.client.phoneNote
import com.musicd.mallard.client.phoneReport
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * This phone's player, as Mandarin's phone zone: the server's player (lib/relayplayer.js) hands the phone each track LMS
 * plays, and this plays them — one ExoPlayer item per track, so gapless — and tells the server what it plays (started,
 * where it is, the end), which it tells LMS. Commands arrive on the player's event channel (Server-Sent Events), read on
 * a thread of their own and acted on on the main thread. Each track is the file itself (bit-perfect; at home always) or,
 * away with Opus chosen, its Opus 256 kbps — decided as it is queued, so a track keeps the form it started in. LMS's
 * ReplayGain for each comes with it and is applied on the phone (TrackGain). The sound goes where PhoneStreamService's
 * sinks send it: the USB DAC with USB direct on. The volume is the phone's own (PhoneVolume).
 */
internal class PhoneRelay(
    private val player: ExoPlayer, private val client: () -> ServerClient?, private val playerId: String, private val opus: () -> Boolean,
    private val files: MediaSource.Factory, private val bytes: MediaSource.Factory, private val ahead: PhoneAhead,
    private val kept: (String) -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val reports = Executors.newSingleThreadExecutor()
    @Volatile private var running = true
    private var reader: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null
    private val started = HashSet<Int>()
    /** Where in a track the phone was when it started it: a stream begun mid-track (a seek) counts from there, as LMS does. */
    private val base = HashMap<Int, Long>()
    /** LMS's ReplayGain per track, in dB (null: none). */
    private val gainDb = HashMap<Int, Double?>()
    /** A track played by its id, as the player's own bytes instead: for when it won't play that way (taken up once). */
    private val fallback = HashMap<Int, MediaSource>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            note("now on #${seq()} (${mediaItem?.localConfiguration?.uri})"); gains(); ahead.soon(); if (player.isPlaying) startedNow()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            note((if (isPlaying) "playing" else "not playing") + " #${seq()} at ${player.currentPosition} ms" +
                (if (isPlaying) ", ${player.audioFormat?.let { "${it.sampleMimeType} ${it.sampleRate} Hz ${it.channelCount} ch" } ?: "no format"}, gain ${"%.2f".format(TrackGain.gain)}, volume ${player.volume}, phone volume ${ThisPhone.volume?.level()}" else "") +
                (if (!isPlaying && player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) ", suppressed (${player.playbackSuppressionReason})" else ""))
            if (isPlaying) startedNow(); pos()
        }
        override fun onPlaybackStateChanged(state: Int) {
            note("#${seq()}: " + when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" } + " at ${player.currentPosition} ms")
            if (state == Player.STATE_ENDED) { val s = seq(); report("drained", s, 0, false) }
        }
        override fun onPlayerError(error: PlaybackException) {
            val s = seq()
            val i = player.currentMediaItemIndex
            val fb = fallback.remove(s)
            if (fb != null && i >= 0) {
                // By its id it wouldn't play (the file, or its Opus, didn't come): the same track as the player's bytes.
                note("track #$s didn't play by its id (${error.errorCodeName}: ${error.cause?.message}); playing the player's stream of it", true)
                val at = player.currentPosition.coerceAtLeast(0)
                player.removeMediaItem(i); player.addMediaSource(i, fb); player.seekTo(i, at); player.prepare()
                return
            }
            note("track #$s couldn't play (${error.errorCodeName}: ${error.cause?.message}); skipped", true)
            report("failed", s, 0, false)
            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying) pos()
            main.postDelayed(this, 1000)
        }
    }

    fun start() {
        player.addListener(listener)
        main.postDelayed(ticker, 1000)
        reader = Thread({ listen() }, "relay-events").apply { isDaemon = true; start() }
        note("this phone plays Lyrion's tracks, one item each; phone volume ${ThisPhone.volume?.level()}, USB DAC ${if (UsbDriver.active) "on" else "off"}")
    }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
        reader?.interrupt()
        main.removeCallbacks(ticker)
        player.removeListener(listener)
        reports.shutdownNow()
        ahead.stop()
    }

    private fun seq(): Int = player.currentMediaItem?.mediaId?.toIntOrNull() ?: 0

    /** The track heard now and the one after it: their ReplayGain, for the decoders to take up as they move on. */
    private fun gains() {
        val i = player.currentMediaItemIndex
        fun at(k: Int) = if (k in 0 until player.mediaItemCount) TrackGain.of(player.getMediaItemAt(k).mediaId.toIntOrNull()?.let { gainDb[it] }) else 1.0
        TrackGain.gains(at(i), at(i + 1))
    }

    private fun startedNow() {
        val s = seq()
        if (s <= 0 || !started.add(s)) return
        base[s] = player.currentPosition.coerceAtLeast(0)
        fallback.keys.removeAll { it < s }
        report("started", s, 0, true)
    }

    private fun pos() {
        val s = seq()
        if (s <= 0 || s !in started) return
        report("pos", s, (player.currentPosition - (base[s] ?: 0)).coerceAtLeast(0), player.isPlaying)
    }

    /** A line for the app's log and the server's (owner report: no sound, and nothing to say why). */
    private fun note(msg: String, anomaly: Boolean = false) {
        AppLog.line("PHONE", msg, anomaly)
        if (running) runCatching { reports.execute { runCatching { client()?.phoneNote(playerId, msg) } } }
    }

    private fun report(ev: String, s: Int, ms: Long, playing: Boolean) {
        if (!running) return
        runCatching { reports.execute { runCatching { client()?.phoneReport(playerId, ev, s, ms, playing) } } }
    }

    /** The relay's event channel, held open; taken up again after a moment if it drops. */
    private fun listen() {
        while (running) {
            runCatching {
                // Where the server is now (home, or away through the phone's Tailscale): looked up again at each connection.
                val base = client()?.address?.baseUrl ?: throw IllegalStateException("no server")
                val c = URL(base + PhoneStream.eventsPath(playerId)).openConnection() as HttpURLConnection
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
                val asOpus = opus()
                note("add #${c.seq}: ${if (c.fresh) "fresh" else "next"}, ${if (c.play) "play" else "hold"}, " +
                    (if (byId != null) "track $byId from the phone's cache" else "the player's bytes" + (c.trackId?.let { " (track $it, not cached yet)" } ?: "")) + (if (asOpus) " as Opus" else "") + ", ${c.mime}, gain ${c.gainDb} dB")
                // A track the phone already holds whole (kept, or fetched ahead): from its cache, by its id. Any other: the
                // bytes LMS is sending its player now (owner report: a track fetched by id from LMS's download stalled, and
                // nothing played). Relative addresses: sent where the server is now (PhoneStreamService).
                val byId = c.trackId?.takeIf { kept(CachePlan.key(it, asOpus)) }
                val path = byId?.let { PhoneStream.filePath(it, asOpus) } ?: PhoneStream.trackPath(playerId, c.seq, asOpus)
                val item = MediaItem.Builder().setUri(path).setMediaId(c.seq.toString())
                    .apply { val m = if (asOpus) "audio/ogg" else c.mime; if (m.isNotEmpty()) setMimeType(m) }.build()
                val source = (if (byId != null) files else bytes).createMediaSource(item)
                if (byId != null) fallback[c.seq] = bytes.createMediaSource(item.buildUpon().setUri(PhoneStream.trackPath(playerId, c.seq, asOpus)).build())
                gainDb[c.seq] = c.gainDb
                if (c.fresh) {
                    player.setMediaSource(source)
                    player.prepare()
                    player.playWhenReady = c.play
                } else {
                    player.addMediaSource(source)
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) { player.prepare(); if (c.play) player.play() }
                }
                gains()
                ahead.soon()
            }
            "drop" -> for (i in player.mediaItemCount - 1 downTo 0) {
                if (i != player.currentMediaItemIndex && player.getMediaItemAt(i).mediaId == c.seq.toString()) player.removeMediaItem(i)
            }
            "stop" -> { player.stop(); player.clearMediaItems(); started.clear(); base.clear(); gainDb.clear(); fallback.clear(); TrackGain.reset() }
            "pause" -> player.pause()
            "resume" -> player.play()
        }
    }

}
