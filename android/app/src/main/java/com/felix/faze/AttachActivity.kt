package com.felix.faze

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * The overlay's + menu cannot open a picker from a floating window, so it opens this invisible screen, which opens the picker (photos, camera or files),
 * queues what was chosen as an attachment, and then brings up the chat where the message can be written and sent.
 */
class AttachActivity : ComponentActivity() {
    private var shot: File? = null
    private val core get() = (application as FazeApp).core

    private val photos = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> finishWith(uri) }
    private val files = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> finishWith(uri) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot
        finishWith(if (ok && f != null) FileProvider.getUriForFile(this, packageName + ".files", f) else null, "photo.jpg")
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture() else { core.reportStatus("Allow the camera to take a picture"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the picker is already open after a rotation or restart
        when (intent.getStringExtra(EXTRA_MODE)) {
            "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "files" -> files.launch(arrayOf("*/*"))
            "camera" -> if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePicture() else cameraPermission.launch(Manifest.permission.CAMERA)
            else -> finish()
        }
    }

    private fun takePicture() {
        val dir = File(cacheDir, "camera").also { it.mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        shot = f
        camera.launch(FileProvider.getUriForFile(this, packageName + ".files", f))
    }

    private fun finishWith(uri: android.net.Uri?, hint: String = "") {
        if (uri != null) {
            core.attach(uri, hint)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }

    companion object { const val EXTRA_MODE = "mode" }
}
