package com.felix.faze

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONObject

class SttError(message: String) : Exception(message)

object Wav {
    fun fromPcm(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int32(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun int16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int32(16)
        int16(1)
        int16(1)
        int32(sampleRate)
        int32(sampleRate * 2)
        int16(2)
        int16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    class Multipart(val contentType: String, val body: ByteArray)

    fun multipart(boundary: String, fields: Map<String, String>, fileField: String, fileName: String, fileType: String, file: ByteArray): Multipart {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: $fileType\r\n\r\n")
        out.write(file)
        text("\r\n--$boundary--\r\n")
        return Multipart("multipart/form-data; boundary=$boundary", out.toByteArray())
    }
}

/** Speech to text with Groq's Whisper: one upload, the words come back. */
class Stt(private val transport: Transport, private val key: () -> String, private val model: String = "whisper-large-v3-turbo") {
    fun transcribe(wav: ByteArray): String {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) throw SttError("Groq has no key (add it under Keys)")
        val m = Wav.multipart("----faze" + System.nanoTime(), mapOf("model" to model, "response_format" to "json", "temperature" to "0"),
            "file", "speech.wav", "audio/wav", wav)
        val r = try {
            transport.post("${Groq.BASE}/audio/transcriptions", mapOf("Authorization" to "Bearer $apiKey"), m.body, m.contentType, 30000)
        } catch (e: IOException) {
            throw SttError("speech recognition: network error (${e.javaClass.simpleName})")
        }
        if (r.status != 200) throw SttError("speech recognition: " + (classify(r.status, r.body, r.headers).message ?: "failed (${r.status})"))
        return try {
            JSONObject(r.body).optString("text", "").trim()
        } catch (e: Exception) {
            throw SttError("speech recognition sent something unreadable")
        }
    }
}
