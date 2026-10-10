package com.atreides.voiceconsent

/** Boundary for the private, two-speaker iQOO separator. */
interface TwoSpeakerSeparator : AutoCloseable {
    val status: SeparatorStatus
    fun separate(pcm16kMono: FloatArray): SeparatorResult
    override fun close() = Unit
}

enum class SeparatorBackend { QAIRT_HTP, UNAVAILABLE }

data class SeparatorStatus(
    val backend: SeparatorBackend,
    val available: Boolean,
    val detail: String,
)

sealed interface SeparatorResult {
    data class Success(
        val sourceA16k: FloatArray,
        val sourceB16k: FloatArray,
        val backend: SeparatorBackend,
    ) : SeparatorResult

    data class Unavailable(val reason: String) : SeparatorResult
    data class Failed(val reason: String) : SeparatorResult
}

/**
 * QAIRT's native libraries and cached DLC are supplied only by the private
 * iQOO demo build. Keeping this optional makes unsupported phones fail closed.
 */
class IqooQairtTwoSpeakerSeparator : TwoSpeakerSeparator {
    private val nativeLoaded = runCatching { System.loadLibrary("consentvoice_qairt") }.isSuccess

    override val status: SeparatorStatus = if (nativeLoaded) {
        SeparatorStatus(SeparatorBackend.QAIRT_HTP, true, "QAIRT bridge loaded; HTP execution is verified per request")
    } else {
        SeparatorStatus(SeparatorBackend.UNAVAILABLE, false, "Private QAIRT HTP bridge/DLC is not installed")
    }

    override fun separate(pcm16kMono: FloatArray): SeparatorResult {
        if (!status.available) return SeparatorResult.Unavailable(status.detail)
        if (pcm16kMono.isEmpty() || pcm16kMono.any { !it.isFinite() }) return SeparatorResult.Failed("Invalid PCM input")
        // JNI implementation is injected only in the private iQOO build. Public
        // builds deliberately fail closed rather than silently using a CPU graph.
        return SeparatorResult.Unavailable("QAIRT bridge has no packaged model binding")
    }
}
