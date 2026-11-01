package com.felix.faze

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavTest {
    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    @Test
    fun theWavHeaderIsRight() {
        val pcm = ByteArray(3200) { (it % 7).toByte() }
        val wav = Wav.fromPcm(pcm, 16000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(16, le32(wav, 16))
        assertEquals(1, wav[20].toInt())
        assertEquals(1, wav[22].toInt())
        assertEquals(16000, le32(wav, 24))
        assertEquals(32000, le32(wav, 28))
        assertEquals(2, wav[32].toInt())
        assertEquals(16, wav[34].toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40))
        assertEquals(pcm.toList(), wav.drop(44))
    }

    @Test
    fun theMultipartBodyHasEveryPart() {
        val m = Wav.multipart("BOUND", mapOf("model" to "whisper-large-v3-turbo", "temperature" to "0"), "file", "speech.wav", "audio/wav", byteArrayOf(1, 2, 3))
        assertEquals("multipart/form-data; boundary=BOUND", m.contentType)
        val s = String(m.body, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n"))
        assertTrue(s.contains("name=\"temperature\"\r\n\r\n0\r\n"))
        assertTrue(s.contains("name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n\u0001\u0002\u0003\r\n--BOUND--\r\n"))
        assertTrue(s.endsWith("--BOUND--\r\n"))
    }

    private val transport = FakeTransport()

    private fun stt(key: String = "gsk_test") = Stt(transport, { key })

    @Test
    fun transcribingReturnsTheTrimmedWords() {
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), "{\"text\": \"  what time is it \"}") }
        assertEquals("what time is it", stt().transcribe(byteArrayOf(1)))
        val call = transport.calls[0]
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", call.url)
        assertEquals("Bearer gsk_test", call.headers["Authorization"])
        assertTrue(call.body.contains("whisper-large-v3-turbo"))
    }

    @Test
    fun transcribingProblemsAreExplained() {
        fun fails(contains: String, block: () -> Unit) {
            try {
                block()
                fail("expected an SttError")
            } catch (e: SttError) {
                assertTrue(e.message, e.message!!.contains(contains))
            }
        }
        fails("no key") { stt("").transcribe(byteArrayOf(1)) }
        transport.onPost = { _, _ -> HttpResult(401, emptyMap(), "{\"error\": {\"message\": \"Invalid API Key\"}}") }
        fails("the key was refused") { stt().transcribe(byteArrayOf(1)) }
        transport.onPost = { _, _ -> throw IOException("x") }
        fails("network error") { stt().transcribe(byteArrayOf(1)) }
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), "<html>") }
        fails("unreadable") { stt().transcribe(byteArrayOf(1)) }
    }
}
