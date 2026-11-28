package com.musicd.mallard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout

/**
 * Mallard: the native remote for MusicD LMS Remote. Its screens are the app's own, all of them (NativeShell); what it
 * adds beside them is the lock screen and notification controls (NowPlayingService), the widget, the Quick Settings
 * tile, and the phone's volume keys moving the player's volume while the app is on screen.
 *
 * The server has no sign-in, so the only thing to set up is its address (ConnectActivity).
 */
class MainActivity : androidx.activity.ComponentActivity() {

    companion object {
        const val ACTION_CHANGE_SERVER = "com.musicd.mallard.action.CHANGE_SERVER"
        private const val BACKGROUND = 0xFF0E1012.toInt()
    }

    private lateinit var reach: ServerReach

    /** The server looked for now, not at the next look (the "can't reach the server" banner's Try again). */
    fun lookForServer() { if (::reach.isInitialized) reach.lookNow() }

    // A foldable folded or opened changes the screen without starting this again (smallestScreenSize, AndroidManifest.xml).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        holdUprightOnPhones(newConfig)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holdUprightOnPhones()
        if (intent?.action == ACTION_CHANGE_SERVER || Store.server(this) == null) {
            openConnect()
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        root.addView(NativeShell.attach(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        Insets.pad(root)
        setContentView(root)
        AppLog.line("APP", "the app's screen made")
        AppLog.trace.reset()
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            if (m.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 0L && NativeShell.onScreen.value)
                AppLog.trace.frame(m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000, SystemClock.uptimeMillis())?.let { AppLog.found(listOf(it)) }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        registerBack()
        CrashLog.offer(this)
        askForNotificationPermission()
        reach = ServerReach(this)
        reach.start()
        Away.changed = { reach.lookNow() }   // home or away through the phone's Tailscale (Away.kt): the server looked at again
        Away.watch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CHANGE_SERVER) openConnect()
    }

    override fun onResume() {
        super.onResume()
        NativeShell.onScreen.value = true
        if (!::reach.isInitialized) return
        AppLog.onScreenAt = SystemClock.elapsedRealtime()
        AppLog.line("APP", "on screen")
        reach.resumed()
        Away.resumed()
        NowPlayingService.start(this)
    }

    override fun onPause() {
        NativeShell.onScreen.value = false
        AppLog.line("APP", "off screen")
        if (::reach.isInitialized) { reach.paused(); Away.paused() }
        super.onPause()
    }

    internal fun openConnect() {
        startActivity(Intent(this, ConnectActivity::class.java))
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Back closes what is on top (the side menu, a screen); on Home the app goes to the back. */
    private fun back() {
        val on = AppLog.shownLast
        val closed = NativeShell.back()
        AppLog.line("BACK", "on $on: " + if (closed) "closed what was on top" else "the app goes to the back")
        if (!closed) moveTaskToBack(true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.line("APP", "Android asks for memory back (level $level)", anomaly = com.musicd.mallard.client.UiTrace.memoryLow(level))
        Covers.trim(level)
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { back() }
        }
    }

    @Deprecated("Deprecated in Java") @Suppress("DEPRECATION")
    override fun onBackPressed() {
        back()
    }

    /**
     * The volume buttons move the chosen player's volume while the app is on screen (LMS's own relative step, so quick
     * presses never race). With no player chosen, the phone's own volume as usual. Off screen, the media session's
     * remote volume does the same from the lock screen (NowPlayingService).
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val zone = NativeShell.zone
            if (zone.isNotEmpty()) {
                val up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                Thread({ runCatching { Store.client(this)?.zoneVolumeStep(zone, if (up) 1 else -1) } }, "volume-key").apply { isDaemon = true; start() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        AppLog.line("APP", "the app's screen gone" + if (isChangingConfigurations) " (made again: the phone turned, or its settings changed)" else "")
        if (::reach.isInitialized) reach.stop()
        super.onDestroy()
    }
}
