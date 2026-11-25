package com.felix.faze

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns shared text into something the computer's inbox takes: a link becomes a .url shortcut, anything else a .txt. Pure, so it can be tested. */
object ShareText {
    fun isLink(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && t.none { it.isWhitespace() }
    }

    /** (file name, file contents) for some shared text. [stamp] is a date like 20261125-053200 so names do not clash. */
    fun file(text: String, stamp: String): Pair<String, ByteArray> {
        val t = text.trim()
        return if (isLink(t)) "link-$stamp.url" to "[InternetShortcut]\nURL=$t\n".toByteArray()
        else "text-$stamp.txt" to (t + "\n").toByteArray()
    }
}

/**
 * Faze in Android's Share menu: share a photo, a file, some text or a link from any app and it goes to the connected computer's Faze inbox
 * (the same place Send a file uses). No screen of its own: it sends and says how it went in a toast.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as FazeApp).core
        val pc = core.link.connected().firstOrNull()
        val items = shared(intent)
        if (pc == null) { toast("No computer is connected. Open Faze and check Devices."); finish(); return }
        if (items.isEmpty()) { toast("Nothing to send."); finish(); return }
        val resolver = contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        Thread {
            val sender = PcSend { m -> core.link.ask(pc, m, 60_000) }
            var sent = 0
            val failed = mutableListOf<String>()
            for ((i, item) in items.withIndex()) {
                val label: String
                try {
                    if (item.uri != null) {
                        label = nameOf(item.uri) ?: "file-$stamp-$i"
                        val input = resolver.openInputStream(item.uri) ?: throw java.io.IOException("could not open it")
                        input.use { sender.send(label, it) }
                    } else {
                        val (name, bytes) = ShareText.file(item.text ?: "", if (items.size > 1) "$stamp-$i" else stamp)
                        label = name
                        sender.send(name, ByteArrayInputStream(bytes))
                    }
                    sent++
                } catch (e: Exception) { failed.add(e.message ?: "failed") }
            }
            runOnUiThread {
                toast(if (failed.isEmpty()) (if (sent == 1) "Sent to your computer." else "Sent $sent items to your computer.") else "Could not send: " + failed.first())
            }
        }.start()
        finish()
    }

    private class Item(val uri: Uri?, val text: String?)

    @Suppress("DEPRECATION")
    private fun shared(i: Intent): List<Item> {
        val out = mutableListOf<Item>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) out.add(Item(u, null))
                else i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(Item(null, it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { out.add(Item(it, null)) }
        }
        return out
    }

    private fun nameOf(uri: Uri): String? =
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null } ?: uri.lastPathSegment

    private fun toast(m: String) = Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show()
}
