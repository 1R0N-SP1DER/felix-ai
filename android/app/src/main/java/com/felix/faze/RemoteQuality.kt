package com.felix.faze

/** What the control screen asks the PC to send: picture width, frames per second and JPEG quality. Fewer pixels means less to send and draw, so less delay. */
object RemoteQuality {
    val CHOICES = listOf("360" to "360p", "720" to "720p", "1080" to "1080p", "match" to "Match phone")

    data class Params(val width: Int, val fps: Int, val quality: Int)

    fun params(choice: String, phoneLongEdge: Int): Params = when (choice) {
        "360" -> Params(640, 30, 55)
        "1080" -> Params(1920, 30, 65)
        "match" -> Params(phoneLongEdge.coerceIn(640, 2560), 30, 65)
        else -> Params(1280, 30, 60)
    }
}
