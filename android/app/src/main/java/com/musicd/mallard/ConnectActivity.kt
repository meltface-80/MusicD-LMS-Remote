package com.musicd.mallard

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.musicd.mallard.client.Finder
import com.musicd.mallard.client.ServerAddress
import com.musicd.mallard.client.ServerClient
import java.util.concurrent.Executors

/**
 * Which MusicD Server to use.
 *
 * The port is the one thing a person reliably knows — it is in the address
 * they open the server at — so that is the first box, filled in with the default. The address
 * is optional: leave it empty and the app looks for a server on that port
 * across this phone's network, and connects if it finds exactly one.
 */
class ConnectActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()

    private lateinit var portBox: EditText
    private lateinit var hostBox: EditText
    private lateinit var status: TextView
    private lateinit var found: LinearLayout
    private lateinit var connect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holdUprightOnPhones()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(40), px(24), px(24))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFBFC7CE.toInt())
            textSize = 13f
            setPadding(0, px(18), 0, px(6))
        }

        col.addView(TextView(this).apply {
            text = "Mallard"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Connect to your MusicD LMS Remote server (port 3390 unless you changed it). " +
                "Leave the address empty and this phone will look for it on your Wi-Fi."
            setTextColor(0xFFBFC7CE.toInt())
            textSize = 15f
            setPadding(0, px(8), 0, 0)
        })

        col.addView(label("Port"))
        portBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.lastPort(this@ConnectActivity).toString())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        col.addView(portBox)

        col.addView(label("Address (optional)"))
        hostBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "e.g. 192.168.1.10"
            setHintTextColor(0xFF6B737A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            imeOptions = EditorInfo.IME_ACTION_GO
            Store.server(this@ConnectActivity)?.let { setText(it.host) }
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { go(); true } else false
            }
        }
        col.addView(hostBox)

        connect = Button(this).apply {
            text = "Connect"
            setOnClickListener { go() }
        }
        col.addView(connect, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(24) })

        status = TextView(this).apply {
            setTextColor(0xFFBFC7CE.toInt())
            textSize = 14f
            setPadding(0, px(16), 0, px(8))
        }
        col.addView(status)

        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(found)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0E1012.toInt())
            isFillViewport = true
            addView(col)
        }
        Insets.pad(scroll)
        setContentView(scroll)
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }

    private fun setBusy(busy: Boolean, message: String) {
        connect.isEnabled = !busy
        status.text = message
    }

    private fun go() {
        val port = try {
            ServerAddress.portOf(portBox.text.toString().ifBlank { ServerAddress.DEFAULT_PORT.toString() })
        } catch (e: IllegalArgumentException) {
            status.text = e.message
            return
        }
        found.removeAllViews()
        val hostText = hostBox.text.toString().trim()
        if (hostText.isNotEmpty()) {
            val address = try {
                ServerAddress.parse(hostText, port.toString())
            } catch (e: IllegalArgumentException) {
                status.text = e.message
                return
            }
            tryConnect(address)
        } else {
            search(port)
        }
    }

    private fun tryConnect(address: ServerAddress) {
        setBusy(true, "Connecting to $address…")
        work.execute {
            val result = runCatching { ServerClient(address, 4000).health() }
            main.post {
                if (isFinishing) return@post
                result.onSuccess { h ->
                    Store.setServer(this, address)
                    setBusy(false, "Connected — ${h.rooms} players" + (if (h.lms.isNotEmpty()) " on ${h.lms}." else "."))
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }.onFailure { e ->
                    setBusy(false, "Couldn't reach MusicD LMS Remote at $address.\n\n" +
                        (e.message ?: e.javaClass.simpleName) +
                        "\n\nCheck the address and port, that the server is running, and that this phone is on the same network.")
                }
            }
        }
    }

    private fun search(port: Int) {
        val mine = Net.localIpv4()
        if (mine.isEmpty()) {
            status.text = "This phone isn't on a local network. Join your Wi-Fi, or type the server's address."
            return
        }
        setBusy(true, "Looking for MusicD LMS Remote on port $port…")
        work.execute {
            val hosts = mine.take(2).flatMap { runCatching { Finder.candidates(it) }.getOrDefault(emptyList()) }.distinct()
            val hits = Finder.find(hosts, onFound = { h -> main.post { addFound(ServerAddress(h, port)) } }) { h ->
                runCatching { ServerClient(ServerAddress(h, port), 900).health(); true }.getOrDefault(false)
            }
            main.post {
                if (isFinishing) return@post
                when (hits.size) {
                    0 -> setBusy(false, "No MusicD LMS Remote answered on port $port. Type its address instead.")
                    1 -> tryConnect(ServerAddress(hits[0], port))
                    else -> setBusy(false, "Found ${hits.size} servers — pick one.")
                }
            }
        }
    }

    private fun addFound(address: ServerAddress) {
        if (isFinishing) return
        for (i in 0 until found.childCount) if ((found.getChildAt(i).tag as? String) == address.toString()) return
        found.addView(Button(this).apply {
            tag = address.toString()
            text = address.toString()
            gravity = Gravity.CENTER
            setOnClickListener { tryConnect(address) }
        })
        found.visibility = View.VISIBLE
    }
}
