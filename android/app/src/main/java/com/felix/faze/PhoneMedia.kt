package com.felix.faze

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where this phone's microphone sound comes from. */
interface MicSource {
    /** Starts listening if not already. Throws with a plain reason when it cannot. */
    fun start()
    /** What was heard since the last call (16 kHz mono 16-bit), possibly empty. */
    fun take(): ByteArray
    fun stop()
}

/** Where this phone's camera pictures come from. */
interface CamSource {
    /** One JPEG from the front ("front") or back camera. Throws with a plain reason when it cannot. */
    fun frame(facing: String): ByteArray
    fun stop()
}

/**
 * Lets a connected computer use this phone's microphone and camera, the same way the phone uses the computer's: the computer asks for a chunk or a picture
 * as it needs one. Off unless Settings > Devices > "Let computers use my camera and microphone" is on, and Android only lets Faze use them while it is on screen.
 */
class PhoneMedia(private val enabled: () -> Boolean, private val mic: MicSource, private val cam: CamSource, private val log: (String) -> Unit = {}) {
    companion object {
        val TYPES = setOf("phone_audio_get", "phone_audio_stop", "phone_cam_get", "phone_cam_stop")
        const val RATE = 16000
    }

    fun handle(msg: JSONObject): JSONObject? {
        val t = msg.optString("t")
        if (t !in TYPES) return null
        if (!enabled()) return err("Camera and microphone sharing is off on the phone (Settings > Devices > Let computers use my camera and microphone).")
        return try {
            when (t) {
                "phone_audio_get" -> {
                    mic.start()
                    JSONObject().put("t", "phone_audio_ok").put("rate", RATE).put("data", Base64.getEncoder().encodeToString(mic.take()))
                }
                "phone_audio_stop" -> { mic.stop(); JSONObject().put("t", "ok") }
                "phone_cam_get" -> JSONObject().put("t", "phone_cam_ok").put("jpeg", Base64.getEncoder().encodeToString(cam.frame(msg.optString("facing", "front"))))
                else -> { cam.stop(); JSONObject().put("t", "ok") }
            }
        } catch (e: Exception) {
            log("phone media: ${e.message}")
            err(e.message ?: "the phone could not do that")
        }
    }

    private fun err(m: String) = JSONObject().put("t", "error").put("message", m)
}

class AndroidMic(private val context: Context) : MicSource {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    @Synchronized override fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Faze has no permission for the microphone. Allow it in the phone's app settings.")
        val min = AudioRecord.getMinBufferSize(PhoneMedia.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, PhoneMedia.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, PhoneMedia.RATE))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("The microphone is busy or not available to Faze right now (keep Faze open on screen).") }
        r.startRecording()
        rec = r; running = true
        thread = Thread {
            val chunk = ByteArray(4096)
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n); if (buffer.size() > PhoneMedia.RATE * 2 * 3) { val keep = buffer.toByteArray().takeLast(PhoneMedia.RATE * 2).toByteArray(); buffer.reset(); buffer.write(keep) } }
                else if (n < 0) break
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun take(): ByteArray = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }

    @Synchronized override fun stop() {
        running = false
        try { rec?.stop(); rec?.release() } catch (_: Exception) { }
        rec = null
        synchronized(buffer) { buffer.reset() }
    }
}

/** One still picture per request through Camera2, with the camera kept open for a few seconds so repeated requests are quick. */
class AndroidCam(private val context: Context) : CamSource {
    private val thread = HandlerThread("faze-cam").also { it.start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var openFacing = ""
    private var reader: ImageReader? = null
    private var session: CameraCaptureSession? = null
    private val closer = Runnable { stop() }

    @SuppressLint("MissingPermission")
    @Synchronized private fun open(facing: String) {
        if (device != null && openFacing == facing) return
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Faze has no permission for the camera. Allow it in the phone's app settings.")
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (facing == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } ?: throw IllegalStateException("This phone has no $facing camera.")
        val opened = CountDownLatch(1)
        var failure: String? = null
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null; opened.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); device = null; failure = "The camera is busy or not available to Faze right now (keep Faze open on screen)."; opened.countDown() }
        }, handler)
        if (!opened.await(5, TimeUnit.SECONDS) || device == null) throw IllegalStateException(failure ?: "The camera did not open.")
        val r = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        val ready = CountDownLatch(1)
        device!!.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
            override fun onConfigureFailed(s: CameraCaptureSession) { ready.countDown() }
        }, handler)
        if (!ready.await(5, TimeUnit.SECONDS) || session == null) { r.close(); stop(); throw IllegalStateException("The camera could not be set up.") }
        reader = r; openFacing = facing
    }

    override fun frame(facing: String): ByteArray {
        open(if (facing == "back") "back" else "front")
        val r = reader!!
        val got = CountDownLatch(1)
        var bytes: ByteArray? = null
        r.setOnImageAvailableListener({ rd ->
            rd.acquireLatestImage()?.use { img -> val b = img.planes[0].buffer; bytes = ByteArray(b.remaining()).also { b.get(it) } }
            got.countDown()
        }, handler)
        val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface); set(CaptureRequest.JPEG_ORIENTATION, 270) }.build()
        session!!.capture(req, null, handler)
        if (!got.await(4, TimeUnit.SECONDS) || bytes == null) throw IllegalStateException("No picture came from the camera.")
        handler.removeCallbacks(closer)
        handler.postDelayed(closer, 8000)
        return bytes!!
    }

    @Synchronized override fun stop() {
        try { session?.close(); device?.close(); reader?.close() } catch (_: Exception) { }
        session = null; device = null; reader = null; openFacing = ""
    }
}
