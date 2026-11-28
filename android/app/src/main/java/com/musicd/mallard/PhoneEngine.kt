package com.musicd.mallard

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.musicd.mallard.client.InterfaceFeed
import com.musicd.mallard.client.PhoneEngineState
import com.musicd.mallard.client.PhoneTailscale
import com.musicd.mallard.client.Route
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Tailscale on the phone (owner request): the engine (musicdnet/, the server's own, shipped in the APK as
 * libmusicdnet.so) run as a separate program, as Mandarin's TailscaleEngine runs it — no VPN, no Tailscale app. The app
 * drives it over HTTP on 127.0.0.1 with a secret only the two know; it stops when the app's process does (it watches its
 * stdin), and by itself after 150 s unused without a lease (Away renews one while it is wanted).
 *
 * Away from home it forwards 127.0.0.1:[PhoneTailscale.PORT] to the server's tailnet address ([connectAway]); signing
 * in, once, is Settings → Away from home → This phone ([status], [login], [logout]).
 */
internal object PhoneEngine {
    @Volatile private var process: Process? = null
    @Volatile private var port = 0
    @Volatile private var secret = ""
    @Volatile private var appContext: Context? = null
    @Volatile private var readyFor: String? = null
    @Volatile private var freshAt = 0L
    private val stderrTail = java.util.concurrent.ConcurrentLinkedDeque<String>()

    private fun binary(c: Context) = File(c.applicationInfo.nativeLibraryDir, "libmusicdnet.so")
    private fun stateDir(c: Context) = File(c.filesDir, "tailscale")

    /** The engine is in this build (an arm64 phone; not on others). */
    fun installed(c: Context) = binary(c).exists()

    /** Signed in here before: its state on disk, or the app's note that it joined. */
    fun signedIn(c: Context) = Route.ownSignedIn(installed(c), File(stateDir(c), "tailscaled.state").exists(),
        c.getSharedPreferences("mallard", Context.MODE_PRIVATE).getBoolean("engine_joined", false))
    private fun joined(c: Context, on: Boolean) = c.getSharedPreferences("mallard", Context.MODE_PRIVATE).edit().putBoolean("engine_joined", on).apply()

    fun alive() = process?.isAlive == true

    /** Forwarding the port to the server, this app's own engine holding it (until then the port is anyone's). */
    fun forwarding() = alive() && readyFor != null

