package com.felix.faze

/** The modifier keys of the remote's key bar: tap one to arm it, and the next key goes with it as a shortcut (Ctrl, then C = Ctrl+C). */
object RemoteKeys {
    val MODS = listOf("ctrl", "shift", "alt", "super")

    /** Arms the modifier, or takes it back if it was armed. */
    fun toggle(armed: Set<String>, mod: String): Set<String> = if (mod in armed) armed - mod else armed + mod

    /** The keys to press for [key] with whatever is armed: modifiers first, in a fixed order, then the key. */
    fun combo(armed: Set<String>, key: String): List<String> = MODS.filter { it in armed } + key

    /** Letters offered while a modifier is armed (the usual shortcuts). */
    val QUICK = listOf("c", "v", "x", "a", "z", "s", "f", "t", "w", "y", "l", "d")
}

/** How far the PC's pointer moves for a finger drag. */
enum class PointerSpeed(val label: String, val factor: Float) { SLOW("Slow", 1f), NORMAL("Normal", 1.8f), FAST("Fast", 3f) }
