package com.felix.faze

/** What each step of showing this phone's screen found. Pure data so the wording is tested without a phone. */
data class ScreenProbe(
    val root: Boolean,
    /** Size of a root screenshot in bytes and how bright it is on average (0 black .. 1 white); null when it could not be taken, with the reason. */
    val shot: Pair<Int, Double>?, val shotError: String,
    /** Bytes of live video in about two seconds and what screenrecord said. */
    val videoBytes: Int, val videoError: String,
    val fastInput: Boolean,
)

object ScreenCheck {
    /** Plain lines: what works, what does not, and what to do about it. */
    fun report(p: ScreenProbe): List<String> {
        val out = mutableListOf<String>()
        if (!p.root) {
            out += "Root is not being used. Settings > Advanced > Control method decides: Auto uses root when Faze can get it."
            out += "Without root the picture needs Allow screen capture (one tap) and the Accessibility service."
            return out
        }
        out += "Root: works."
        val s = p.shot
        out += when {
            s == null -> "Screenshot: failed. " + p.shotError.ifEmpty { "No picture came back." }
            s.second < 0.01 -> "Screenshot: came back black (${s.first / 1024} KB). A secure screen, or this phone's screenshot tool, is returning nothing. Try again with a normal app open."
            else -> "Screenshot: works (${s.first / 1024} KB)."
        }
        out += when {
            p.videoBytes > 2000 -> "Live video: works (${p.videoBytes / 1024} KB in 2 seconds)."
            else -> "Live video: nothing came out. " + p.videoError.ifEmpty { "screenrecord gave no data, so the computer shows pictures instead." }
        }
        out += if (p.fastInput) "Fast touch and keys: working." else "Fast touch and keys: not available, so each tap starts a slow command (typing from a computer will lag)."
        return out
    }
}
