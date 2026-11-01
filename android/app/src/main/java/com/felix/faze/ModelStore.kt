package com.felix.faze

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The small offline speech model the wake word listens with. Downloaded once, over HTTPS, into the app's private folder. */
object ModelStore {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    private fun dir(context: Context) = File(context.filesDir, "vosk")

    fun ready(context: Context): File? = ZipSafe.modelRoot(dir(context))

    /** Blocking: call from a background thread. [onProgress] gets 0..99. */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        val tmp = File(context.filesDir, "vosk.tmp")
        tmp.deleteRecursively()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            val counting = CountingInputStream(conn.inputStream)
            ZipSafe.extract(counting, tmp) { onProgress(if (total > 0) (counting.count * 100 / total).toInt().coerceIn(0, 99) else 0) }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        } finally {
            conn.disconnect()
        }
        if (ZipSafe.modelRoot(tmp) == null) {
            tmp.deleteRecursively()
            throw IOException("that download is not a speech model")
        }
        dir(context).deleteRecursively()
        if (!tmp.renameTo(dir(context))) throw IOException("couldn't save the model")
    }
}
