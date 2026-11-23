package com.felix.faze

/** What the control screen asks the PC to send: picture width, frames per second and JPEG quality. Fewer pixels means less to send and draw, so less delay. */
object RemoteQuality {
    val CHOICES = listOf("360" to "360p", "720" to "720p", "1080" to "1080p", "match" to "Match phone")

    data class Params(val width: Int, val fps: Int, val quality: Int)

    val FPS_CHOICES = listOf(15, 30, 60)

    /** fps is the frame rate the person chose (15, 30 or 60); anything else becomes 30. */
    fun params(choice: String, phoneLongEdge: Int, fps: Int = 30): Params {
        val f = if (fps in FPS_CHOICES) fps else 30
        return when (choice) {
            "360" -> Params(640, f, 55)
            "1080" -> Params(1920, f, 65)
            "match" -> Params(phoneLongEdge.coerceIn(640, 2560), f, 65)
            else -> Params(1280, f, 60)
        }
    }
}
