package com.example.consent_cam.hardware

enum class ComputeUnit(val label: String) {
    HEXAGON_NPU("Hexagon NPU"),
    ADRENO_GPU("Adreno GPU"),
    ORYON_CPU("Oryon CPU"),
}

/** Deterministic fallback policy for neural inference on the iQOO 15 (SM8850). */
object AcceleratorPolicy {
    val neuralPreference = listOf(
        ComputeUnit.HEXAGON_NPU,
        ComputeUnit.ADRENO_GPU,
        ComputeUnit.ORYON_CPU,
    )

    fun nextAvailable(failed: Set<ComputeUnit>, gpuSupported: Boolean): ComputeUnit =
        neuralPreference.first { candidate ->
            candidate !in failed && (candidate != ComputeUnit.ADRENO_GPU || gpuSupported)
        }
}
