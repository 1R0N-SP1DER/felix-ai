package com.felix.faze

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import java.io.File

/** Copies a picked item into the app's own folder. Pictures are shrunk to a sensible size; files are copied as they are (up to 20 MB). */
object AndroidAttachments {
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun copyIn(app: Application, uri: Uri, dir: File, nameHint: String): Attachment {
        val resolver = app.contentResolver
        var name = nameHint
        if (name.isEmpty()) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).orEmpty() }
        }
        val mime = resolver.getType(uri).orEmpty()
        val image = mime.startsWith("image/") || AttachmentReader.isImageName(name)
        if (name.isEmpty()) name = if (image) "picture.jpg" else "file"
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        if (image) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot open")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("not a picture")
            val scale = 1600f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val jpg = File(dir, out.nameWithoutExtension + ".jpg")
            jpg.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            return Attachment(safe, jpg.absolutePath, Attachment.IMAGE)
        }
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(16384)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { out.delete(); throw IllegalStateException("file too large") }
                    o.write(buf, 0, n)
                }
            }
        } ?: throw IllegalStateException("cannot open")
        return Attachment(safe, out.absolutePath, Attachment.FILE)
    }
}

/** Re-encodes a downloaded picture as a smaller JPEG; anything that is not really a picture comes back null. */
fun AndroidAttachments.shrink(bytes: ByteArray, maxSide: Int = 1280): ByteArray? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return out.toByteArray()
}

/** Renders the first pages of a PDF as pictures for the vision model. */
object AndroidPdf {
    fun pages(file: File, max: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(fd).use { r ->
            for (i in 0 until minOf(r.pageCount, max)) {
                r.openPage(i).use { p ->
                    val scale = 1400f / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val jpg = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, jpg)
                    bmp.recycle()
                    out.add(jpg.toByteArray())
                }
            }
        }
        return out
    }
}

/** Loads a picture for the screen: from the phone for local ones, from the web (kept only in memory) for the ones the AI found. */
object PictureLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val transport = UrlTransport()

    fun load(a: Attachment, maxSide: Int): Bitmap? {
        val key = (if (a.isRemote) a.url else a.path) + "@" + maxSide
        cache.get(key)?.let { return it }
        val bytes = bytes(a) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** The picture's own bytes: read from the file, or fetched from the web (https only, size limited). */
    fun bytes(a: Attachment): ByteArray? = try {
        if (a.isRemote) { if (ImageUrl.allowed(a.url)) transport.getBytes(a.url, mapOf("Accept" to "image/*"), 15000, WebImages.MAX_BYTES) else null }
        else File(a.path).takeIf { it.exists() }?.readBytes()
    } catch (e: Exception) { null }

    /** Saves the picture into the phone's Pictures/Faze folder. Returns null when it worked, otherwise why not. */
    fun download(context: Context, a: Attachment): String? {
        val bytes = bytes(a) ?: return "the picture could not be loaded"
        val png = bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "faze_${System.currentTimeMillis()}" + if (png) ".png" else ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Faze")
        }
        return try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return "Android would not create the file"
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return "Android would not open the file"
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
