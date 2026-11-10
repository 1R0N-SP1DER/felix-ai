package com.felix.faze

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Looks at the screen and answers a question about it. */
fun interface Eyes {
    fun look(question: String): String
}

/**
 * Sees the screen the way a person would: a screenshot goes to a vision model (an image in the usual chat format) and its answer comes back
 * as text. This is the fallback for what the UI tree cannot say: pictures, games, video, apps that hide their contents.
 */
class ScreenEyes(private val chat: ChatApi, private val model: () -> String, private val capture: () -> ByteArray) : Eyes {
    override fun look(question: String): String {
        val jpeg = capture()
        if (jpeg.isEmpty()) throw ToolFailure("no screenshot came back (is root granted?)")
        val answer = try {
            chat.chat(model(), messages(question, jpeg), null, { _ -> }, { false }).content.trim()
        } catch (e: RemoteError) {
            throw ToolFailure("the vision model failed: ${e.message}")
        }
        if (answer.isEmpty()) throw ToolFailure("the vision model gave no answer")
        return answer
    }

    companion object {
        /** Free on Groq, accepts images (3 per request), and uses the same key as the chat. */
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
        const val SYSTEM = "You are the eyes of a phone assistant. You are shown a screenshot of the phone. Answer the question about it briefly and exactly. " +
            "Read text as written, name buttons and fields you can see, and say roughly where they are (top, middle, bottom, left, right). If you cannot tell, say so."

        fun messages(question: String, jpeg: ByteArray): JSONArray {
            val q = question.trim().ifEmpty { "What is on this screen?" }
            val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
            val user = JSONArray()
                .put(JSONObject().put("type", "text").put("text", q))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
            return JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", user))
        }
    }
}

/** Root screenshot, shrunk to what a vision model needs (a long side of 1280 px, JPEG), so the upload is small and quick. */
class AndroidScreenCapture {
    fun jpeg(maxSide: Int = 1280): ByteArray {
        val pb = ProcessBuilder("su", "-c", "screencap -p")
        pb.redirectError(java.io.File("/dev/null"))
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("LD_LIBRARY_PATH")
        val p = try { pb.start() } catch (e: Exception) { throw ToolFailure("root is not available, so the screen can't be captured") }
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.also { it.start() }
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); throw ToolFailure("the screenshot timed out") }
        reader.join(1000)
        val png = out.toByteArray()
        if (png.size < 100) throw ToolFailure("no screenshot came back (is root granted?)")
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw ToolFailure("the screenshot could not be read")
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val jpg = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, jpg)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return jpg.toByteArray()
    }
}
