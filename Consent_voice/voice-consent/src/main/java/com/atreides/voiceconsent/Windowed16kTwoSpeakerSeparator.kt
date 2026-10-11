package com.atreides.voiceconsent

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/** One 2-second, 16 kHz QAIRT inference. This is distinct from the legacy 8 kHz graph. */
interface FixedShape16kSeparator : AutoCloseable {
    val status: SeparatorStatus
    fun separate2Seconds(pcm16k: FloatArray): SeparatorResult
    override fun close() = Unit
}

/** End-aligned, half-overlapping blocks preserve the original 16 kHz timeline. */
class Windowed16kTwoSpeakerSeparator(private val blockSeparator: FixedShape16kSeparator) : TwoSpeakerSeparator {
    override val status: SeparatorStatus get() = blockSeparator.status

    override fun separate(pcm16kMono: FloatArray): SeparatorResult {
        if (!status.available) return SeparatorResult.Unavailable(status.detail)
        if (pcm16kMono.isEmpty() || pcm16kMono.any { !it.isFinite() }) return SeparatorResult.Failed("Invalid PCM input")
        val blockSize = 32_000
        val hop = 16_000
        val sourceA = FloatArray(pcm16kMono.size)
        val sourceB = FloatArray(pcm16kMono.size)
        val weights = FloatArray(pcm16kMono.size)
        val finalStart = (pcm16kMono.size - blockSize).coerceAtLeast(0)
        val hops = maxOf(1, ceil(finalStart.toDouble() / hop).toInt())
        val starts = (0..hops).map { (it.toLong() * finalStart / hops).toInt() }.distinct()
        val window = FloatArray(blockSize) { index ->
            // Match the local 16 kHz NPU/float comparison's Hann overlap-add.
            0.02f + 0.98f * (0.5f - 0.5f * cos(2.0 * PI * index / (blockSize - 1)).toFloat())
        }
        var previousA: FloatArray? = null
        var previousB: FloatArray? = null
        var previousOffset = 0
        for (offset in starts) {
            val block = FloatArray(blockSize)
            pcm16kMono.copyInto(block, endIndex = minOf(pcm16kMono.size, offset + blockSize), startIndex = offset)
            val result = blockSeparator.separate2Seconds(block)
            if (result !is SeparatorResult.Success || result.sourceA16k.size != blockSize || result.sourceB16k.size != blockSize) {
                return SeparatorResult.Failed((result as? SeparatorResult.Unavailable)?.reason ?: (result as? SeparatorResult.Failed)?.reason ?: "Invalid separator output")
            }
            if (result.sourceA16k.any { !it.isFinite() } || result.sourceB16k.any { !it.isFinite() }) {
                return SeparatorResult.Failed("Separator returned invalid audio")
            }
            var a = result.sourceA16k
            var b = result.sourceB16k
            previousA?.let { previous ->
                val overlap = (previousOffset + blockSize - offset).coerceIn(0, blockSize)
                val previousStart = offset - previousOffset
                val other = requireNotNull(previousB)
                val same = correlation(previous, previousStart, a, overlap) + correlation(other, previousStart, b, overlap)
                val swapped = correlation(previous, previousStart, b, overlap) + correlation(other, previousStart, a, overlap)
                if (swapped > same + 0.05f) { val temp = a; a = b; b = temp }
            }
            val usable = minOf(blockSize, pcm16kMono.size - offset)
            for (index in 0 until usable) {
                val weight = window[index]
                sourceA[offset + index] += a[index] * weight
                sourceB[offset + index] += b[index] * weight
                weights[offset + index] += weight
            }
            previousA = a
            previousB = b
            previousOffset = offset
        }
        for (index in weights.indices) {
            if (weights[index] <= 1e-6f) return SeparatorResult.Failed("Incomplete source reconstruction")
            sourceA[index] /= weights[index]
            sourceB[index] /= weights[index]
        }
        return SeparatorResult.Success(sourceA, sourceB, SeparatorBackend.QAIRT_HTP)
    }

    override fun close() = blockSeparator.close()

    private fun correlation(left: FloatArray, leftStart: Int, right: FloatArray, count: Int): Float {
        var dot = 0.0; var leftNorm = 0.0; var rightNorm = 0.0
        for (index in 0 until count) {
            val a = left[leftStart + index]
            val b = right[index]
            dot += a * b; leftNorm += a * a; rightNorm += b * b
        }
        return if (leftNorm == 0.0 || rightNorm == 0.0) 0f else (dot / sqrt(leftNorm * rightNorm)).toFloat()
    }
}
