package com.felix.faze

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws screens on the JVM and saves them as pictures in build/screenshots (the CI log prints them), so a layout can be looked at without a phone.
 * They are drawn with made-up data; nothing here talks to a computer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        val dir = File("build/screenshots")
        dir.mkdirs()
        System.setProperty("roborazzi.test.record", "true") // no Gradle plugin here: ask the library to just write the picture
        rule.setContent(content)
        try {
            rule.onRoot().captureRoboImage(File(dir, "$name.png").path)
        } catch (e: Throwable) {
            File(dir, "$name.txt").writeText(e.toString() + "\n" + e.stackTrace.take(25).joinToString("\n"))
            throw e
        }
        assertTrue(File(dir, "$name.png").length() > 1000)
    }

    /** A stand-in for the PC's desktop: a blue gradient with a few windows. */
    private fun desktop(): androidx.compose.ui.graphics.ImageBitmap {
        val b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 1280f, 720f, 0xFF1D3B6F.toInt(), 0xFF0B1424.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1280f, 720f, p)
        val w = Paint()
        w.color = 0xFF222B3D.toInt()
        c.drawRoundRect(120f, 110f, 720f, 520f, 18f, 18f, w)
        w.color = 0xFF2E3B55.toInt()
        c.drawRoundRect(520f, 250f, 1130f, 640f, 18f, 18f, w)
        w.color = 0xFF111827.toInt()
        c.drawRect(0f, 672f, 1280f, 720f, w)
        return b.asImageBitmap()
    }

    private fun remote() = PcRemote({ _, _ -> JSONObject() }, Executor { it.run() }, {})

    @Test fun remoteOverEmptyScreen() = shot("remote_waiting") {
        RemoteView("Felix-PC", null, "", "", remote(), {}, {})
    }

    @Test fun remoteOverTheLivePicture() = shot("remote_live") {
        RemoteView("Felix-PC", desktop(), "", "x264", remote(), {}, {})
    }

    @Test fun remoteWithTheKeyBarAndCtrlArmed() = shot("remote_keys") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, startKeysOpen = true, startArmed = setOf("ctrl"))
    }

    @Test fun remoteWithTheWebcamSquare() = shot("remote_cam") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, camFrame = desktop(), cam = true, mic = true)
    }

    @Test fun remoteWithTheGamepad() = shot("remote_pad") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, startPad = true)
    }

    @Test fun remoteEditingTheGamepad() = shot("remote_pad_edit") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, startEdit = true)
    }

    @Test fun remoteWithTheRacingLayout() = shot("remote_pad_racing") {
        val l = remember { PadLayouts.memory().also { it.select("Racing") } }
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, startPad = true, layouts = l)
    }

    @Test fun remoteTaskManager() = shot("remote_tasks") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, panel = "tasks", procs = listOf(ProcRow(2210, "firefox", 18.4, 1240.0), ProcRow(1432, "plasmashell", 3.1, 410.0), ProcRow(8812, "code", 1.2, 880.0)))
    }

    @Test fun remoteDeviceManager() = shot("remote_devices") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, panel = "devices", devices = listOf(PcDeviceRow("usb", "Logitech Wireless Receiver"), PcDeviceRow("input", "AT Translated Set 2 keyboard"), PcDeviceRow("battery", "BAT0 82%")))
    }

    @Test fun remoteDrawing() = shot("remote_draw") {
        RemoteView("Felix-PC", desktop(), "", "", remote(), {}, {}, startDrawing = true)
    }

    @Test fun remoteControllerOnly() = shot("remote_overlay_only") {
        RemoteView("Felix-PC", null, "", "", remote(), {}, {}, overlayOnly = true)
    }

    @Test fun deviceTiles() = shot("device_tiles") {
        Column(Modifier.background(Palette.Bg).verticalScroll(rememberScrollState()).padding(16.dp)) { PageHeader("Felix-PC", {}); TileGrid {} }
    }

    @Test fun remoteWithNoComputer() = shot("remote_no_pc") {
        RemoteView(null, null, "", "", remote(), {}, {})
    }
}
