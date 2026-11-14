package com.felix.faze

/** The silhouettes of the morphing core: a port of the shape functions in core/web/core.js (a golden test checks the numbers). */
object CoreShapes {
    const val N = 120
    private const val TAU = Math.PI * 2

    private val fns: Map<String, (Double) -> Double> = mapOf(
        "faze" to { t -> 1 + 0.025 * Math.cos(3 * t + 0.6) },
        "gpt" to { t -> 0.84 + 0.16 * Math.cos(6 * t) + 0.035 * Math.cos(12 * t + 0.5) },
        "claude" to { t -> 0.7 + 0.3 * Math.pow(Math.abs(Math.cos(6 * t)), 0.55) },
        "gemini" to { t -> 0.42 + 0.58 * Math.pow(Math.abs(Math.cos(2 * t)), 2.4) },
        "qwen" to { t -> 1 / Math.pow(Math.pow(Math.abs(Math.cos(t)), 4.0) + Math.pow(Math.abs(Math.sin(t)), 4.0), 0.25) },
        "kimi" to { t -> 0.66 + 0.26 * Math.cos(t - 0.7) + 0.1 * Math.cos(2 * (t - 0.7)) + 0.03 * Math.cos(5 * t) },
        "lfm" to { t -> 0.86 + 0.075 * Math.cos(8 * t) + 0.05 * Math.cos(3 * t + 1.1) },
    )

    val CYCLE = listOf("faze", "gpt", "claude", "gemini", "qwen", "kimi", "lfm")

    /** Which form each backend wears. */
    val MODEL_FORM = mapOf(
        "auto" to "faze", "faze" to "faze", "qwen" to "qwen", "lfm" to "lfm", "gemini" to "gemini",
        "groq" to "kimi", "openrouter" to "gpt", "custom" to "gpt", "nvidia" to "gpt", "regex" to "faze", "intent" to "faze", "needle" to "faze",
    )

    private val cache = HashMap<String, FloatArray>()

    /** Radius for each of [N] angles, scaled so the largest is 1. The same array is returned for the same name. */
    fun radii(name: String): FloatArray = cache.getOrPut(name) {
        val fn = fns[name] ?: fns.getValue("faze")
        val out = FloatArray(N) { i -> fn(i.toDouble() / N * TAU).toFloat() }
        val max = out.max()
        for (i in 0 until N) out[i] = out[i] / max
        out
    }

    fun mix(a: FloatArray, b: FloatArray, t: Double, out: FloatArray) {
        for (i in 0 until N) out[i] = (a[i] + (b[i] - a[i]) * t).toFloat()
    }

    fun smooth(t: Double): Double = t * t * (3 - 2 * t)

    /** Slight overshoot, like a spring settling. */
    fun spring(t: Double): Double {
        val c = 1.35
        val u = t - 1
        return 1 + (c + 1) * u * u * u + c * u * u
    }
}

/** The morphing and breathing state of one core: a port of the step() logic of FazeCore in core.js. */
class CoreMorph(initialTime: Double = 0.0) {
    var cur: FloatArray = CoreShapes.radii("faze").copyOf()
        private set
    private var from: FloatArray = cur.copyOf()
    private var to: FloatArray = CoreShapes.radii("faze")
    var t = 1.0
        private set
    private var dur = 1.0
    var hold = 0.0
        private set
    var cycleIndex = 0
        private set
    var cycle = true
        private set
    var state = "idle"
        private set
    var level = 0.0
    var pulseV = 0.0
        private set
    var time = initialTime
        private set
    var rot = 0.0
        private set

    private fun morphTo(target: FloatArray, seconds: Double) {
        if (to === target && t >= 1) return
        from = cur.copyOf()
        to = target
        t = 0.0
        dur = seconds
    }

    /** "auto" (or null) cycles through every form; any other id morphs into that model's form and stays. */
    fun setModel(id: String?) {
        if (id == null || id == "auto") {
            if (!cycle) {
                cycle = true
                hold = 0.4
            }
            return
        }
        cycle = false
        morphTo(CoreShapes.radii(CoreShapes.MODEL_FORM[id] ?: id), 0.95)
    }

    fun setState(newState: String) {
        state = newState
    }

    fun pulse(amount: Double = 0.08) {
        pulseV = minOf(0.5, pulseV + amount)
    }

    fun step(dt: Double) {
        val fast = if (state == "thinking" || state == "tool") 2.4 else 1.0
        time += dt * fast
        rot += dt * 0.07 * fast
        if (t < 1) {
            t = minOf(1.0, t + dt / dur)
            val s = CoreShapes.spring(t)
            CoreShapes.mix(from, to, if (s > 1.08) 1.08 else s, cur)
        } else if (cycle) {
            hold -= dt * fast
            if (hold <= 0) {
                cycleIndex = (cycleIndex + 1) % CoreShapes.CYCLE.size
                hold = 1.1
                morphTo(CoreShapes.radii(CoreShapes.CYCLE[cycleIndex]), if (state == "idle") 2.8 else 1.6)
            }
        }
        pulseV *= Math.pow(0.04, dt)
    }
}
