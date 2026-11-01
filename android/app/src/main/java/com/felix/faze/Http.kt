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

    /** POST JSON and hand each response line to [onLine] until it returns false. The body is filled only for non-200. */
    fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean): HttpResult
}

class UrlTransport : Transport {
    private fun open(url: String, method: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
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
            c.disconnect()
        }
    }
}
