package com.atreides.consentvoice

import android.content.Context
import com.atreides.voiceconsent.FixedShape16kSeparator
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SeparatorResult
import com.atreides.voiceconsent.SeparatorStatus

/** Adapts the private QAIRT JNI bridge to the shared source-aware pipeline. */
class QairtFixedShapeSeparator(context: Context) : FixedShape16kSeparator {
    private val initializationError = runCatching { QairtSepformerModel.initialize(context.applicationContext) }
        .fold(onSuccess = { it }, onFailure = { it.message ?: "QAIRT native runtime unavailable" })

    override val status: SeparatorStatus = initializationError?.let {
        SeparatorStatus(SeparatorBackend.UNAVAILABLE, false, it)
    } ?: SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "QAIRT HTP SepFormer initialized")

    override fun separate2Seconds(pcm16k: FloatArray): SeparatorResult {
        if (!status.available) return SeparatorResult.Unavailable(status.detail)
        if (pcm16k.size != QairtSepformer.inputSamples || pcm16k.any { !it.isFinite() }) {
            return SeparatorResult.Failed("SepFormer requires finite 32,000-sample 16 kHz PCM")
        }
        return runCatching {
            val raw = QairtSepformer.separate(pcm16k)
            require(raw.size == QairtSepformer.inputSamples * QairtSepformer.sources) { "Unexpected SepFormer output size" }
            SeparatorResult.Success(
                sourceA16k = FloatArray(QairtSepformer.inputSamples) { raw[it * QairtSepformer.sources] },
                sourceB16k = FloatArray(QairtSepformer.inputSamples) { raw[it * QairtSepformer.sources + 1] },
                backend = SeparatorBackend.QAIRT_HTP,
            )
        }.getOrElse { SeparatorResult.Failed(it.message ?: "QAIRT HTP inference failed") }
    }

    override fun close() = runCatching { QairtSepformer.close() }.getOrNull().let { Unit }
}
