package com.felix.faze

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One piece on the controller: a button (or several pressed together), a stick, or a tilt switch. x and y are fractions of the screen so a layout fits any phone and either rotation. */
data class PadPart(
    val id: String, val kind: String, val label: String, val binds: List<String>,
    val x: Float, val y: Float, val size: Float, val shape: String = "pill", val right: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("label", label).put("binds", JSONArray(binds))
        .put("x", x.toDouble()).put("y", y.toDouble()).put("size", size.toDouble()).put("shape", shape).put("right", right)

    companion object {
        fun fromJson(o: JSONObject) = PadPart(
            o.getString("id"), o.getString("kind"), o.optString("label"),
            (0 until (o.optJSONArray("binds")?.length() ?: 0)).map { o.getJSONArray("binds").getString(it) },
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("size").toFloat(), o.optString("shape", "pill"), o.optBoolean("right"),
        )
    }
}

data class PadLayout(val name: String, val parts: List<PadPart>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("parts", JSONArray(parts.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = PadLayout(o.getString("name"), (0 until o.getJSONArray("parts").length()).map { PadPart.fromJson(o.getJSONArray("parts").getJSONObject(it)) })
    }
}

object PadNames {
    val ALL = listOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "BACK", "START", "GUIDE", "LS", "RS", "UP", "DOWN", "LEFT", "RIGHT")

    /** Keys of the PC's keyboard a button may press, written key:space, key:w, key:ctrl... (a plain A is the controller's A button). */
    val KEYS = (('a'..'z') + ('0'..'9')).map { it.toString() } + listOf("space", "enter", "esc", "tab", "shift", "ctrl", "alt", "super", "backspace", "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown") + (1..12).map { "f$it" }

    /** "a, ls+rs, key:space" -> [A, LS, RS, key:space]; names the controller and keyboard do not have are dropped. */
    fun parse(text: String): List<String> = text.split(',', '+', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { t ->
        val low = t.lowercase()
        when {
            low.startsWith("key:") || low.startsWith("k:") -> low.substringAfter(":").takeIf { it in KEYS }?.let { "key:$it" }
            t.uppercase() in ALL -> t.uppercase()
            else -> null
        }
    }.distinct()

    fun isKey(bind: String) = bind.startsWith("key:")
    fun label(bind: String) = bind.removePrefix("key:")
}

/** Steering from the phone's tilt: which way is "sideways" depends on how the screen is turned. */
object Tilt {
    fun steer(rotation: Int, ax: Float, ay: Float): Float {
        val raw = when (rotation) { 1 -> ay; 3 -> -ay; 2 -> ax; else -> -ax } / 5.5f
        val v = raw.coerceIn(-1f, 1f)
        return if (kotlin.math.abs(v) < 0.08f) 0f else v
    }
}

object PadPresets {
    private fun btn(id: String, label: String, binds: List<String>, x: Float, y: Float, size: Float, shape: String = "pill") = PadPart(id, "button", label, binds, x, y, size, shape)

    fun xbox() = PadLayout("Xbox pad", listOf(
        btn("lt", "LT", listOf("LT"), .08f, .10f, 48f), btn("lb", "LB", listOf("LB"), .19f, .10f, 48f),
        btn("rt", "RT", listOf("RT"), .92f, .10f, 48f), btn("rb", "RB", listOf("RB"), .81f, .10f, 48f),
        PadPart("ls", "stick", "", emptyList(), .13f, .42f, 120f),
        btn("up", "▲", listOf("UP"), .21f, .62f, 46f, "square"), btn("left", "◀", listOf("LEFT"), .14f, .76f, 46f, "square"),
        btn("right", "▶", listOf("RIGHT"), .28f, .76f, 46f, "square"), btn("down", "▼", listOf("DOWN"), .21f, .90f, 46f, "square"),
        btn("y", "Y", listOf("Y"), .88f, .38f, 52f, "round"), btn("x", "X", listOf("X"), .81f, .52f, 52f, "round"),
        btn("b", "B", listOf("B"), .95f, .52f, 52f, "round"), btn("a", "A", listOf("A"), .88f, .66f, 52f, "round"),
        PadPart("rs", "stick", "", emptyList(), .70f, .76f, 120f, right = true),
        btn("back", "Back", listOf("BACK"), .42f, .93f, 38f), btn("guide", "Guide", listOf("GUIDE"), .50f, .93f, 38f), btn("start", "Start", listOf("START"), .58f, .93f, 38f),
    ))

    fun platformer() = PadLayout("Platformer", listOf(
        btn("left", "◀", listOf("LEFT"), .10f, .78f, 70f, "square"), btn("right", "▶", listOf("RIGHT"), .26f, .78f, 70f, "square"),
        btn("up", "▲", listOf("UP"), .18f, .52f, 60f, "square"),
        btn("a", "Jump", listOf("A"), .90f, .74f, 80f, "round"), btn("x", "Run", listOf("X"), .76f, .84f, 62f, "round"), btn("b", "Act", listOf("B"), .78f, .58f, 62f, "round"),
        btn("start", "Pause", listOf("START"), .5f, .92f, 38f),
    ))

    fun shooter() = PadLayout("Shooter", listOf(
        PadPart("ls", "stick", "", emptyList(), .13f, .58f, 130f), PadPart("rs", "stick", "", emptyList(), .78f, .64f, 130f, right = true),
        btn("rt", "Fire", listOf("RT"), .92f, .22f, 62f), btn("lt", "Aim", listOf("LT"), .08f, .22f, 62f),
        btn("a", "Jump", listOf("A"), .93f, .46f, 60f, "round"), btn("flares", "Flares", listOf("LS", "RS"), .62f, .90f, 44f),
        btn("rb", "Grenade", listOf("RB"), .80f, .10f, 44f), btn("lb", "Swap", listOf("LB"), .20f, .10f, 44f),
    ))

    fun racing() = PadLayout("Racing", listOf(
        PadPart("tilt", "tilt", "Tilt steer", emptyList(), .5f, .88f, 44f),
        btn("rt", "Gas", listOf("RT"), .90f, .66f, 90f), btn("lt", "Brake", listOf("LT"), .74f, .84f, 70f),
        btn("a", "Nitro", listOf("A"), .90f, .30f, 62f, "round"), btn("x", "Horn", listOf("X"), .10f, .30f, 52f, "round"),
        btn("left", "◀", listOf("LEFT"), .08f, .78f, 70f, "square"), btn("right", "▶", listOf("RIGHT"), .22f, .78f, 70f, "square"),
    ))

    fun all() = listOf(xbox(), platformer(), shooter(), racing())
}

/** The saved layouts and which one is in use. [load] and [save] are where the text lives (the phone's settings, or nothing in tests). */
class PadLayouts(private val load: () -> String?, private val save: (String) -> Unit) {
    var layouts by mutableStateOf(PadPresets.all()); private set
    var current by mutableStateOf("Xbox pad"); private set

    init {
        try {
            val o = JSONObject(load() ?: "{}")
            val list = o.optJSONArray("layouts")
            if (list != null && list.length() > 0) layouts = (0 until list.length()).map { PadLayout.fromJson(list.getJSONObject(it)) }
            current = o.optString("current", current)
        } catch (_: Exception) { /* unreadable text: start from the presets */ }
        if (layouts.none { it.name == current }) current = layouts.first().name
    }

    val active: PadLayout get() = layouts.first { it.name == current }

    private fun persist() = save(JSONObject().put("current", current).put("layouts", JSONArray(layouts.map { it.toJson() })).toString())

    fun select(name: String) { if (layouts.any { it.name == name }) { current = name; persist() } }

    fun next() { val i = layouts.indexOfFirst { it.name == current }; select(layouts[(i + 1) % layouts.size].name) }

    fun setParts(parts: List<PadPart>, persist: Boolean = true) {
        layouts = layouts.map { if (it.name == current) it.copy(parts = parts) else it }
        if (persist) persist()
    }

    /** A copy of the layout in use under a new name, which becomes the one in use. */
    fun copy() {
        var n = 2
        while (layouts.any { it.name == "$current $n" }) n++
        val name = "$current $n"
        layouts = layouts + active.copy(name = name); current = name; persist()
    }

    fun delete() {
        if (layouts.size <= 1) return
        layouts = layouts.filter { it.name != current }; current = layouts.first().name; persist()
    }

    fun resetAll() { layouts = PadPresets.all(); current = layouts.first().name; persist() }

    companion object {
        fun memory() = PadLayouts({ null }, {})
    }
}
