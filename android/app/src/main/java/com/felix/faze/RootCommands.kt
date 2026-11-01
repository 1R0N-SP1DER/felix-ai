package com.felix.faze

/**
 * The only root commands the app can ever run. They are fixed strings: nothing a model, a web page or a message says can
 * add to this list, and [isAllowed] is what the shell wrapper checks before it runs anything.
 */
object RootCommands {
    const val HIDE_MIC_DOT = "cmd device_config put privacy camera_mic_icons_enabled false"
    const val SHOW_MIC_DOT = "cmd device_config delete privacy camera_mic_icons_enabled"
    const val READ_MIC_DOT = "cmd device_config get privacy camera_mic_icons_enabled"

    private val allowed = setOf(HIDE_MIC_DOT, SHOW_MIC_DOT, READ_MIC_DOT)

    fun isAllowed(command: String): Boolean = command in allowed
}

/** What a run of the root shell is allowed to look like; the Android side turns this into a real process. */
interface RootShell {
    /** Returns the output, or null when the command was refused or root is not available. */
    fun run(command: String): String?
}

/**
 * Keeps the Android privacy dot (the green microphone dot) off while the app only waits for its wake word, and back on while
 * it really records a request. A no-op unless the person turned the option on. Failures are reported, never thrown.
 */
class MicDotGuard(private val shell: RootShell, private val enabled: () -> Boolean, private val report: (String) -> Unit = {}) {
    private var hidden = false

    /** The assistant is just waiting: hide the dot. */
    fun waiting() {
        if (!enabled() || hidden) return
        if (shell.run(RootCommands.HIDE_MIC_DOT) != null) hidden = true else report("couldn't hide the microphone dot (root not granted?)")
    }

    /** A request is being recorded: show the dot, as Android intends. */
    fun recording() {
        restore()
    }

    /** Listening stopped or the option was turned off: leave the phone as Android ships it. */
    fun restore() {
        if (!hidden) return
        if (shell.run(RootCommands.SHOW_MIC_DOT) != null) hidden = false else report("couldn't restore the microphone dot")
    }

    fun isHidden(): Boolean = hidden
}
