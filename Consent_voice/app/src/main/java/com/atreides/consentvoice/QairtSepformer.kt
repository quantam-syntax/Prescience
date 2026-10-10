package com.atreides.consentvoice

/**
 * Native QAIRT bridge for the iQOO 15 cached SepFormer model.
 *
 * The model and QAIRT binaries are intentionally staged locally by
 * scripts/stage_qairt_sepformer.ps1 and are never committed to source control.
 */
object QairtSepformer {
    const val inputSamples = 32_000 // Four seconds at 8 kHz.
    const val sources = 2

    init { System.loadLibrary("qairt_sepformer") }

    external fun initialize(modelPath: String, nativeLibraryDir: String): String?
    external fun separate(input8k: FloatArray): FloatArray
    external fun close()
}
