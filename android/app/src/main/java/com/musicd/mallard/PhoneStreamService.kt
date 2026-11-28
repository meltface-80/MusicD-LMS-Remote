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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * This phone's sound away from home: the server's stream of what LMS plays to the phone's own player (lib/phoneplayers.js),
 * played for as long as the phone is away. It is one continuous stream — silence while LMS is paused — so it never ends;
 * a dropped connection is taken up again after a moment. LMS does the transport (the app's screens, the lock screen's
 * controls through NowPlayingService); this only plays what arrives, with a small buffer so a tap is heard soon.
 */
class PhoneStreamService : Service() {
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var url: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val u = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        foreground(intent.getStringExtra(EXTRA_NAME).orEmpty())
        if (u != url || player == null) play(u)
        return START_STICKY
    }

    private fun play(u: String) {
        url = u
        val p = player ?: ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 4000, 500, 1000).build())
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
        p.setMediaItem(MediaItem.fromUri(u))
        p.prepare()
        p.play()
        AppLog.line("AWAY", "playing this phone's player from the server")
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

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null; url = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "away"
        private const val ID = 4207
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val ACTION_STOP = "com.musicd.mallard.AWAY_STOP"

        /** Playing [url] (the phone's stream) as [name]; started in the foreground. False when Android won't let it start now. */
        fun start(c: Context, url: String, name: String): Boolean = runCatching {
            val i = Intent(c, PhoneStreamService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
            c.startForegroundService(i)
            true
        }.getOrElse { AppLog.line("AWAY", "couldn't start the phone's stream yet: ${it.message}", anomaly = true); false }

        fun stop(c: Context) { runCatching { c.stopService(Intent(c, PhoneStreamService::class.java)) } }
    }
}
