package com.atreides.voiceconsent

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Private bridge contract: one fixed SepFormer input at 8 kHz for four seconds. */
interface FixedShape8kSeparator : AutoCloseable {
    val status: SeparatorStatus
    fun separate4Seconds(pcm8k: FloatArray): SeparatorResult
    override fun close() = Unit
}

/**
 * Converts the app's 16 kHz timeline to the fixed QAIRT graph contract. Blocks
 * overlap by two seconds; weighted overlap-add keeps the returned sources on
 * the original timeline without edge clicks.
 */
class WindowedTwoSpeakerSeparator(private val blockSeparator: FixedShape8kSeparator) : TwoSpeakerSeparator {
    override val status: SeparatorStatus get() = blockSeparator.status

    override fun separate(pcm16kMono: FloatArray): SeparatorResult {
        if (!status.available) return SeparatorResult.Unavailable(status.detail)
        if (pcm16kMono.isEmpty() || pcm16kMono.any { !it.isFinite() }) return SeparatorResult.Failed("Invalid PCM input")
        val input = resample(pcm16kMono, VOICE_SAMPLE_RATE_HZ, 8_000)
        val blockSize = 32_000
        val hop = 16_000
        val sourceA = FloatArray(input.size)
        val sourceB = FloatArray(input.size)
        val weights = FloatArray(input.size)
        var previousA: FloatArray? = null
        var offset = 0
        while (offset < input.size) {
            val block = FloatArray(blockSize)
            input.copyInto(
                destination = block,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = minOf(input.size, offset + blockSize),
            )
            val result = blockSeparator.separate4Seconds(block)
            if (result !is SeparatorResult.Success || result.sourceA16k.size != blockSize || result.sourceB16k.size != blockSize) {
                return SeparatorResult.Failed((result as? SeparatorResult.Unavailable)?.reason ?: (result as? SeparatorResult.Failed)?.reason ?: "Invalid separator output")
            }
            var a = result.sourceA16k
            var b = result.sourceB16k
            previousA?.let { previous ->
                val compare = minOf(hop, previous.size, a.size)
                if (correlation(previous, b, compare) > correlation(previous, a, compare)) {
                    val temporary = a; a = b; b = temporary
                }
            }
            val usable = minOf(blockSize, input.size - offset)
            for (index in 0 until usable) {
                val weight = 0.5f - 0.5f * cos((2.0 * PI * index) / (blockSize - 1)).toFloat()
                sourceA[offset + index] += a[index] * weight
                sourceB[offset + index] += b[index] * weight
                weights[offset + index] += weight
            }
            previousA = a.copyOfRange(blockSize - hop, blockSize)
            offset += hop
        }
        for (index in sourceA.indices) if (weights[index] > 1e-6f) {
            sourceA[index] /= weights[index]
            sourceB[index] /= weights[index]
        }
        val a16 = resample(sourceA, 8_000, VOICE_SAMPLE_RATE_HZ).copyOf(pcm16kMono.size)
        val b16 = resample(sourceB, 8_000, VOICE_SAMPLE_RATE_HZ).copyOf(pcm16kMono.size)
        return SeparatorResult.Success(a16, b16, SeparatorBackend.QAIRT_HTP)
    }

    override fun close() = blockSeparator.close()

    private fun correlation(left: FloatArray, right: FloatArray, count: Int): Float {
        var dot = 0.0; var leftNorm = 0.0; var rightNorm = 0.0
        for (index in 0 until count) { dot += left[index] * right[index]; leftNorm += left[index] * left[index]; rightNorm += right[index] * right[index] }
        return if (leftNorm == 0.0 || rightNorm == 0.0) -1f else (dot / kotlin.math.sqrt(leftNorm * rightNorm)).toFloat()
    }
}

internal fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
    if (inputRate == outputRate) return input.copyOf()
    val outputSize = ((input.size.toLong() * outputRate + inputRate / 2) / inputRate).toInt()
    if (input.isEmpty()) return FloatArray(outputSize)

    // SepFormer accepts only 8 kHz. Linear decimation folds the 4–8 kHz
    // content of phone recordings back into speech frequencies, which is heard
    // as harsh/buzzy interference. A short windowed-sinc filter removes that
    // aliasing before inference and is also a cleaner 8 -> 16 kHz reconstruction.
    val halfTaps = 24
    val cutoff = 0.45 * minOf(1.0, outputRate.toDouble() / inputRate)
    return FloatArray(outputSize) { index ->
        val position = index.toDouble() * inputRate / outputRate
        val center = position.toInt()
        var weighted = 0.0
        var weightSum = 0.0
        for (tap in (center - halfTaps)..(center + halfTaps)) {
            if (tap !in input.indices) continue
            val distance = position - tap
            val sinc = if (kotlin.math.abs(distance) < 1e-9) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * distance) / (PI * distance)
            }
            val window = 0.5 + 0.5 * cos(PI * distance / (halfTaps + 1))
            val weight = sinc * window
            weighted += input[tap] * weight
            weightSum += weight
        }
        if (kotlin.math.abs(weightSum) > 1e-9) (weighted / weightSum).toFloat() else 0f
    }
}
