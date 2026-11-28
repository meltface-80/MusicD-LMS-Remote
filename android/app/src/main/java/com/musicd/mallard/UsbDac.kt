package com.musicd.mallard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The USB DAC (Mandarin's, Stage 9.1): found, asked about, and understood.
 *
 * Android's audio system mixes everything at one rate, so a DAC on the USB port never hears the stream as it is. The way
 * round it is the app's own USB Audio Class driver (UsbDriver, usb_jni.cc): the DAC is found through Android's USB host
 * access, the user's permission asked once and remembered, and its descriptors read — UAC1 and UAC2, the control
 * interface with its clock and volume control, every streaming alternate setting with its format, channels, depth and
 * rates. Away from home, with USB direct on, the phone's sound goes to it through UsbAudioSink.
 */
object UsbDac {
    private const val TAG = "UsbDac"
    private const val ACTION_PERMISSION = "com.musicd.mallard.USB_PERMISSION"

    /** One streaming alternate setting: a format the DAC takes. */
    class Stream(
        val iface: Int, val alt: Int, val uac: Int, val channels: Int, val subslot: Int, val bits: Int,
        val pcm: Boolean, val dsd: Boolean, val float: Boolean, val rates: List<Int>, val continuous: IntArray?,
        val endpoint: Int, val maxPacket: Int, val interval: Int, val sync: String, val feedback: Int?
    ) {
        fun json(): JSONObject = JSONObject().put("interface", iface).put("alt", alt).put("channels", channels)
            .put("subslot", subslot).put("bits", bits).put("pcm", pcm).put("dsd", dsd).put("float", float)
            .put("rates", JSONArray(rates)).put("continuous", continuous?.let { JSONArray(listOf(it[0], it[1])) } ?: JSONObject.NULL)
            .put("endpoint", endpoint).put("max_packet", maxPacket).put("interval", interval).put("sync", sync)
            .put("feedback", feedback ?: JSONObject.NULL)
    }

    /** What the DAC's descriptors say about it. */
    class Info(
        val name: String, val vendorId: Int, val productId: Int, val serial: String?, val uac: Int,
        val controlInterface: Int, val clocks: List<Int>, val clockRates: List<Int>, val currentRate: Int?,
        val volumeControl: Boolean, val muteControl: Boolean, val featureUnit: Int, val streams: List<Stream>, val speed: String,
        val descriptorsHex: String, val probe: String
    ) {
        fun json(): JSONObject {
            val rates = sortedSetOf<Int>()
            for (s in streams) rates.addAll(s.rates)
            rates.addAll(clockRates)
            val bits = sortedSetOf<Int>(); for (s in streams) if (s.pcm) bits.add(s.bits)
            return JSONObject().put("name", name).put("vendor_id", vendorId).put("product_id", productId)
                .put("serial", serial ?: JSONObject.NULL).put("uac", uac).put("control_interface", controlInterface)
                .put("clocks", JSONArray(clocks)).put("clock_rates", JSONArray(clockRates)).put("current_rate", currentRate ?: JSONObject.NULL)
                .put("volume_control", volumeControl).put("mute_control", muteControl).put("feature_unit", featureUnit).put("speed", speed)
                .put("rates", JSONArray(rates.toList())).put("bits", JSONArray(bits.toList()))
                .put("dsd", streams.any { it.dsd }).put("float", streams.any { it.float })
                .put("sync", streams.map { it.sync }.distinct().joinToString("/"))
                .put("streams", JSONArray().also { a -> for (s in streams) a.put(s.json()) })
                .put("probe", probe)
        }
    }

    @Volatile private var device: UsbDevice? = null
    @Volatile private var info: Info? = null
    @Volatile private var error: String? = null
    @Volatile private var permission: Boolean = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { UsbDriver.forgetVolume(c); refresh(c) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Only the DAC going ends its stream — not a USB stick or a hub's other port.
                    @Suppress("DEPRECATION")
                    val gone = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val ours = UsbDriver.current?.device ?: device
                    if (gone == null || ours == null || gone.deviceName == ours.deviceName) UsbDriver.close()
                    refresh(c)
                }
                ACTION_PERMISSION -> {
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "permission " + (if (granted) "granted" else "refused"))
                    if (!granted) error = "Permission refused — tap Allow to ask again"
                    refresh(c)
                }
            }
        }
    }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    /** Watch the port; read whatever is on it now. */
    fun start(c: Context) {
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= 33) c.applicationContext.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else c.applicationContext.registerReceiver(receiver, f)
            registered = true
        }
        refresh(c)
    }

    /** A device's key, as the choice of one is kept (Store.usbDevice): its vendor and product ids. */
    fun key(d: UsbDevice): String = "${d.vendorId}:${d.productId}"

    /** Every USB audio device Android lists (an Audio Control interface), in its order. */
    fun devices(c: Context): List<UsbDevice> {
        val um = c.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return um.deviceList.values.filter { d -> (0 until d.interfaceCount).any { i -> d.getInterface(i).let { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 } } }
    }

    /**
     * The device's own DAC, where that sits on a USB bridge inside (a FiiO R7's,
     * and other players'): it names the device's maker as its own, where the
     * DAC plugged into the port names another.
     */
    private fun own(d: UsbDevice): Boolean {
        val maker = (d.manufacturerName ?: "").trim()
        val mine = Build.MANUFACTURER.trim()
        return maker.isNotEmpty() && mine.isNotEmpty() && (maker.startsWith(mine, ignoreCase = true) || mine.startsWith(maker, ignoreCase = true))
    }

    /**
     * The DAC on the port: the one chosen (Store.usbDevice) when it is there; else
     * the first that isn't the device's own; else the first. Before, the first
     * listed, which on a FiiO R7 was its own DAC, never the one plugged in.
     */
    private fun find(c: Context): UsbDevice? {
        val all = devices(c)
        if (all.isEmpty()) return null
        val chosen = Store.usbDevice(c)
        if (chosen != null) all.firstOrNull { key(it) == chosen }?.let { return it }
        return all.firstOrNull { !own(it) } ?: all[0]
    }

    /** Look at the port again: found, permitted, read. */
    fun refresh(c: Context) {
        Thread {
            try {
                val um = c.getSystemService(Context.USB_SERVICE) as? UsbManager
                val d = find(c)
                device = d
                if (um == null || d == null) { info = null; permission = false; error = null; changed(); return@Thread }
                permission = um.hasPermission(d)
                if (!permission) { info = null; changed(); return@Thread }
                val conn = um.openDevice(d)
                if (conn == null) { info = null; error = "Couldn't open the device"; changed(); return@Thread }
                try {
                    info = read(d, conn)
                    error = null
                } catch (e: Exception) {
                    Log.w(TAG, "reading the DAC", e)
                    info = null; error = "Couldn't read the DAC: " + (e.message ?: e.javaClass.simpleName)
                } finally { conn.close() }
            } catch (e: Exception) {
                Log.w(TAG, "usb", e); error = e.message
            }
            changed()
        }.start()
    }

    /** Ask the user; Android remembers a yes for this device. */
    fun requestPermission(c: Context) {
        val um = c.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val d = device ?: find(c) ?: return
        if (um.hasPermission(d)) { refresh(c); return }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(c, 0, Intent(ACTION_PERMISSION).setPackage(c.packageName), flags)
        um.requestPermission(d, pi)
    }

    fun device(): UsbDevice? = device
    fun info(): Info? = info

    /** The page's view of it. */
    fun json(c: Context): JSONObject {
        val d = device
        val o = JSONObject().put("attached", d != null).put("permission", permission)
            .put("direct", Store.usbDirect(c)).put("error", error ?: JSONObject.NULL)
            .put("fixed", UsbDriver.fixed(c)).put("limit", Store.usbLimit(c)).put("volume", UsbDriver.volume())
        if (d != null) {
            val maker = d.manufacturerName ?: ""
            val name = d.productName ?: "USB audio device"
            // "AudioQuest DragonFly" already names its maker: not twice.
            o.put("device", JSONObject().put("name", name)
                .put("manufacturer", if (maker.isNotEmpty() && !name.startsWith(maker, ignoreCase = true)) maker else JSONObject.NULL)
                .put("vendor_id", d.vendorId).put("product_id", d.productId).put("key", key(d)))
        }
        // Every USB audio device there is, the one in use marked: more than one, and the page offers the choice (the device's own DAC among them).
        o.put("devices", JSONArray().also { a -> for (x in devices(c)) a.put(JSONObject().put("key", key(x)).put("name", x.productName ?: "USB audio device")
            .put("manufacturer", x.manufacturerName ?: JSONObject.NULL).put("own", own(x)).put("chosen", d != null && key(x) == key(d))) })
        info?.let { o.put("info", it.json()) }
        // The stream, when one is open: what the DAC is being fed, and how it is going.
        UsbDriver.current?.let { s -> o.put("stream", JSONObject().put("rate", s.rate).put("bits", s.bits).put("alt", s.stream.alt)
            .put("volume", UsbDriver.volume()).put("level", UsbDriver.level()).put("software_volume", UsbDriver.softwareGain() != 1f)
            .put("volume_db", UsbDriver.volumeDb()?.let { r -> JSONObject().put("min", r[0]).put("max", r[1]).put("step", r[2]) } ?: JSONObject.NULL)
            .put("stats", UsbDriver.stats() ?: JSONObject.NULL)) }
        UsbDriver.lastError?.let { o.put("stream_error", it) }
        return o
    }

    /** Everything, as text to copy into a report. */
    fun diagnostics(c: Context): String {
        val sb = StringBuilder("Mallard USB DAC diagnostics (app " + BuildConfig.VERSION_NAME + ", Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL + ")\n")
        sb.append(json(c).toString(2)).append("\n")
        info?.let { sb.append("\nDescriptors:\n").append(it.descriptorsHex).append("\n") }
        return sb.toString()
    }

    // --------------------------------------------------------------- reading

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24(b: ByteArray, i: Int) = u16(b, i) or (u8(b, i + 2) shl 16)
    private fun u32(b: ByteArray, i: Int) = u24(b, i) or (u8(b, i + 3) shl 24)

    private class AltBuild(val iface: Int, val alt: Int) {
        var uac = 1; var channels = 0; var subslot = 0; var bits = 0
        var pcm = false; var dsd = false; var float = false
        val rates = ArrayList<Int>(); var continuous: IntArray? = null
        var endpoint = -1; var maxPacket = 0; var interval = 0; var sync = "unknown"; var feedback: Int? = null
        var general = false
    }

    /** The descriptors, walked: UAC1 and UAC2. */
    private fun read(d: UsbDevice, conn: UsbDeviceConnection): Info {
        val raw = conn.rawDescriptors ?: throw IllegalStateException("no descriptors")
        var uac = 1
        var acInterface = -1
        val clocks = ArrayList<Int>()
        var volume = false; var mute = false; var featureUnit = -1
        val alts = ArrayList<AltBuild>()
        var cur: AltBuild? = null
        var inAC = false
        var i = 0
        while (i + 1 < raw.size) {
            val len = u8(raw, i); val type = u8(raw, i + 1)
            if (len < 2 || i + len > raw.size) break
            when (type) {
                4 -> {   // interface
                    val num = u8(raw, i + 2); val alt = u8(raw, i + 3)
                    val cls = u8(raw, i + 5); val sub = u8(raw, i + 6); val proto = u8(raw, i + 7)
                    inAC = cls == 1 && sub == 1
                    if (inAC) { acInterface = num; if (proto == 0x20) uac = 2 }
                    cur = if (cls == 1 && sub == 2) AltBuild(num, alt).also { it.uac = if (proto == 0x20) 2 else uac; alts.add(it) } else null
                }
                0x24 -> {   // class-specific interface
                    val sub = u8(raw, i + 2)
                    if (inAC) {
                        when (sub) {
                            0x01 -> if (len >= 5) { val bcd = u16(raw, i + 3); if (bcd >= 0x200) uac = 2 }
                            0x0A -> if (len >= 6) clocks.add(u8(raw, i + 3))                     // UAC2 clock source
                            0x06 -> {                                                           // feature unit
                                var v = false; var m = false
                                if (uac == 2 && len >= 9) { val ctl = u32(raw, i + 5); if ((ctl shr 2) and 3 == 3) v = true; if (ctl and 3 == 3) m = true }
                                else if (uac == 1 && len >= 7) { val size = u8(raw, i + 5); if (size >= 1) { val ctl = u8(raw, i + 6); if (ctl and 2 != 0) v = true; if (ctl and 1 != 0) m = true } }
                                // The first unit with a volume control is the one driven.
                                if (v && !volume) { volume = true; mute = m; featureUnit = u8(raw, i + 3) }
                            }
                        }
                    } else cur?.let { a ->
                        when (sub) {
                            0x01 -> {   // AS_GENERAL
                                a.general = true
                                if (a.uac == 2 && len >= 16) {
                                    val ftype = u8(raw, i + 5); val formats = u32(raw, i + 6); a.channels = u8(raw, i + 10)
                                    if (ftype == 1) { if (formats and 1 != 0) a.pcm = true; if (formats and 4 != 0) a.float = true; if (formats and 0x80000000.toInt() != 0) a.dsd = true }
                                } else if (len >= 7) {
                                    val tag = u16(raw, i + 5)
                                    if (tag == 1 || tag == 2) a.pcm = true; if (tag == 3) a.float = true
                                }
                            }
                            0x02 -> {   // FORMAT_TYPE
                                if (a.uac == 2 && len >= 6) { a.subslot = u8(raw, i + 4); a.bits = u8(raw, i + 5) }
                                else if (len >= 8) {
                                    a.channels = u8(raw, i + 4); a.subslot = u8(raw, i + 5); a.bits = u8(raw, i + 6)
                                    val n = u8(raw, i + 7)
                                    if (n == 0 && len >= 14) a.continuous = intArrayOf(u24(raw, i + 8), u24(raw, i + 11))
                                    else for (k in 0 until n) { val p = i + 8 + 3 * k; if (p + 3 <= i + len) a.rates.add(u24(raw, p)) }
                                }
                            }
                        }
                    }
                }
                5 -> cur?.let { a ->   // endpoint
                    if (len >= 7) {
                        val addr = u8(raw, i + 2); val attr = u8(raw, i + 3)
                        if (attr and 3 == 1) {
                            val usage = (attr shr 4) and 3
                            if (usage == 1 || (addr and 0x80 != 0 && a.endpoint >= 0)) a.feedback = addr
                            else {
                                a.endpoint = addr; a.maxPacket = u16(raw, i + 4) and 0x7ff; a.interval = u8(raw, i + 6)
                                a.sync = when ((attr shr 2) and 3) { 1 -> "asynchronous"; 2 -> "adaptive"; 3 -> "synchronous"; else -> "none" }
                            }
                        }
                    }
                }
            }
            i += len
        }
        val streams = alts.filter { it.general && it.endpoint >= 0 }.map {
            Stream(it.iface, it.alt, it.uac, it.channels, it.subslot, it.bits, it.pcm, it.dsd, it.float, it.rates, it.continuous,
                it.endpoint, it.maxPacket, it.interval, it.sync, it.feedback)
        }
        // UAC2: the rates live in the clock, asked for with a RANGE request.
        val clockRates = ArrayList<Int>()
        var current: Int? = null
        if (uac == 2 && acInterface >= 0 && clocks.isNotEmpty()) {
            val ac = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id == acInterface }
            val claimed = ac != null && conn.claimInterface(ac, true)
            try {
                for (clk in clocks) {
                    val idx = (clk shl 8) or acInterface
                    val head = ByteArray(2)
                    var n = conn.controlTransfer(0xA1, 0x02, 0x0100, idx, head, 2, 1000)
                    if (n == 2) {
                        val count = u16(head, 0)
                        val buf = ByteArray(2 + 12 * count)
                        n = conn.controlTransfer(0xA1, 0x02, 0x0100, idx, buf, buf.size, 1000)
                        if (n >= 2 + 12 * count) for (k in 0 until count) {
                            val min = u32(buf, 2 + 12 * k); val max = u32(buf, 6 + 12 * k); val res = u32(buf, 10 + 12 * k)
                            if (res == 0 || min == max) { if (!clockRates.contains(min)) clockRates.add(min); if (max != min && !clockRates.contains(max)) clockRates.add(max) }
                            else { var r = min; var guard = 0; while (r <= max && guard++ < 64) { if (!clockRates.contains(r)) clockRates.add(r); r += res } }
                        }
                    }
                    if (current == null) {
                        val c4 = ByteArray(4)
                        if (conn.controlTransfer(0xA1, 0x01, 0x0100, idx, c4, 4, 1000) == 4) current = u32(c4, 0)
                    }
                }
            } finally { if (claimed && ac != null) conn.releaseInterface(ac) }
        }
        clockRates.sort()
        val speed = try { nativeSpeed(conn.fileDescriptor) } catch (e: Throwable) { "unknown" }
        val probe = try { nativeProbe(conn.fileDescriptor) } catch (e: Throwable) { "native: " + (e.message ?: e.javaClass.simpleName) }
        val hex = StringBuilder()
        for (k in raw.indices) { hex.append(String.format("%02x", u8(raw, k))); if (k % 32 == 31) hex.append('\n') else hex.append(' ') }
        return Info(d.productName ?: "USB audio device", d.vendorId, d.productId, runCatching { d.serialNumber }.getOrNull(), uac,
            acInterface, clocks, clockRates, current, volume, mute, featureUnit, streams, speed, hex.toString().trim(), probe)
    }

    // The native side (usb_jni.cc): the device node the connection holds, as
    // the driver will use it — proof that ioctls reach it from the app.
    init { runCatching { System.loadLibrary("mallardusb") }.onFailure { Log.w(TAG, "no native usb library", it) } }
    @JvmStatic private external fun nativeProbe(fd: Int): String
    @JvmStatic private external fun nativeSpeed(fd: Int): String
}