    /** Started if it isn't running. Blocking; not on the main thread. */
    @Synchronized
    fun ensureRunning(c: Context) {
        appContext = c.applicationContext
        process?.let { if (it.isAlive) return }
        val bin = binary(c)
        check(bin.exists()) { "Tailscale for this phone isn't in this build" }
        secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val dir = stateDir(c).apply { mkdirs() }
        val pb = ProcessBuilder(bin.path, "-dir", dir.path)
        pb.environment()["MUSICDNET_SECRET"] = secret
        pb.environment()["HOME"] = dir.path
        pb.environment()["TMPDIR"] = c.cacheDir.path
        val p = pb.start()
        val line = p.inputStream.bufferedReader().readLine()
            ?: throw IllegalStateException("The engine didn't start: " + p.errorStream.bufferedReader().readText().take(300))
        port = line.removePrefix("CONTROL ").trim().toInt()
        process = p
        readyFor = null
        // Its error output kept flowing (a full pipe would stall it); its end said in the app's log.
        Thread({
            runCatching { p.errorStream.bufferedReader().forEachLine { stderrTail.addLast(it); while (stderrTail.size > 30) stderrTail.pollFirst() } }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) {
                process = null; readyFor = null
                // 3: stopped itself unused; 4: its CPU guard (Mandarin's EngineExit). Anything else is worth a line.
                AppLog.line("REACH", "the phone's Tailscale engine ended ($code)" + if (code == 3 || code == 4) "" else ": " + stderrTail.toList().takeLast(3).joinToString(" | "), anomaly = code != 3 && code != 4)
            }
        }, "musicdnet-stderr").apply { isDaemon = true; start() }
        AppLog.line("REACH", "the phone's Tailscale engine started")
        sendInterfaces(force = true)
    }

    /** Started and asked to join (the sign-in screen). Its status. Blocking. */
    fun status(c: Context): PhoneEngineState {
        ensureRunning(c)
        var j = json("GET", "/status", timeoutMs = 4000)
        if (j.optString("state").let { it == "" || it == "NoState" || it == "Stopped" }) {
            json("POST", "/start", JSONObject().put("Hostname", PhoneTailscale.HOSTNAME).toString(), 30_000)
            j = json("GET", "/status", timeoutMs = 4000)
        }
        val st = PhoneEngineState.parse(j)
        if (st.signedIn) joined(c, true)
        return st
    }

    /** A sign-in link (Tailscale's page, opened in the browser). Blocking. */
    fun login(c: Context): PhoneEngineState {
        status(c)
        return PhoneEngineState.parse(json("POST", "/login", timeoutMs = 20_000))
    }

    /** Signed out: the phone leaves the tailnet; away goes back to waiting for a sign-in. Blocking. */
    fun logout(c: Context): PhoneEngineState {
        ensureRunning(c)
        val st = PhoneEngineState.parse(json("POST", "/logout", timeoutMs = 15_000))
        joined(c, false)
        stop()
        return st
    }

    /**
     * Away: joined, forwarding 127.0.0.1:PORT to [target], and the server answering through it. Blocking (seconds when
     * all is well); not on the main thread. An engine that was running and can't reach the server is started afresh
     * once, at most every three minutes (Mandarin's item 157).
     */
    fun connectAway(c: Context, target: String): Boolean = runCatching {
        if (!signedIn(c)) return false
        val wasAlive = alive()
        if (!join(c, target)) return false
        for (i in 0 until 3) {
            if (json("GET", "/probe", timeoutMs = 10_000).optBoolean("ok")) return true
            if (i > 0) { json("POST", "/down?on=1"); json("POST", "/down?on=0") }
            json("POST", "/rebind", timeoutMs = 12_000)
        }
        if (wasAlive) {
            val now = SystemClock.elapsedRealtime()
            if (!Route.mayRestartEngine(now, freshAt)) return false
            freshAt = now
            stop()
            if (join(c, target) && json("GET", "/probe", timeoutMs = 10_000).optBoolean("ok")) return true
        }
        false
    }.getOrElse { AppLog.line("REACH", "away through the phone's Tailscale: ${it.message}", anomaly = true); false }

    private fun join(c: Context, target: String): Boolean {
        if (readyFor == target && alive()) return true
        ensureRunning(c)
        sendInterfaces()
        json("POST", "/start", JSONObject().put("Hostname", PhoneTailscale.HOSTNAME).toString(), 30_000)
        var running = false
        for (i in 0 until 40) {
            when (json("GET", "/status", timeoutMs = 4000).optString("state")) {
                "Running" -> { running = true; joined(c, true) }
                "NeedsLogin", "NeedsMachineAuth" -> {
                    AppLog.line("REACH", "Tailscale on this phone needs signing in again: Settings → Away from home", anomaly = true)
                    joined(c, false)
                    return false
                }
            }
            if (running) break
            Thread.sleep(250)
        }
        if (!running) return false
        if (json("POST", "/forward?target=$target&port=${PhoneTailscale.PORT}").optInt("_status") >= 400) return false
        readyFor = target
        json("POST", "/down?on=0")
        return true
    }

    /** The lease: kept [seconds] more (0 lets it stop itself once unused). */
    fun keep(seconds: Int) { if (alive()) runCatching { call("POST", "/keep?s=$seconds", timeoutMs = 3000) } }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        readyFor = null
        p?.let { runCatching { it.outputStream.close() }; it.destroy(); runCatching { it.waitFor(3, TimeUnit.SECONDS) } }
    }

    // The phone's interfaces and its default route, for the engine (Android 11+ won't let it list them itself); sent
    // only when the list differs, at most every ten seconds (core's InterfaceFeed).
    private val feed = InterfaceFeed { SystemClock.elapsedRealtime() }
    private val later = Handler(Looper.getMainLooper())
    @Volatile private var laterPending = false

    fun sendInterfaces(force: Boolean = false) {
        if (!alive()) return
        val text = interfacesText()
        when (val action = synchronized(feed) { if (force) feed.reset(); feed.offer(text) }) {
            InterfaceFeed.Action.Skip -> return
            is InterfaceFeed.Action.Later -> {
                if (laterPending) return
                laterPending = true
                later.postDelayed({ laterPending = false; Thread({ sendInterfaces() }, "musicdnet-interfaces").apply { isDaemon = true; start() } }, action.inMs)
            }
            InterfaceFeed.Action.Send -> runCatching { call("POST", "/interfaces", text) }
        }
    }

    private fun interfacesText(): String {
        val sb = StringBuilder()
        appContext?.let { c ->
            runCatching { val cm = c.getSystemService(ConnectivityManager::class.java); cm.getLinkProperties(cm.activeNetwork)?.interfaceName }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { sb.append("default ").append(it).append('\n') }
        }
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                val flags = ArrayList<String>()
                if (ni.isUp) flags += "up"
                if (ni.isLoopback) flags += "loopback"
                if (ni.supportsMulticast()) flags += "multicast"
                if (flags.isEmpty()) flags += "none"
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val host = when (val a = ia.address) { is Inet6Address -> a.hostAddress?.substringBefore('%'); is Inet4Address -> a.hostAddress; else -> null }
                    host?.let { "$it/${ia.networkPrefixLength}" }
                }
                sb.append(ni.name).append(' ').append(ni.index).append(' ').append(runCatching { ni.mtu }.getOrDefault(1500))
                    .append(' ').append(flags.joinToString("|")).append(' ').append(addrs.joinToString(",")).append('\n')
            }
        }
        return sb.toString()
    }

    /** One call to the engine: (HTTP status, body). Blocking. */
    private fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 15_000): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = timeoutMs
        c.setRequestProperty("X-Secret", secret)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        try {
            val code = c.responseCode
            return code to ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: "")
        } finally { c.disconnect() }
    }

    private fun json(method: String, path: String, body: String? = null, timeoutMs: Int = 15_000): JSONObject {
        val (code, text) = call(method, path, body, timeoutMs)
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("error", text)).put("_status", code)
    }
}
