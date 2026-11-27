package com.musicd.mallard

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.musicd.mallard.client.AwayStood
import com.musicd.mallard.client.LookWait
import com.musicd.mallard.client.OfflineKind
import com.musicd.mallard.client.OneLoop
import com.musicd.mallard.client.Retry
import java.util.concurrent.Executors

/**
 * Whether the server can be reached, followed for the app's screens (bug list item 111; MainActivity's, beside the page,
 * until the WebView went). Online the screens ask the server; offline (Offline mode on, no network, or the server not
 * answering) they are answered by the phone ([OfflineNow]), and the server is looked for now and then, the screens going
 * back to it once it answers. The timings are the page's: three looks over about ten seconds at the start, then one every
 * quarter of a minute, two missed in a row being offline.
 */
internal class ServerReach(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val checks = Executors.newSingleThreadExecutor()
    /** The server watch's own thread: its repair of the way to the server can take a while, and the checks above mustn't wait for it. */
    private val watching = Executors.newSingleThreadExecutor()
    @Volatile private var offline = false
    /** The server has answered since the screens last went to it. */
    private var reached = false
    /** Which start a reachability check belongs to (a newer one makes older checks moot). */
    private var seq = 0
    private var liveFailures = 0
    /**
     * Back on screen offline, the wait for the look that says whether the server is there (item 485): the screens keep the
     * server's rooms as last answered meanwhile ([OfflineNow.looking]), where they showed the phone's own queue for a moment.
     */
    private val wait = LookWait()
    /** The look [serverWatch] makes next, by [LookWait]'s numbers. */
    private var look = 0
    /** Away acted on by the screens once it has stood three seconds on screen (item 487): [NativeShell.away]. */
    private val stood = AwayStood()
    private val awayAgain = Runnable { awayLook() }
    /** The wait's end if the look hasn't answered by then: its repair of the way to the server can take twenty seconds. */
    private val waitMost = Runnable { if (wait.waiting) phoneAnswers("not found in time") }
    /** The server looked for off screen and not found, in a row. */
    private var watchMisses = 0
    private var onScreen = false
    /** The address the screens went to the server on. */
    private var reachedOn: String? = null

    /** Started: the network followed, the server looked for. */
    fun start() {
        runCatching { a.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netWatch) }
        go()
    }

    fun stop() {
        runCatching { a.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netWatch) }
        main.removeCallbacksAndMessages(null)
        waitOver()
        live.stop()
        checks.shutdownNow()
        watching.shutdownNow()
    }

    /**
     * To the server: offline at once with Offline mode on or no network to reach it over, else the screens go to it while
     * it is looked for ([checkReachable]).
     */
    fun go() {
        waitOver()
        if (Store.server(a) == null) return a.openConnect()
        if (Store.offlineMode(a) || !Away.hasNetwork(a)) { AppLog.line("REACH", if (Store.offlineMode(a)) "Offline mode on" else "no network"); return goOffline() }
        // No address to reach the server on at all (away, with no way there): offline, not a wait (v0.7.2).
        val base = Store.active(a)?.baseUrl ?: run { AppLog.line("REACH", "no address to reach the server on", anomaly = true); Away.recheck(a); goOffline(); return }
        AppLog.line("REACH", "to the server at $base (" + (if (Store.isAway(a)) "away" else "home") + ")")
        if (Store.token(a) == null) return a.signedOut()
        reachedOn = base
        offline = false
        reached = false
        liveFailures = 0
        OfflineNow.goOnline(Store.playRoom(a))
        main.removeCallbacks(serverWatch)
        // The screens to the server: one whose load failed asks again (item 157).
        Reach.bump()
        checkReachable(base)
    }

    /** On screen again: the server looked for at once while offline; online, the address checked (home or away) and the watch started. */
    fun resumed() {
        onScreen = true
        awayLook()
        if (offline) {
            // The server is looked for at once, and until that look answers the screens keep its rooms (item 485).
            if (!Store.offlineMode(a) && Away.hasNetwork(a)) {
                AppLog.line("REACH", "on screen again offline: the screens keep the server's rooms as last answered while it is looked for")
                wait.begin(); OfflineNow.looking.value = true
                waitAtMost()
            }
            lookSoon(500); return
        }
        Reach.bump()
        val base = Store.active(a)?.baseUrl
        if (base != null && base != reachedOn) { AppLog.line("REACH", "the address changed while away from the screen: $reachedOn → $base"); go() }
        live.start(15_000)
    }

    fun paused() { onScreen = false; live.stop(); waitOver(); awayLook() }

    /** The way or the screen changed, or the count is up: whether away has stood, told to the screens, and asked again when it may have. */
    private fun awayLook() {
        val left = stood.at(Store.isAway(a), onScreen, SystemClock.elapsedRealtime())
        if (NativeShell.away.value != stood.stood) {
            AppLog.line("ROOM", if (stood.stood) "away for good: every screen follows and plays in this phone's room" else "not away for the screens: each in its own room")
            NativeShell.away.value = stood.stood
        }
        main.removeCallbacks(awayAgain)
        if (left != null) main.postDelayed(awayAgain, left)
    }

    /** The wait ends by itself, sooner away than at home (item 487, [LookWait.mostMs]): counted from here. */
    private fun waitAtMost() { main.removeCallbacks(waitMost); main.postDelayed(waitMost, LookWait.mostMs(Store.isAway(a))) }

    /** No look to wait for (item 485). */
    private fun waitOver() { wait.end(); OfflineNow.looking.value = false; main.removeCallbacks(waitMost) }

    /** The wait over with the server not found ([why]): the screens in the phone's room, answered by the phone. */
    private fun phoneAnswers(why: String) {
        AppLog.line("REACH", "the server $why: the screens in this phone's room, answered by the phone")
        waitOver()
    }

    /** The way to the server changed (home or away): the screens follow it, or, offline, the server is looked for. */
    fun moved() {
        AppLog.line("REACH", "the way to the server changed: " + (if (Store.isAway(a)) "away" else "home") + ", ${Store.active(a)?.baseUrl}")
        awayLook()
        if (offline) {
            // The way changed while the screens wait (the Wi-Fi awake, a moment after the screen): the wait as that way has it.
            if (wait.waiting) waitAtMost()
            if (Away.hasNetwork(a)) lookSoon(500); return
        }
        val base = Store.active(a)?.baseUrl
        if (base != null && base != reachedOn) go() else Reach.bump()
    }

    /** Offline mode switched (the menu, or Settings → Downloads): onto the phone's music, or back to the server. */
    fun modeChanged() {
        main.removeCallbacks(serverWatch)
        offline = false
        go()
    }

    /** Offline, the server looked for now (the Offline dialog's Try the server again, item 110), not at the next look. */
    fun lookNow() { if (offline) lookSoon(0) }

    private fun lookSoon(ms: Long) { look = wait.asked(); main.removeCallbacks(serverWatch); main.postDelayed(serverWatch, ms) }

    /** Offline: the screens answered by the phone, the server looked for now and then. */
    private fun goOffline() {
        AppLog.line("REACH", "offline: the screens answered by the phone (" + (if (Store.offlineMode(a)) "Offline mode" else "the server can't be reached") + ")", anomaly = !Store.offlineMode(a))
        offline = true
        reached = false
        ++seq
        waitOver()
        OfflineNow.goOffline(if (Store.offlineMode(a)) OfflineKind.MODE else OfflineKind.APP, Store.phoneZone(a))
        lookSoon(10_000)
    }

    private fun answers(base: String, timeout: Int): Boolean = runCatching {
        val c = java.net.URL("$base/api/health").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.useCaches = false
        c.setRequestProperty("Connection", "close")
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    /**
     * Does the server answer at all? Three tries over about ten seconds, not one: at a cold start away from home the
     * app's own Tailscale engine is still coming up for the first few seconds, and one three-second look used to call
     * that an outage. Answered, the copy kept for offline is refreshed; not, offline.
     */
    private fun checkReachable(base: String) {
        val mine = ++seq
        checks.execute {
            // This run's first look at the way made first (item 157); a way other than [base] found, the screens are
            // moved to it ([moved]) and looked for there.
            Away.awaitWay(a)
            if (Store.active(a)?.baseUrl != base) return@execute
            var ok = false
            // Looked for until three looks and ten seconds both have gone by (item 158: refused at once, three took three seconds).
            val from = SystemClock.elapsedRealtime()
            var looks = 0
            while (true) {
                if (mine != seq || a.isFinishing) return@execute
                ok = answers(base, 3000); looks++
                if (ok || Retry.unreachable(looks, SystemClock.elapsedRealtime() - from)) break
                try { Thread.sleep(1500) } catch (e: InterruptedException) { return@execute }
            }
            main.post {
                if (mine != seq || offline || a.isFinishing) return@post
                AppLog.line("REACH", if (ok) "the server answered" else "the server didn't answer three looks over about ten seconds", anomaly = !ok)
                if (ok) { reached = true; Reach.bump(); OfflineSite.sync(a); checks.execute { Away.learnId(a, base) } } else { Away.recheck(a); goOffline() }
            }
        }
    }

    /**
     * While on screen and online: is the server still there? Asked every 15 s; two missed in a row, offline. One loop of it
     * however often it is started again (item 193: each start while a look was out added another).
     */
    private val live = OneLoop({ r, ms -> main.postDelayed(r, ms) }, { r -> main.removeCallbacks(r) }) look@{ next ->
        if (a.isFinishing || !onScreen) return@look
        if (offline || !reached) { next(15_000); return@look }
        val base = Store.active(a)?.baseUrl
        if (base == null) { Away.recheck(a); goOffline(); return@look }
        val was = reachedOn
        checks.execute {
            val ok = answers(base, 4000)
            main.post {
                if (a.isFinishing) return@post
                liveFailures = if (ok) 0 else liveFailures + 1
                if (!ok) AppLog.line("REACH", "the live look at $base missed ($liveFailures in a row)", anomaly = true)
                if (ok) OfflineSite.sync(a)
                if (liveFailures >= 2 && !offline && reachedOn == was) { liveFailures = 0; Away.recheck(a); goOffline() }
                next(15_000)
            }
        }
    }

    /**
     * Offline: look for the server now and then, and go back to it when it answers. Each look first has the way to the
     * server checked and, if need be, repaired ([Away.check]: the Tailscale engine's tunnel can go stale while the phone
     * sleeps, and only a check brings it back).
     */
    private val serverWatch = object : Runnable {
        override fun run() {
            if (!offline || a.isFinishing) return
            // Offline mode: the server isn't looked for (switching it off does that).
            if (Store.offlineMode(a)) { waitOver(); return }
            if (!Away.hasNetwork(a)) { waitOver(); main.postDelayed(this, 10_000); return }
            val mine = look
            watching.execute {
                runCatching { Away.checkAndWait(a) }
                val back = Store.active(a)?.baseUrl?.let { answers(it, 2500) } ?: false
                main.post {
                    if (!offline || a.isFinishing) return@post
                    // This look's answer ends the wait on screen, unless it failed and a later one is asked for (item 485).
                    val waited = wait.waiting
                    wait.answered(mine, back)
                    if (waited && !wait.waiting && !back) phoneAnswers("not found") else OfflineNow.looking.value = wait.waiting
                    // Off screen, less and less often (item 158: every ten seconds all night).
                    if (back) { AppLog.line("REACH", "the server is back"); watchMisses = 0; go() } else main.postDelayed(this, if (onScreen) 10_000L else Retry.offScreen(++watchMisses))
                }
            }
        }
    }

    /**
     * The phone's network (v0.5.56): the last network gone, offline at once; another still up, the way to the server
     * looked at now and the server asked soon. A network back while offline: the server looked for straight away.
     */
    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            AppLog.line("REACH", "a network went" + if (Away.hasNetwork(a)) " (another is still up)" else " (none left)")
            main.postDelayed({
                if (a.isFinishing || offline) return@postDelayed
                if (Away.hasNetwork(a)) {
                    Away.recheck(a, soon = true)
                    live.start(2_000)
                    return@postDelayed
                }
                goOffline()
            }, 300)
        }

        override fun onAvailable(network: Network) {
            AppLog.line("REACH", "a network came")
            main.post { if (!a.isFinishing && offline) lookSoon(1_500) }
        }
    }
}
