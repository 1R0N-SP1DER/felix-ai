package com.felix.faze

/** One accent: the light colour and its partner, as 0xAARRGGBB. Copied from ACCENTS in core/web/app.js. */
data class Accent(val name: String, val light: Long, val partner: Long)

object Accents {
    val all = listOf(
        Accent("cyan", 0xFF5CC8FF, 0xFF3D7BFF),
        Accent("ice", 0xFFB8D8FF, 0xFF7A9BD6),
        Accent("violet", 0xFFA99BFF, 0xFF6A5CFF),
        Accent("mint", 0xFF5CE6C0, 0xFF2FA98C),
        Accent("amber", 0xFFFFC46B, 0xFFE0873A),
    )

    fun byName(name: String): Accent = all.firstOrNull { it.name == name } ?: all[0]
}
