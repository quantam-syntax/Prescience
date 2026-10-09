package com.example.consent_cam.recognition

import kotlin.math.abs
import kotlin.math.sqrt

object EmbeddingMath {
    fun normalize(values: FloatArray): FloatArray? {
        if (values.isEmpty() || values.any { !it.isFinite() }) return null
        var squaredNorm = 0.0
        values.forEach { value -> squaredNorm += value.toDouble() * value.toDouble() }
        if (!squaredNorm.isFinite() || squaredNorm <= 0.0) return null
        val norm = sqrt(squaredNorm).toFloat()
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    fun aggregate(samples: List<FloatArray>): FloatArray? {
        if (samples.isEmpty()) return null
        val normalized = mutableListOf<FloatArray>()
        try {
            samples.forEach { sample ->
                val value = normalize(sample) ?: return null
                normalized += value
            }
            val dimensions = normalized.first().size
            if (normalized.any { it.size != dimensions }) return null
            val average = FloatArray(dimensions)
            normalized.forEach { sample ->
                sample.indices.forEach { index -> average[index] += sample[index] / normalized.size }
            }
            return normalize(average).also { average.fill(0f) }
        } finally {
            normalized.forEach { it.fill(0f) }
        }
    }

    fun cosine(first: FloatArray, second: FloatArray): Float? {
        if (first.size != second.size || first.isEmpty()) return null
        if (first.any { !it.isFinite() } || second.any { !it.isFinite() }) return null
        var dot = 0.0
        var firstNorm = 0.0
        var secondNorm = 0.0
        first.indices.forEach { index ->
            dot += first[index].toDouble() * second[index].toDouble()
            firstNorm += first[index].toDouble() * first[index].toDouble()
            secondNorm += second[index].toDouble() * second[index].toDouble()
        }
        if (firstNorm <= 0.0 || secondNorm <= 0.0) return null
        return (dot / sqrt(firstNorm * secondNorm)).toFloat().coerceIn(-1f, 1f)
    }

    fun isUnitVector(values: FloatArray, tolerance: Float = 0.002f): Boolean {
        val normalized = normalize(values) ?: return false
        return values.indices.all { index -> abs(values[index] - normalized[index]) <= tolerance }
    }
}

