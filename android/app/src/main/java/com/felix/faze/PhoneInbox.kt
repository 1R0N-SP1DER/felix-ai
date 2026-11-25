package com.felix.faze

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStream
import java.util.Base64

/** Somewhere a received file is written, one piece at a time. */
interface InboxTransfer {
    fun write(bytes: ByteArray)
    /** Completes the file and says where it is for the person ("Downloads/Faze/name"). */
    fun finish(): String
    fun abort()
}

interface InboxStore { fun open(name: String): InboxTransfer }

/** What the person sees when something arrives. */
interface InboxNotify {
    fun file(name: String, where: String)
    fun text(text: String)
    fun link(url: String)
}

/**
 * Things a paired computer sends to this phone (phone_put): a file, some text or a link. Files arrive in order in pieces and are saved to Downloads/Faze;
 * a wrong order, an oversized file or a bad name is refused. Off when the person turned receiving off in Settings.
 */
class PhoneInbox(private val enabled: () -> Boolean, private val store: InboxStore, private val notify: InboxNotify, private val log: (String) -> Unit = {}) {
    companion object {
        const val TYPE = "phone_put"
        const val MAX_SIZE = 1024L * 1024 * 1024
        const val MAX_TEXT = 200_000

        /** A safe file name: no folders, no control characters, not empty, not hidden, not too long. */
        fun safeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val bad = "<>:\"|?*"
            val clean = base.filter { it >= ' ' && it.code != 127 && it !in bad }.trim().trimStart('.')
            return (if (clean.isEmpty()) "file" else clean).take(120)
        }
    }

    private var cur: InboxTransfer? = null
    private var curName = ""
    private var next = 0L
    private var total = 0L

    @Synchronized
    fun handle(msg: JSONObject): JSONObject? {
        if (msg.optString("t") != TYPE) return null
        if (!enabled()) return error("Receiving is off on the phone. Turn it on in Faze settings (Let computers send me things).")
        return try {
            when (msg.optString("kind")) {
                "text" -> text(msg)
                "link" -> link(msg)
                "file" -> file(msg)
                else -> error("Unknown kind of thing to send.")
            }
        } catch (e: Exception) {
            abort()
            log("could not receive: " + (e.message ?: e.javaClass.simpleName))
            error("The phone could not save that: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun text(msg: JSONObject): JSONObject {
        val t = msg.optString("text")
        if (t.isEmpty() || t.length > MAX_TEXT) return error("That text is empty or too long.")
        notify.text(t)
        return ok()
    }

    private fun link(msg: JSONObject): JSONObject {
        val u = msg.optString("text")
        if (!(u.startsWith("http://") || u.startsWith("https://")) || u.length > 4000) return error("That is not a web link.")
        notify.link(u)
        return ok()
    }

    private fun file(msg: JSONObject): JSONObject {
        val offset = msg.optLong("offset", 0)
        val size = msg.optLong("size", -1)
        if (size < 0 || size > MAX_SIZE) return error("That file is too big to send this way (the limit is 1 GB).")
        if (offset == 0L) {
            abort()
            curName = safeName(msg.optString("name"))
            cur = store.open(curName)
            next = 0; total = size
        }
        val t = cur ?: return error("The file transfer was not started in order.")
        if (offset != next) { abort(); return error("The pieces arrived out of order. Send it again.") }
        val data = try { Base64.getDecoder().decode(msg.optString("data")) } catch (e: IllegalArgumentException) { abort(); return error("A piece of the file was damaged.") }
        if (next + data.size > total) { abort(); return error("The file was longer than it said.") }
        t.write(data)
        next += data.size
        if (msg.optBoolean("eof")) {
            if (next != total) { abort(); return error("The file ended early.") }
            val where = t.finish()
            cur = null
            notify.file(curName, where)
            log("received $curName")
        }
        return ok()
    }

    private fun abort() { try { cur?.abort() } catch (_: Exception) { }; cur = null }
    private fun ok() = JSONObject().put("t", "phone_put_ok")
    private fun error(m: String) = JSONObject().put("t", "error").put("message", m)
}

/** Writes into Downloads/Faze with MediaStore, so the file shows up in the Files app and the gallery, and a name that exists gets a number, never overwritten. */
class AndroidInboxStore(private val context: Context) : InboxStore {
    override fun open(name: String): InboxTransfer {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Faze")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Android would not create the file")
        val out: OutputStream = resolver.openOutputStream(uri) ?: throw java.io.IOException("Android would not open the file")
        return object : InboxTransfer {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun finish(): String {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val shown = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                return "Downloads/Faze/$shown"
            }
            override fun abort() { try { out.close() } catch (_: Exception) { }; try { resolver.delete(uri, null, null) } catch (_: Exception) { } }
        }
    }
}

class AndroidInboxNotify(private val context: Context) : InboxNotify {
    private val channel = "faze_inbox"
    private var id = 7000

    private fun post(title: String, body: String, tap: PendingIntent? = null) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "From my computer", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(context, channel).setSmallIcon(context.applicationInfo.icon).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true)
        if (tap != null) b.setContentIntent(tap)
        try { nm.notify(id++, b.build()) } catch (_: SecurityException) { }
    }

    override fun file(name: String, where: String) = post("File from your computer", "$name is in $where")

    override fun text(text: String) {
        // Android only lets an app on screen change the clipboard, so this may not take; the notification keeps the text either way.
        try { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From my computer", text)) } catch (_: Exception) { }
        post("Text from your computer", text)
    }

    override fun link(url: String) {
        val open = PendingIntent.getActivity(context, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        post("Link from your computer", url, open)
    }
}
