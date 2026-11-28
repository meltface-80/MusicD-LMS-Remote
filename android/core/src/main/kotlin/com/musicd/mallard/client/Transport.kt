package com.musicd.mallard.client

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/* How a ServerClient's requests are answered: over HTTP by the server ([HttpTransport]); tests answer in-process. */

/** Answers a [ServerClient]'s requests. */
interface Transport {
    /** [method] (GET, POST, PATCH, DELETE) on the server's [path], with [body] for those that carry one: the JSON answer, or a [ServerClient.ServerException]. */
    fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject

    /** The bytes at the server's [path] (a cover, the labels' scan log), or at a whole address elsewhere (a service's cover). */
    fun bytes(path: String, timeout: Int): ByteArray

    /** [bytes] of [type] sent to the server's [path] (POST, item 252: a room correction's WAV): the JSON answer. Only over the network. */
    fun upload(path: String, bytes: ByteArray, type: String, timeout: Int): JSONObject =
        throw ServerClient.ServerException(503, "Only with the server")
}

/** The server, over HTTP at [baseUrl]. Plain HttpURLConnection, no library. MusicD LMS Remote has no sign-in. */
class HttpTransport(private val baseUrl: String) : Transport {
    override fun json(method: String, path0: String, body0: JSONObject?, timeout: Int): JSONObject {
        // Away, every screen's room is this phone's (AwayRooms, Mandarin's AwayRoom).
        val own = ThisPhone.awayRoom()
        val path = if (own.isNullOrEmpty()) path0 else AwayRooms.path(path0, own)
        val body = if (own.isNullOrEmpty()) body0 else AwayRooms.body(path0, body0, own)
        val c = open(baseUrl + path, timeout)
        try {
            if (method != "GET") method(c, method)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    override fun upload(path: String, bytes: ByteArray, type: String, timeout: Int): JSONObject {
        val c = open(baseUrl + path, timeout)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    override fun bytes(path: String, timeout: Int): ByteArray {
        val c = open(if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + path, timeout)
        try {
            if (c.responseCode != 200) throw ServerClient.ServerException(c.responseCode, "HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, timeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(timeout, 5000)
            readTimeout = timeout
            useCaches = false
            setRequestProperty("Accept", "application/json")
            // Which phone is asking, so the server can offer its own player as "This phone" at home (ThisPhone).
            ThisPhone.deviceId?.let { setRequestProperty("X-Mallard-Device", it) }
        }

    /** Android's connection takes PATCH (Settings → Audio Devices, item 89); one that refuses it has it set underneath. */
    private fun method(c: HttpURLConnection, method: String) {
        try { c.requestMethod = method } catch (e: java.net.ProtocolException) {
            HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(c, method)
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { s ->
            val out = ByteArrayOutputStream()
            s.copyTo(out)
            out.toString("UTF-8")
        } ?: ""
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (code >= 400) {
            throw ServerClient.ServerException(code, j?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
        }
        return j ?: throw ServerClient.ServerException(code, "The server answered with something that isn't JSON")
    }
}

