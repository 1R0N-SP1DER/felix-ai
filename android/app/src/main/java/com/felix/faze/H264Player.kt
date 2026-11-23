package com.felix.faze

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays the PC's H.264 stream with the phone's hardware decoder, straight onto a Surface. Units come from [PcFrames.nextUnit]; each is shown the moment it is decoded
 * (no pacing, no buffering: the point is the least delay). Anything that goes wrong is reported once through onError and playing stops.
 */
class H264Player(private val frames: PcFrames, private val surface: Surface, private val width: Int, private val height: Int, private val onSize: (Int, Int) -> Unit, private val onError: (String) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "h264-play").also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        thread = null
    }

    private fun run() {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)       // real time
            format.setInteger("low-latency", 1)                  // Android 11 and later; older versions ignore it
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pending: PcFrames.Unit? = null
            while (running) {
                if (pending == null) pending = frames.nextUnit()
                val p = pending
                if (p != null) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        if (p.data.size <= buf.capacity()) {
                            buf.put(p.data)
                            codec.queueInputBuffer(i, 0, p.data.size, System.nanoTime() / 1000, 0)
                        } else codec.queueInputBuffer(i, 0, 0, 0, 0)
                        pending = null
                    }
                }
                val o = codec.dequeueOutputBuffer(info, if (pending == null) 4_000 else 1_000)
                when {
                    o >= 0 -> codec.releaseOutputBuffer(o, true)
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        onSize(w, h)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
        }
    }
}

/**
 * The PC picture drawn by the hardware decoder. shape is the same stretch / fill / fit the JPEG picture has; the video's own proportions arrive from the decoder.
 * guessW and guessH only seed the decoder: it adapts to the real size by itself.
 */
@Composable
fun VideoSurface(frames: PcFrames, guessW: Int, guessH: Int, shape: String, onError: (String) -> Unit) {
    var aspect by remember { mutableStateOf(guessW.toFloat() / guessH) }
    val player = remember { arrayOfNulls<H264Player>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.stop(); player[0] = null } }
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        val cw = with(d) { maxWidth.toPx() }
        val ch = with(d) { maxHeight.toPx() }
        val (w, h) = when (shape) {
            "fit" -> if (cw / ch > aspect) (ch * aspect to ch) else (cw to cw / aspect)
            "fill" -> if (cw / ch > aspect) (cw to cw / aspect) else (ch * aspect to ch)
            else -> cw to ch
        }
        AndroidView(
            modifier = Modifier.requiredSize(with(d) { w.toDp() }, with(d) { h.toDp() }),
            factory = { c ->
                SurfaceView(c).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player[0]?.stop()
                            player[0] = H264Player(frames, holder.surface, guessW, guessH, { nw, nh -> if (nh > 0) sv.post { aspect = nw.toFloat() / nh } }, { m -> sv.post { onError(m) } }).also { it.start() }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { player[0]?.stop(); player[0] = null }
                    })
                }
            },
        )
    }
}
