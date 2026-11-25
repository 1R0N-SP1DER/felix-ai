package com.felix.faze

/** The one line Settings shows about the GPU, from what the last offline model start reported. */
object GpuStatus {
    fun describe(last: String, layers: Int): String = when {
        layers <= 0 -> "no, GPU share is CPU only"
        last.startsWith("GPU:") -> "yes. " + last.removePrefix("GPU:").trim()
        last.contains("CPU") -> "no. This llama.cpp could not use the GPU, so the CPU runs the model"
        else -> "unknown yet. Load an offline model once and this updates"
    }
}
