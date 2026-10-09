package com.example.consent_cam.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class AcceleratorPolicyTest {
    @Test
    fun selectsNpuFirst() {
        assertEquals(ComputeUnit.HEXAGON_NPU, AcceleratorPolicy.nextAvailable(emptySet(), gpuSupported = true))
    }

    @Test
    fun fallsBackFromNpuToGpuThenCpu() {
        assertEquals(
            ComputeUnit.ADRENO_GPU,
            AcceleratorPolicy.nextAvailable(setOf(ComputeUnit.HEXAGON_NPU), gpuSupported = true),
        )
        assertEquals(
            ComputeUnit.ORYON_CPU,
            AcceleratorPolicy.nextAvailable(
                setOf(ComputeUnit.HEXAGON_NPU, ComputeUnit.ADRENO_GPU),
                gpuSupported = true,
            ),
        )
    }

    @Test
    fun skipsUnsupportedGpu() {
        assertEquals(
            ComputeUnit.ORYON_CPU,
            AcceleratorPolicy.nextAvailable(setOf(ComputeUnit.HEXAGON_NPU), gpuSupported = false),
        )
    }
}
