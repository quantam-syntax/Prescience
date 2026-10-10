package com.atreides.consentvoice.tsvadlab

/** A 160 ms score emitted by a speaker-conditioned frame model. */
data class TargetSpeechFrame(
    val startSample: Int,
    val endSample: Int,
    val targetProbability: Float,
)

/**
 * The only model boundary in the lab. Implementations must operate completely
 * on-device and accept a locally generated enrollment embedding.
 */
interface TargetSpeechEngine : AutoCloseable {
    val enrollmentDimension: Int
    fun score(pcm16kMono: FloatArray, enrollment: FloatArray): List<TargetSpeechFrame>
}

/**
 * Converts noisy frame probabilities into stable redaction decisions. Two
 * positive frames start redaction; three negative frames release it.
 */
class TargetSpeechGate(
    private val enterThreshold: Float = 0.70f,
    private val exitThreshold: Float = 0.45f,
    private val enterFrames: Int = 2,
    private val exitFrames: Int = 3,
) {
    init {
        require(enterThreshold in 0f..1f && exitThreshold in 0f..enterThreshold)
        require(enterFrames > 0 && exitFrames > 0)
    }

    fun protectedFrames(frames: List<TargetSpeechFrame>): List<TargetSpeechFrame> {
        var active = false
        var positive = 0
        var negative = 0
        return frames.map { frame ->
            if (!active) {
                positive = if (frame.targetProbability >= enterThreshold) positive + 1 else 0
                if (positive >= enterFrames) active = true
            } else {
                negative = if (frame.targetProbability < exitThreshold) negative + 1 else 0
                if (negative >= exitFrames) {
                    active = false
                    positive = 0
                    negative = 0
                }
            }
            if (active) frame else frame.copy(targetProbability = 0f)
        }
    }

    override fun toString() = "enter=$enterThreshold/$enterFrames, exit=$exitThreshold/$exitFrames"
}
