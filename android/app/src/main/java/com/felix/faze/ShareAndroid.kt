package com.felix.faze

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Shrinks and encodes a picture for the model: a long side of at most [maxSide] px, JPEG. */
object FrameJpeg {
    fun encode(bmp: Bitmap, rotation: Int, maxSide: Int = 768, quality: Int = 60): ByteArray {
        var b = bmp
        if (rotation != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val scale = maxSide.toFloat() / maxOf(b.width, b.height)
        if (scale < 1f) b = Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}

/** The back camera, as pictures only (nothing is shown on screen). Works while the app is open: Android stops a camera in the background. */
class CameraSource(private val context: Context) : VideoSource {
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    override fun start(onFrame: (ByteArray) -> Unit, ready: (String?) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { ready("the camera is not allowed yet"); return }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                var lastFrame = 0L
                analysis.setAnalyzer(worker) { image: ImageProxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastFrame >= 900) {
                            lastFrame = now
                            onFrame(FrameJpeg.encode(image.toBitmap(), image.imageInfo.rotationDegrees))
                        }
                    } catch (e: Exception) {
                        // a bad frame is skipped
                    } finally {
                        image.close()
                    }
                }
                owner.registry.currentState = Lifecycle.State.STARTED
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                provider = p
                ready(null)
            } catch (e: Exception) {
                owner.registry.currentState = Lifecycle.State.DESTROYED
                ready("the camera could not start: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        try { provider?.unbindAll() } catch (e: Exception) { }
        owner.registry.currentState = Lifecycle.State.DESTROYED
        provider = null
        worker.shutdown()
    }
}

/** The screen, a picture every second or so (root screenshots, the same way "look at screen" works). Faze's own screen shows when it is open. */
class ScreenSource : VideoSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(onFrame: (ByteArray) -> Unit, ready: (String?) -> Unit) {
        running = true
        thread = Thread {
            // one picture first, so "root is not available" shows at once rather than as a silent share
            try {
                onFrame(AndroidScreenCapture().jpeg(768))
            } catch (e: Exception) {
                running = false
                ready((e as? ToolFailure)?.message ?: "the screen could not be captured")
                return@Thread
            }
            ready(null)
            var fails = 0
            while (running) {
                try {
                    Thread.sleep(1200)
                    if (!running) break
                    onFrame(AndroidScreenCapture().jpeg(768))
                    fails = 0
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (++fails >= 5) running = false
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
