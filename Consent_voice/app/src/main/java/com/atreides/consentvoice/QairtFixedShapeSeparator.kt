package com.atreides.consentvoice

import android.content.Context
import com.atreides.voiceconsent.FixedShape8kSeparator
import com.atreides.voiceconsent.SeparatorBackend
import com.atreides.voiceconsent.SeparatorResult
import com.atreides.voiceconsent.SeparatorStatus

/** Adapts the private QAIRT JNI bridge to the shared source-aware pipeline. */
class QairtFixedShapeSeparator(context: Context) : FixedShape8kSeparator {
    private val initializationError = runCatching { QairtSepformerModel.initialize(context.applicationContext) }
        .fold(onSuccess = { it }, onFailure = { it.message ?: "QAIRT native runtime unavailable" })

    override val status: SeparatorStatus = initializationError?.let {
        SeparatorStatus(SeparatorBackend.UNAVAILABLE, false, it)
    } ?: SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "QAIRT HTP SepFormer initialized")

    override fun separate4Seconds(pcm8k: FloatArray): SeparatorResult {
        if (!status.available) return SeparatorResult.Unavailable(status.detail)
        if (pcm8k.size != QairtSepformer.inputSamples || pcm8k.any { !it.isFinite() }) {
            return SeparatorResult.Failed("SepFormer requires finite 32,000-sample 8 kHz PCM")
        }
        return runCatching {
            val raw = QairtSepformer.separate(pcm8k)
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
