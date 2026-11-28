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
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.musicd.mallard.client.PhoneStream

/**
 * This phone's sound while it is a player ("This phone", away from home or chosen at home), as Mandarin's phone zone:
 * the server's player (lib/phoneplayers.js) hands the phone each track LMS plays, and PhoneRelay plays them one item per
 * track on this player — the file itself, or away with Opus chosen its Opus 256 kbps — and reports back. LMS does the
 * transport (the app's screens, the lock screen's controls through NowPlayingService); this plays what it is given.
 *
 * With USB direct on and a DAC on the port (Settings → Away from home → USB DAC) the sound goes to the DAC through the
 * app's own USB driver (UsbAudioSink), Android's mixer out of the way; the decoders give float (the app's own libopus for
 * Opus), which carries a 16- or 24-bit sample exactly, and WordsSink hands the DAC each sample's own word.
 *
 * The library's tracks are played by their LMS id through the phone's cache (StreamCache, Mandarin's): what is there
 * plays from the phone, what isn't is fetched and kept as it plays, and the tracks ahead are fetched into it (PhoneAhead).
 * Addresses are relative and sent where the server is now — home or away — as each is opened.
 */
@OptIn(UnstableApi::class)
class PhoneStreamService : Service() {
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var relay: PhoneRelay? = null
    private lateinit var routed: DataSource.Factory
    private lateinit var cached: CacheDataSource.Factory
    private var relayFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val playerId = intent?.getStringExtra(EXTRA_PLAYER) ?: return START_NOT_STICKY
        foreground(intent.getStringExtra(EXTRA_NAME).orEmpty())
        if (playerId != relayFor || relay == null) play(playerId)
        return START_STICKY
    }

    /** The player's tracks, as a playlist on this player (PhoneRelay). */
    private fun play(playerId: String) {
        if (Store.client(this) == null) return
        val client = { Store.client(this) }   // where the server is now, home or away
        relay?.stop()
        val p = ensurePlayer()
        p.stop(); p.clearMediaItems()
        relayFor = playerId
        // Opus only away with Opus chosen (Mandarin's rule): at home always the file itself. Asked as each track is queued.
        val opus = { PhoneStream.opus(Store.awayVia, Store.awayQuality(this)) }
        val ahead = PhoneAhead(this, p, cached, client, playerId, opus)
        relay = PhoneRelay(p, client, playerId, opus, DefaultMediaSourceFactory(cached).setLoadErrorHandlingPolicy(retrying),
            DefaultMediaSourceFactory(routed).setLoadErrorHandlingPolicy(retrying), ahead) { StreamCache.complete(this, it) }.also { it.start() }
    }

    /** The one player, built once: its audio through WordsSink and UsbAudioSink, so a USB DAC can take it. */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // Mandarin's: a connection gone quiet (the Wi-Fi gone as you leave) is given up on in seconds, and fetched again.
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5_000).setReadTimeoutMs(8_000).setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            val u = spec.uri.toString()
            if (u.startsWith("/")) spec.withUri(Uri.parse((Store.client(this)?.address?.baseUrl ?: "") + u)) else spec
        }
        routed = DataSource.Factory { Relative(resolving.createDataSource()) }
        val cache = StreamCache.get(this)
        cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(routed)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setCacheKeyFactory(CacheKeyFactory { spec -> PhoneStream.cacheKey(spec.uri.toString()) ?: spec.key ?: spec.uri.toString() })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                WordsSink(UsbAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build(), context).also { usbSink = it })
        }.setEnableAudioFloatOutput(true)
            // The app's own Opus decoder (libopus, float out) ahead of Android's, which gives 16-bit: Opus 256 at 24/48.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        // Mandarin's: the playing track fetched whole as fast as the network allows (up to 5 minutes or 48 MB ahead), so
        // leaving the house is heard from what's on the phone.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(AHEAD_MS, AHEAD_MS, 1_500, 3_000)
            .setTargetBufferBytes(AHEAD_BYTES).setPrioritizeTimeOverSizeThresholds(false).build()
        return ExoPlayer.Builder(this, renderers).setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also { player = it }
    }

    private fun foreground(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on this phone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneStreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("This phone").setContentText(if (name.isNotEmpty()) "Lyrion plays to “$name”" else "Lyrion plays to this phone")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
    }

    /**
     * Mandarin's: a fetch that fails (no network for a moment) is tried again quickly and for a good while, from where it
     * stopped, while the music plays on from what's held.
     */
    private val retrying = object : DefaultLoadErrorHandlingPolicy(RETRIES) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val d = super.getRetryDelayMsFor(info)
            return if (d == C.TIME_UNSET) d else minOf(500L * info.errorCount, 2_000L)
        }
    }

    /** The sink, once the player is built: whether the DAC is what plays. */
    @Volatile private var usbSink: UsbAudioSink? = null

    /**
     * USB direct switched, or the DAC came or went: the sink decides when it is configured, so the track is taken up again
     * from where it was and takes the new path. The DAC pulled out while playing through it: stopped, never on through the
     * loudspeaker.
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
        p.prepare(); p.playWhenReady = playing
    }
    private val onUsb: () -> Unit = { main.post { usbChanged() } }

    override fun onCreate() { super.onCreate(); UsbDac.listen(onUsb) }

    override fun onDestroy() {
        UsbDac.unlisten(onUsb)
        main.removeCallbacksAndMessages(null)
        relay?.stop(); relay = null; relayFor = null
        player?.release(); player = null; usbSink = null
        super.onDestroy()
    }

    /**
     * Reports the address it was asked for, not where it went: the cache otherwise notes the home (or away) address as a
     * redirect, and a track kept at home would be fetched from home again away from it.
     */
    @OptIn(UnstableApi::class)
    private class Relative(private val up: DataSource) : DataSource by up {
        private var asked: Uri? = null
        override fun open(dataSpec: DataSpec): Long { asked = dataSpec.uri; return up.open(dataSpec) }
        override fun getUri(): Uri? = up.uri?.let { asked }
    }

    companion object {
        private const val CHANNEL = "away"
        private const val AHEAD_MS = 5 * 60 * 1000
        private const val AHEAD_BYTES = 48 * 1024 * 1024
        private const val RETRIES = 40
        private const val ID = 4207
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PLAYER = "relay"
        private const val ACTION_STOP = "com.musicd.mallard.AWAY_STOP"

        /** This phone's player [playerId], as [name]: its tracks played here. False when Android won't let it start now. */
        fun start(c: Context, playerId: String, name: String): Boolean = runCatching {
            c.startForegroundService(Intent(c, PhoneStreamService::class.java).putExtra(EXTRA_PLAYER, playerId).putExtra(EXTRA_NAME, name))
            true
        }.getOrElse { AppLog.line("PHONE", "couldn't start the phone's player yet: ${it.message}", anomaly = true); false }

        fun stop(c: Context) { runCatching { c.stopService(Intent(c, PhoneStreamService::class.java)) } }
    }
}
