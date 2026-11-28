package com.musicd.mallard.client

import org.json.JSONObject

/*
 * The diagnostic log (the server's lib/diagnostics.js; owner request: logging "off by default, a user turns it on if they
 * feel they are having trouble", "accessed via the Mallard android app and able to download to the phone"): the server's
 * switch, the app's lines sent to its one log while it is on (AppLog through LogSend), and the log itself to save.
 */

/** Whether diagnostic logging is on (the server's switch, shared with the web app's). */
fun ServerClient.diagnostics(): Boolean = getJson("/api/diagnostics").optBoolean("enabled")

/** The switch set; what the server says it is now. */
fun ServerClient.setDiagnostics(on: Boolean): Boolean = post("/api/diagnostics", JSONObject().put("enabled", on)).optBoolean("enabled")

/** The app's waiting lines ([text], a line each, LogSend's) to the server's log, as "[app <device>]". */
fun ServerClient.sendLog(device: String, text: String) {
    post("/api/diagnostics/app", JSONObject().put("device", device).put("text", text), 20_000)
}

/** The diagnostic log: this server's log, the app's and the page's lines, and Lyrion's own logs, as one text. */
fun ServerClient.diagnosticLog(): ByteArray = bytes("/api/diagnostics/log", 90_000)

object DiagnosticFile {
    /** The saved file's name for [savedAtMs]: "mallard-log-202611281407.txt". */
    fun name(savedAtMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.UK)
        return "mallard-log-" + f.format(java.util.Date(savedAtMs)) + ".txt"
    }

    /** The file: the server's log, then this phone's own recent lines (already without what is the owner's alone). */
    fun join(server: String, phone: String, device: String): String =
        server.trimEnd() + "\n\n===== Mallard on $device: its recent lines =====\n" + phone.lines().joinToString("\n") { LogRedact.line(it) }.trimEnd() + "\n"
}
