package com.atreides.voiceconsent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Experimental, gentle cross-stream cleanup. It is not a privacy guarantee. */
object CoherentLeakageSuppressor {
    private const val size = 512
    private const val hop = 128
    private const val bins = size / 2 + 1
    private val window = DoubleArray(size) { index -> 0.5 - 0.5 * cos(2.0 * PI * index / size) }

    fun suppress(retained: FloatArray, other: FloatArray, strength: Double = 0.45): FloatArray {
        require(retained.size == other.size && retained.isNotEmpty()) { "Separated streams must align" }
        require(retained.all { it.isFinite() } && other.all { it.isFinite() }) { "Invalid separated audio" }
        require(strength in 0.0..1.0)
        val retainedRms = sqrt(retained.sumOf { it.toDouble() * it } / retained.size)
        val otherRms = sqrt(other.sumOf { it.toDouble() * it } / other.size)
        if (retainedRms < 1e-8 || otherRms < 1e-8 || strength == 0.0) return retained.copyOf()
        val ratio = retainedRms / otherRms
        val frames = (retained.size + hop - 1) / hop + 1
        val count = frames * bins
        val targetRe = DoubleArray(count); val targetIm = DoubleArray(count)
        val otherRe = DoubleArray(count); val otherIm = DoubleArray(count)
        val real = DoubleArray(size); val imaginary = DoubleArray(size)
        for (frame in 0 until frames) {
            val center = frame * hop
            for (index in 0 until size) {
                val position = center + index - size / 2
                real[index] = if (position in retained.indices) retained[position] * window[index] else 0.0
                imaginary[index] = 0.0
            }
            fft(real, imaginary, false)
            for (bin in 0 until bins) {
                val at = frame * bins + bin
                targetRe[at] = real[bin]; targetIm[at] = imaginary[bin]
            }
            for (index in 0 until size) {
                val position = center + index - size / 2
                real[index] = if (position in other.indices) other[position] * ratio * window[index] else 0.0
                imaginary[index] = 0.0
            }
            fft(real, imaginary, false)
            for (bin in 0 until bins) {
                val at = frame * bins + bin
                otherRe[at] = real[bin]; otherIm[at] = imaginary[bin]
            }
        }
        val targetPower = DoubleArray(count) { targetRe[it] * targetRe[it] + targetIm[it] * targetIm[it] }
        val otherPower = DoubleArray(count) { otherRe[it] * otherRe[it] + otherIm[it] * otherIm[it] }
        val crossRe = DoubleArray(count) { targetRe[it] * otherRe[it] + targetIm[it] * otherIm[it] }
        val crossIm = DoubleArray(count) { targetIm[it] * otherRe[it] - targetRe[it] * otherIm[it] }
        val gain = DoubleArray(count)
        for (frame in 0 until frames) for (bin in 0 until bins) {
            var tp = 0.0; var op = 0.0; var cr = 0.0; var ci = 0.0
            for (shift in -4..4) {
                val at = reflect(frame + shift, frames) * bins + bin
                tp += targetPower[at]; op += otherPower[at]
                cr += crossRe[at]; ci += crossIm[at]
            }
            tp /= 9.0; op /= 9.0; cr /= 9.0; ci /= 9.0
            val coherence = min(1.0, (cr * cr + ci * ci) / (tp * op + 1e-12))
            val otherFraction = op / (tp + op + 1e-12)
            gain[frame * bins + bin] = max(0.2, 1.0 - strength * coherence * otherFraction)
        }
        val output = DoubleArray(retained.size)
        val normalization = DoubleArray(retained.size)
        for (frame in 0 until frames) {
            for (bin in 0 until bins) {
                var smoothed = 0.0
                for (shift in -1..1) smoothed += gain[frame * bins + reflect(bin + shift, bins)]
                val at = frame * bins + bin
                real[bin] = targetRe[at] * smoothed / 3.0
                imaginary[bin] = targetIm[at] * smoothed / 3.0
            }
            for (bin in bins until size) {
                real[bin] = real[size - bin]
                imaginary[bin] = -imaginary[size - bin]
            }
            fft(real, imaginary, true)
            val center = frame * hop
            for (index in 0 until size) {
                val position = center + index - size / 2
                if (position in output.indices) {
                    output[position] += real[index] * window[index]
                    normalization[position] += window[index] * window[index]
                }
            }
        }
        return FloatArray(output.size) { index ->
            if (normalization[index] > 1e-10) (output[index] / normalization[index]).toFloat() else 0f
        }
    }

    private fun reflect(index: Int, length: Int): Int {
        if (length == 1) return 0
        var value = index
        while (value < 0 || value >= length) value = if (value < 0) -value - 1 else 2 * length - value - 1
        return value
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
        var reverse = 0
        for (index in 1 until size) {
            var bit = size ushr 1
            while (reverse and bit != 0) { reverse = reverse xor bit; bit = bit ushr 1 }
            reverse = reverse xor bit
            if (index < reverse) {
                val re = real[index]; real[index] = real[reverse]; real[reverse] = re
                val im = imaginary[index]; imaginary[index] = imaginary[reverse]; imaginary[reverse] = im
            }
        }
        var span = 2
        while (span <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / span
            val stepRe = cos(angle); val stepIm = sin(angle)
            for (start in 0 until size step span) {
                var twiddleRe = 1.0; var twiddleIm = 0.0
                for (index in 0 until span / 2) {
                    val left = start + index; val right = left + span / 2
                    val productRe = twiddleRe * real[right] - twiddleIm * imaginary[right]
                    val productIm = twiddleRe * imaginary[right] + twiddleIm * real[right]
                    real[right] = real[left] - productRe; imaginary[right] = imaginary[left] - productIm
                    real[left] += productRe; imaginary[left] += productIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
            }
            span *= 2
        }
        if (inverse) for (index in 0 until size) { real[index] /= size; imaginary[index] /= size }
    }
}
