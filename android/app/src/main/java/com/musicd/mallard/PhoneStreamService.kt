package com.musicd.mallard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * This phone's sound away from home: the server's stream of what LMS plays to the phone's own player (lib/phoneplayers.js),
 * played for as long as the phone is away. It is one continuous stream — silence while LMS is paused — so it never ends;
 * a dropped connection is taken up again after a moment. LMS does the transport (the app's screens, the lock screen's
 * controls through NowPlayingService); this only plays what arrives, with a small buffer so a tap is heard soon.
 *
 * With USB direct on and a DAC on the port (Settings → Away from home → USB DAC) the sound goes to the DAC through the
 * app's own USB driver (UsbAudioSink), Android's mixer out of the way; the decoders give float, which carries a 16- or
 * 24-bit sample exactly, and WordsSink hands the DAC each sample's own word.
 *
 * Untouched (Settings → Away from home → Stream away as): no stream — the server's relay hands the phone each track as
 * its own file, and PhoneRelay plays them as a playlist on this same player and reports back.
 */
@OptIn(UnstableApi::class)
class PhoneStreamService : Service() {
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var url: String? = null
    private var relay: PhoneRelay? = null
    private var relayFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val relayPlayer = intent?.getStringExtra(EXTRA_RELAY)
        val u = intent?.getStringExtra(EXTRA_URL)
        if (relayPlayer == null && u == null) return START_NOT_STICKY
        foreground(intent?.getStringExtra(EXTRA_NAME).orEmpty())
        if (relayPlayer != null) { if (relayPlayer != relayFor || relay == null) untouched(relayPlayer) }
        else if (u != null && (u != url || player == null)) play(u)
        return START_STICKY
    }

    /** Untouched: the relay's tracks, as a playlist on this player (PhoneRelay). */
    private fun untouched(playerId: String) {
        val client = Store.client(this) ?: return
        url = null
        relay?.stop()
        val p = ensurePlayer()
        p.stop(); p.clearMediaItems()
        relayFor = playerId
        relay = PhoneRelay(p, client, playerId).also { it.start() }
    }

    private fun play(u: String) {
        relay?.stop(); relay = null; relayFor = null
        url = u
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(u))
        p.prepare()
        p.play()
        AppLog.line("AWAY", "playing this phone's player from the server")
    }

    /** The one player, built once: its audio through WordsSink and UsbAudioSink, so a USB DAC can take it. */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                WordsSink(UsbAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build(), context).also { usbSink = it })
        }.setEnableAudioFloatOutput(true)
            // The app's own Opus decoder (libopus, float out) ahead of Android's, which gives 16-bit: Opus 256 at 24/48.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 8000, 500, 1000).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also { ex ->
                ex.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        AppLog.line("AWAY", "the stream stopped: ${error.errorCodeName}; taken up again", anomaly = true)
                        main.postDelayed({ url?.let { again -> ex.setMediaItem(MediaItem.fromUri(again)); ex.prepare(); ex.play() } }, 2_000)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        // The stream never ends; if it does (the server restarted), it is asked for again.
                        if (state == Player.STATE_ENDED) main.postDelayed({ url?.let { again -> ex.setMediaItem(MediaItem.fromUri(again)); ex.prepare(); ex.play() } }, 1_000)
                    }
                })
                player = ex
            }
    }

    private fun foreground(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing away from home", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneStreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("This phone, away from home").setContentText(if (name.isNotEmpty()) "Lyrion plays to “$name”" else "Lyrion plays to this phone")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
    }

    /** The sink, once the player is built: whether the DAC is what plays. */
    @Volatile private var usbSink: UsbAudioSink? = null

    /**
     * USB direct switched, or the DAC came or went: the sink decides when it is configured, so the stream is taken up
     * again and takes the new path. The DAC pulled out while playing through it: stopped, never on through the loudspeaker.
     */
    private fun usbChanged() {
        val p = player ?: return
        val wasUsb = usbSink?.usb == true
        val want = Store.usbDirect(this) && UsbDac.device() != null
        if (wasUsb == want && (wasUsb || !UsbDriver.active)) return
        val playing = p.playWhenReady
        p.stop()
        if (!want) UsbDriver.close()
        if (wasUsb && UsbDac.device() == null) { AppLog.line("USB", "the DAC went: the phone's sound stopped rather than go to the loudspeaker"); stopSelf(); return }
        // Untouched: the same track again from where it was, on the new path. The stream: asked for again.
        if (relay != null) { p.prepare(); p.playWhenReady = playing }
        else url?.let { p.setMediaItem(MediaItem.fromUri(it)); p.prepare(); p.play() }
    }
    private val onUsb: () -> Unit = { main.post { usbChanged() } }

    override fun onCreate() { super.onCreate(); UsbDac.listen(onUsb) }

    override fun onDestroy() {
        UsbDac.unlisten(onUsb)
        main.removeCallbacksAndMessages(null)
        relay?.stop(); relay = null; relayFor = null
        player?.release(); player = null; url = null; usbSink = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "away"
        private const val ID = 4207
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RELAY = "relay"
        private const val ACTION_STOP = "com.musicd.mallard.AWAY_STOP"

        /** Playing [url] (the phone's stream) as [name]; started in the foreground. False when Android won't let it start now. */
        fun start(c: Context, url: String, name: String): Boolean = runCatching {
            val i = Intent(c, PhoneStreamService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
            c.startForegroundService(i)
            true
        }.getOrElse { AppLog.line("AWAY", "couldn't start the phone's stream yet: ${it.message}", anomaly = true); false }

        /** Untouched: the relay's tracks for [playerId] (the phone's player), as [name]. */
        fun startRelay(c: Context, playerId: String, name: String): Boolean = runCatching {
            c.startForegroundService(Intent(c, PhoneStreamService::class.java).putExtra(EXTRA_RELAY, playerId).putExtra(EXTRA_NAME, name))
            true
        }.getOrElse { AppLog.line("AWAY", "couldn't start the phone's player yet: ${it.message}", anomaly = true); false }

        fun stop(c: Context) { runCatching { c.stopService(Intent(c, PhoneStreamService::class.java)) } }
    }
}
