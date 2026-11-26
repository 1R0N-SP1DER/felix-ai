package com.felix.faze

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The screen-capture permission the person gave once (the same one phone sound uses, see SoundService). It lets Faze record the screen as H.264 without root. */
object ProjectionVideo {
    @Volatile private var projection: MediaProjection? = null

    fun ready() = projection != null
    fun attach(p: MediaProjection) { projection = p }
    fun clear() { projection = null }
    fun current(): MediaProjection? = projection
}

/** Bytes handed over by one thread and read by another; reading blocks until something arrives and ends (-1) once it is finished and empty. */
class QueueInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var cur: ByteArray? = null
    private var pos = 0
    @Volatile private var done = false

    fun put(b: ByteArray) { if (!done) queue.put(b) }
    fun finish() { done = true }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            var c = cur
            if (c == null || pos >= c.size) {
                c = queue.poll(200, TimeUnit.MILLISECONDS)
                if (c == null) { if (done && queue.isEmpty()) return -1 else continue }
                cur = c; pos = 0
            }
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }
    }
}

/** Live video of this screen without root: the screen goes onto a virtual display that feeds a hardware H.264 encoder; the encoded pictures come out as a byte stream. */
class ProjectionSource(context: Context, w: Int, h: Int) : ScreenStream.Source {
    private val out = QueueInputStream()
    override val input: InputStream get() = out
    @Volatile private var failure = ""
    @Volatile private var running = true
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null

    init {
        val p = ProjectionVideo.current() ?: throw IllegalStateException("Screen capture is not allowed yet. On this phone: Faze > Settings > Devices > Allow screen capture.")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 4).coerceIn(1_500_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L) // a still screen still sends a picture now and then
            setInteger("prepend-sps-pps-to-idr-frames", 1)                 // every keyframe can start the video
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            display = try {
                p.createVirtualDisplay("faze-stream", w, h, context.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            } catch (e: SecurityException) {
                throw IllegalStateException("Android allows one screen capture for each permission. Allow screen capture again in Faze > Settings > Devices.")
            }
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) { }
            codec = null
            throw e
        }
        Thread({ pump(c) }, "projection-encode").also { it.isDaemon = true }.start()
    }

    private fun pump(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = c.dequeueOutputBuffer(info, 100_000)
                if (i >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(i)
                        if (buf != null) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                            out.put(bytes)
                        }
                    }
                    c.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            out.finish()
        }
    }

    override fun close() {
        running = false
        try { display?.release() } catch (_: Exception) { }
        try { codec?.stop(); codec?.release() } catch (_: Exception) { }
        out.finish()
    }

    override fun error(): String = failure
}
