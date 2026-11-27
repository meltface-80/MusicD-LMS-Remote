package com.musicd.mallard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.Zone
import java.util.concurrent.Executors

/**
 * Follows one Lyrion player and puts it where Android shows media: the lock
 * screen, the notification shade, headset and Bluetooth buttons, and the
 * home-screen widget.
 *
 * It holds a long-poll open against /api/zone-state, so it hears about a track
 * change the moment the server does and costs nothing while nothing happens.
 * The room is whichever one was last looked at in the app (the server
 * remembers it), so the lock screen controls the room on the screen.
 *
 * It stops itself after a while with nothing playing, so a phone does not
 * carry a notification for a system that is switched off.
 */
class NowPlayingService : Service() {

    companion object {
        private const val TAG = "NowPlaying"
        private const val CHANNEL = "now_playing"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PREVIOUS = "com.musicd.mallard.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.musicd.mallard.PLAY_PAUSE"
        const val ACTION_NEXT = "com.musicd.mallard.NEXT"
        const val ACTION_RANDOM = "com.musicd.mallard.RANDOM"
        const val ACTION_STOP = "com.musicd.mallard.STOP"

        /** Nothing playing for this long and the service bows out (item 158: twenty minutes after every open, the app kept awake). */
        private const val IDLE_STOP_MS = 5 * 60 * 1000L
        private const val ZONES_MS = 5 * 60 * 1000L   // the rooms looked at again after this long (item 120)
        private const val ART_PX = 512

        @Volatile var latest: Zone? = null
            private set
        @Volatile var latestArt: Bitmap? = null
            private set
        @Volatile var reachable: Boolean = true
            private set

        fun start(context: Context, action: String? = null) {
            if (Store.server(context) == null) return
            // No network: no players to follow — and no "Can't reach the server" notification sitting there.
            if (!Net.hasNetwork(context)) return
            val i = Intent(context, NowPlayingService::class.java)
            if (action != null) i.action = action
            try { context.startForegroundService(i) } catch (e: Exception) { Log.w(TAG, "could not start the service", e) }
        }

        fun pending(context: Context, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context, action.hashCode(),
                Intent(context, NowPlayingService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    private val main = Handler(Looper.getMainLooper())
    /**
     * The lock screen, notification and widget are drawn here, never on the
     * main thread: handing a cover to Android copies it, and on a phone that
     * had just lost its network that copy held the app's screen for over ten
     * seconds (v0.5.55's freeze report).
     */
    private val ui = android.os.HandlerThread("now-playing-ui").apply { start() }
    private val uiHandler = Handler(ui.looper)
    /** What was last drawn: the same again isn't drawn twice. */
    @Volatile private var shown: String? = null
    private val commands = Executors.newSingleThreadExecutor { r -> Thread(r, "commands").apply { isDaemon = true } }
    private var session: MediaSession? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var zoneId: String? = null
    @Volatile private var wake = false
    private var artKey: String? = null
    private var idleSince = SystemClock.elapsedRealtime()
    private var volumeProvider: RoomVolume? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = try {
            MediaSession(this, "MusicDServer").apply {
                // With a Handler: without one the callback wants a Looper on
                // the calling thread, and that is how the original app went a
                // whole release without a working media session.
                setCallback(Callbacks(), main)
                setPlaybackState(state(null))
                isActive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "no media session on this device", e)
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_PREVIOUS -> command("previous")
            ACTION_PLAY_PAUSE -> command("playpause")
            ACTION_NEXT -> command("next")
            ACTION_RANDOM -> random()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        idleSince = SystemClock.elapsedRealtime()
        if (!running) {
            running = true
            worker = Thread({ loop() }, "now-playing").apply { isDaemon = true; start() }
        } else {
            wake = true
        }
        return START_NOT_STICKY   // not brought back by Android from the background, where it may not come to the front (item 158)
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        commands.shutdownNow()
        uiHandler.removeCallbacksAndMessages(null)
        ui.quitSafely()
        runCatching { session?.isActive = false; session?.release() }
        session = null
        latest = null
        NowPlayingWidget.render(this, null, null, reachable)
        super.onDestroy()
    }

    private fun goForeground() {
        val n = notification(latest)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }.onFailure { Log.w(TAG, "can't come to the front", it); stopSelf() }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, n) }.onFailure { Log.w(TAG, "can't come to the front", it); stopSelf() }
        }
    }

    // ---------------------------------------------------------------- loop

    private fun loop() {
        var failures = 0
        var zonesAt = 0L   // the rooms last looked at: at the start, woken (the app open, a room chosen), after a miss, every few minutes
        while (running) {
            val client = Store.client(this)
            if (client == null) { stopSelf(); return }
            // No Wi-Fi, no mobile data: nothing to ask (and nothing to draw again and again).
            if (!Net.hasNetwork(this)) {
                if (reachable) { reachable = false; showSoon(latest) }
                sleep(5_000)
                if (SystemClock.elapsedRealtime() - idleSince > IDLE_STOP_MS) { main.post { stopSelf() }; return }
                continue
            }
            try {
                if (zoneId == null || wake || failures > 0 || SystemClock.elapsedRealtime() - zonesAt > ZONES_MS) {
                    val zones = client.zones().also { zonesAt = SystemClock.elapsedRealtime() }
                    // The player the app has chosen, if the server still has it; else the first.
                    val kept = Store.zone(this)
                    val chosen = kept?.takeIf { k -> zones.zones.any { it.id == k } } ?: zones.zones.firstOrNull()?.id
                    if (chosen != zoneId) { zoneId = chosen; if (chosen != null && chosen != kept) Store.setZone(this, chosen) }
                }
                val id = zoneId
                if (id == null) {
                    publish(client, null)
                    sleep(15_000)
                    continue
                }
                wake = false
                val st = client.zoneState(id)
                failures = 0
                reachable = true
                publish(client, st.zone)
                if (st.zone?.isPlaying == true) idleSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - idleSince > IDLE_STOP_MS) {
                    main.post { stopSelf() }
                    return
                }
                // The server answers at once (no long-poll): asked again in 2 s while it plays, 6 s while it doesn't, as
                // its own page asks. A tap on the notification wakes this early.
                sleep(if (st.zone?.isPlaying == true) 2_000L else 6_000L)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                failures++
                reachable = false
                if (failures == 1) Log.i(TAG, "server unreachable: ${e.message}")
                showSoon(latest)
                wake = false; sleep(minOf(30_000L, 2_000L * failures))   // the wake this try answered spent, or the pause ends at once (item 400)
                if (SystemClock.elapsedRealtime() - idleSince > IDLE_STOP_MS) { main.post { stopSelf() }; return }
            }
        }
    }

    private fun sleep(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (running && !wake && SystemClock.elapsedRealtime() < until) {
            try { Thread.sleep(250) } catch (e: InterruptedException) { return }
        }
    }

    /** Fetch art if the track changed (off the main thread), then redraw. */
    private fun publish(client: ServerClient, zone: Zone?) {
        val key = zone?.nowPlaying?.imageKey
        if (key != artKey) {
            artKey = key
            latestArt = if (key == null) null else runCatching { art(client.bytes(client.imageUrl(key, ART_PX))) }.getOrNull()
        }
        latest = zone
        showSoon(zone)
    }

    /**
     * The cover, no bigger than [ART_PX] whatever size came, and (Android 12
     * on) in shared memory — so handing it to the lock screen, notification
     * and widget passes a reference, not a copy of every pixel.
     */
    private fun art(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (b.width > ART_PX || b.height > ART_PX) {
            val scale = ART_PX.toFloat() / maxOf(b.width, b.height)
            b = Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.asShared() else b
    }

    /** Draw [zone] on the drawing thread — only if something shown has changed. */
    private fun showSoon(zone: Zone?) {
        val np = zone?.nowPlaying
        val sig = listOf(zone?.id, zone?.name, zone?.state, np?.title, np?.artist, np?.album, np?.lengthSeconds,
            if (zone?.state == "playing") null else np?.seekSeconds, zone?.volume, zone?.nextAllowed, zone?.previousAllowed,
            artKey, latestArt != null, reachable).joinToString("|")
        if (sig == shown) return
        shown = sig
        runCatching { uiHandler.post { if (running) refreshViews(zone) } }
    }

    private fun refreshViews(zone: Zone?) {
        updateSession(zone)
        if (running) {
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(zone))
            }
        }
        NowPlayingWidget.render(this, zone, latestArt, reachable)
    }

    // ------------------------------------------------------------ session

    private fun state(zone: Zone?): PlaybackState {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (zone == null || zone.nextAllowed) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (zone == null || zone.previousAllowed) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val s = when (zone?.state) {
            "playing" -> PlaybackState.STATE_PLAYING
            "loading" -> PlaybackState.STATE_BUFFERING
            "paused" -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val pos = (zone?.nowPlaying?.seekSeconds ?: 0).toLong() * 1000L
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(s, pos, if (s == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
            .build()
    }

    private fun updateSession(zone: Zone?) {
        val s = session ?: return
        try {
            val np = zone?.nowPlaying
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, np?.title ?: "Nothing playing")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, np?.artist ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, np?.album ?: "")
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, zone?.name ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (np?.lengthSeconds ?: 0).toLong() * 1000L)
                    .apply { latestArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build()
            )
            s.setPlaybackState(state(zone))
            // The phone's volume keys move the room's volume while the session
            // is showing — the same as any Cast or Sonos app.
            val v = zone?.volume
            if (zone != null && v != null) {
                val p = volumeProvider
                if (p == null) {
                    val np2 = RoomVolume(v)
                    volumeProvider = np2
                    s.setPlaybackToRemote(np2)
                } else {
                    p.currentVolume = v
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not update the session", e)
        }
    }

    private inner class RoomVolume(initial: Int) :
        VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, initial) {

        override fun onSetVolumeTo(volume: Int) {
            val zone = latest ?: return
            val delta = volume - (zone.volume ?: volume)
            currentVolume = volume
            for (o in zone.outputs) {
                val target = ((o.volume ?: 0) + delta).coerceIn(0, 100)
                send { it.volumeAbsolute(o.id, target) }
            }
        }

        override fun onAdjustVolume(direction: Int) {
            if (direction == 0) return
            val zone = latest ?: return
            currentVolume = ((zone.volume ?: 0) + 2 * direction).coerceIn(0, 100)
            for (o in zone.outputs) send { it.volumeRelative(o.id, 2 * direction) }
        }
    }

    private inner class Callbacks : MediaSession.Callback() {
        override fun onPlay() = command("play")
        override fun onPause() = command("pause")
        override fun onStop() = command("pause")
        override fun onSkipToNext() = command("next")
        override fun onSkipToPrevious() = command("previous")
    }

    private fun send(body: (ServerClient) -> Unit) {
        val client = Store.client(this) ?: return
        runCatching {
            commands.execute {
                runCatching { body(client) }.onFailure { Log.w(TAG, "command failed", it) }
                wake = true
            }
        }
    }

    private fun command(cmd: String) {
        send { client ->
            val zone = zoneId ?: client.zones().lastZone ?: return@send
            client.control(zone, cmd)
        }
    }

    private fun random() {
        send { client -> client.playRandom(zoneId) }
    }

    // ------------------------------------------------------- notification

    private fun notification(zone: Zone?): Notification {
        val np = zone?.nowPlaying
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = zone?.isPlaying == true
        val title = when {
            !reachable -> "Can't reach MusicD LMS Remote"
            np != null -> np.title
            zone != null -> zone.name
            else -> "Mallard"
        }
        val text = when {
            !reachable -> Store.server(this)?.toString() ?: ""
            np != null -> listOf(np.artist, zone?.name ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            zone != null -> "Nothing playing"
            else -> "No player chosen yet"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action("Previous", android.R.drawable.ic_media_previous, ACTION_PREVIOUS))
            .addAction(
                if (playing) action("Pause", android.R.drawable.ic_media_pause, ACTION_PLAY_PAUSE)
                else action("Play", android.R.drawable.ic_media_play, ACTION_PLAY_PAUSE)
            )
            .addAction(action("Next", android.R.drawable.ic_media_next, ACTION_NEXT))
            .setDeleteIntent(pending(this, ACTION_STOP))
        latestArt?.let { b.setLargeIcon(it) }
        session?.sessionToken?.let { token ->
            b.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        }
        return b.build()
    }

    private fun action(title: String, icon: Int, act: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending(this, act)).build()
}
