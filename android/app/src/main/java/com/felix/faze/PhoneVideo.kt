package com.felix.faze

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Cuts a byte stream of H.264 (Annex B, 00 00 01 start codes) into access units, one per picture, each with whether it is a keyframe. The same rules as the
 * PC's H264Splitter (pc/faze_pc/stream.py): a unit ends when the next begins (a delimiter, SPS/PPS/SEI after picture data, or a slice that starts at macroblock 0),
 * or on [flush] when the encoder has gone quiet, which keeps the delay down.
 */
class H264Splitter {
    class AccessUnit(val data: ByteArray, val key: Boolean)

    private var buf = ByteArray(0)
    private val cur = ByteArrayOutputStream()
    private var hasVcl = false
    private var key = false

    private fun marks(b: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                out.add((if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i) to i + 3)
                i += 3
            } else i++
        }
        return out
    }

    private fun begins(typ: Int, first: Int?) = hasVcl && (typ in 6..9 || ((typ == 1 || typ == 5) && first != null && first and 0x80 != 0))

    private fun take(out: MutableList<AccessUnit>) {
        if (hasVcl && cur.size() > 0) out.add(AccessUnit(cur.toByteArray(), key))
        cur.reset(); hasVcl = false; key = false
    }

    private fun consume(nal: ByteArray, out: MutableList<AccessUnit>) {
        if (nal.size < 4) return
        val one = nal.indexOf(1.toByte())
        val head = one + 1
        if (one < 0 || head >= nal.size) return
        val typ = nal[head].toInt() and 0x1F
        val first = if (head + 1 < nal.size) nal[head + 1].toInt() and 0xFF else null
        if (begins(typ, first)) take(out)
        cur.write(nal)
        if (typ == 1 || typ == 5) { hasVcl = true; if (typ == 5) key = true }
    }

    @Synchronized fun feed(data: ByteArray): List<AccessUnit> {
        buf += data
        val out = ArrayList<AccessUnit>()
        val m = marks(buf)
        if (m.size >= 2) {
            for (k in 0 until m.size - 1) consume(buf.copyOfRange(m[k].first, m[k + 1].first), out)
            buf = buf.copyOfRange(m.last().first, buf.size)
        }
        if (buf.size > MAX_BUF) { buf = ByteArray(0); cur.reset(); hasVcl = false; key = false }
        return out
    }

    /** The encoder has been quiet: what is waiting is a whole picture. */
    @Synchronized fun flush(): List<AccessUnit> {
        val out = ArrayList<AccessUnit>()
        if (buf.isNotEmpty()) { consume(buf, out); buf = ByteArray(0) }
        take(out)
        return out
    }

    companion object { const val MAX_BUF = 8 * 1024 * 1024 }
}

/** The other phone's live video: pieces of H.264 arrive as screen_h264 notices, are cut into pictures and queued for the decoder. Never answers. */
class PhoneVideoFeed(private val frames: PcFrames, private val quietMs: Long = 25) {
    private val splitter = H264Splitter()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "phone-video").also { it.isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    fun handle(msg: JSONObject): JSONObject? {
        when (msg.optString("t")) {
            "screen_h264" -> {
                val data = try { java.util.Base64.getDecoder().decode(msg.optString("data")) } catch (e: IllegalArgumentException) { return null }
                for (u in splitter.feed(data)) frames.push(u.data, u.key)
                synchronized(this) {
                    pending?.cancel(false)
                    pending = timer.schedule({ for (u in splitter.flush()) frames.push(u.data, u.key) }, quietMs, TimeUnit.MILLISECONDS)
                }
            }
            "screen_stream_error" -> frames.fail(msg.optString("message", "the phone's live video stopped"))
        }
        return null
    }
}
