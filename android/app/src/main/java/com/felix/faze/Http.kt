package com.felix.faze

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)

/** The only place the app touches the network, so everything above it can be tested with a fake. */
interface Transport {
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int = 15000): HttpResult

    fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int = 30000): HttpResult

    /** The raw bytes of a picture (or null when it is not an image, is too big, or the answer is not 200). */
    fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Int = 15000, maxBytes: Int = 6_000_000): ByteArray? = null

    /** POST JSON and hand each response line to [onLine] until it returns false. The body is filled only for non-200. */
    fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean): HttpResult

    /** Cuts every request that is in flight right now, so a blocked read ends at once instead of waiting for the server. */
    fun abortAll() {}
}

class UrlTransport : Transport {
    private val live = java.util.Collections.synchronizedSet(HashSet<HttpURLConnection>())

    override fun abortAll() {
        val all = synchronized(live) { ArrayList(live) }
        for (c in all) try { c.disconnect() } catch (e: Exception) { /* already closed */ }
    }

    private fun open(url: String, method: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        live.add(c)
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        for ((k, v) in headers) c.setRequestProperty(k, v)
        return c
    }

    private fun headerMap(c: HttpURLConnection): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in c.headerFields) {
            if (k != null && v.isNotEmpty()) out[k.lowercase()] = v[0]
        }
        return out
    }

    private fun readAll(stream: InputStream?): String = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
        val c = open(url, "GET", headers, timeoutMs)
        try {
            val code = c.responseCode
            return HttpResult(code, headerMap(c), readAll(if (code in 200..299) c.inputStream else c.errorStream))
        } finally {
            live.remove(c)
            c.disconnect()
        }
    }

    override fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int): ByteArray? {
        val c = open(url, "GET", headers, timeoutMs)
        try {
            if (c.responseCode != 200) return null
            if (c.contentType?.lowercase()?.startsWith("image/") != true) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
            }
            return out.toByteArray()
        } finally {
            live.remove(c)
            c.disconnect()
        }
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int): HttpResult {
        val c = open(url, "POST", headers, timeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            return HttpResult(code, headerMap(c), readAll(if (code in 200..299) c.inputStream else c.errorStream))
        } finally {
            live.remove(c)
            c.disconnect()
        }
    }

    override fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean): HttpResult {
        val c = open(url, "POST", headers, timeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) return HttpResult(code, headerMap(c), readAll(c.errorStream))
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
            return HttpResult(code, headerMap(c), "")
        } finally {
            live.remove(c)
            c.disconnect()
        }
    }
}
