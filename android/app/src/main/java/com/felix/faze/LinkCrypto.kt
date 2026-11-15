package com.felix.faze

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The byte-level pieces of Faze Link v1. The PC side is pc/faze_pc/link.py and the layout is in pc/docs/link.md; the tests use
 *  values computed by that Python code, so the two stay in step. */
object LinkCrypto {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 10
    const val MAX_FRAME = 1 shl 20
    private val random = SecureRandom()

    fun newCode(): String = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun cleanCode(text: String): String = text.uppercase().filter { it in ALPHABET }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun codeKey(code: String): ByteArray {
        val spec = PBEKeySpec(cleanCode(code).toCharArray(), "faze-pair-v1".toByteArray(), 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun proof(key: ByteArray, label: String, a: ByteArray, b: ByteArray): ByteArray = hmac(key, label.toByteArray() + a + b)

    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt, secret)
        var out = ByteArray(0)
        var block = ByteArray(0)
        var i = 1
        while (out.size < length) {
            block = hmac(prk, block + info + byteArrayOf(i.toByte()))
            out += block
            i++
        }
        return out.copyOf(length)
    }

    fun sessionKey(secret: ByteArray, pcNonce: ByteArray, phoneNonce: ByteArray): ByteArray = hkdf(secret, pcNonce + phoneNonce, "faze-session-v1".toByteArray())

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return c.doFinal(plain)
    }

    /** Null when the message fails its check (wrong key or tampered). */
    fun open(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(data)
    } catch (e: Exception) {
        null
    }

    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "bad hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun writeFrame(out: OutputStream, data: ByteArray) {
        require(data.size <= MAX_FRAME) { "message too large" }
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(data)
        out.flush()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw java.io.EOFException("the other side closed the connection")
            got += r
        }
        return buf
    }

    fun readFrame(input: InputStream): ByteArray {
        val n = ByteBuffer.wrap(readExact(input, 4)).int
        if (n < 0 || n > MAX_FRAME) throw java.io.IOException("message too large")
        return readExact(input, n)
    }
}

/** An encrypted connection. Each direction has a 12-byte nonce: 4-byte tag then an 8-byte counter. A counter that is not exactly the next one fails the check, so a captured message cannot be replayed. */
class LinkSession(private val input: InputStream, private val output: OutputStream, private val key: ByteArray, phone: Boolean) {
    private val sendTag = (if (phone) "PHON" else "PCPC").toByteArray()
    private val recvTag = (if (phone) "PCPC" else "PHON").toByteArray()
    private var sent = 0L
    private var got = 0L

    private fun nonce(tag: ByteArray, n: Long): ByteArray = tag + ByteBuffer.allocate(8).putLong(n).array()

    @Synchronized
    fun send(obj: org.json.JSONObject) {
        val n = nonce(sendTag, sent)
        LinkCrypto.writeFrame(output, LinkCrypto.seal(key, n, obj.toString().toByteArray()))
        sent++
    }

    /** Throws when the message fails its check. */
    fun recv(): org.json.JSONObject {
        val plain = LinkCrypto.open(key, nonce(recvTag, got), LinkCrypto.readFrame(input)) ?: throw java.io.IOException("a message failed its check")
        got++
        return org.json.JSONObject(String(plain))
    }
}
