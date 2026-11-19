package `in`.shraddha.ai.biometric

import kotlin.math.sqrt

/** Pure, deterministic helpers for local biometric verification. */
object BiometricMath {
    fun unitNormalize(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.size <= 4096)
        var sum = 0.0
        values.forEach { value ->
            require(value.isFinite()) { "Embedding contains a non-finite value" }
            sum += value.toDouble() * value.toDouble()
        }
        require(sum > 1e-12) { "Embedding has zero magnitude" }
        val norm = sqrt(sum).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        require(left.isNotEmpty() && left.size == right.size && left.size <= 4096)
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (i in left.indices) {
            val a = left[i]
            val b = right[i]
            require(a.isFinite() && b.isFinite()) { "Embedding contains a non-finite value" }
            dot += a.toDouble() * b.toDouble()
            leftNorm += a.toDouble() * a.toDouble()
            rightNorm += b.toDouble() * b.toDouble()
        }
        require(leftNorm > 1e-12 && rightNorm > 1e-12) { "Embedding has zero magnitude" }
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
    }

    /** A match is deliberately impossible without a calibrated, explicit threshold. */
    fun isMatch(similarity: Float, calibratedThreshold: Float): Boolean {
        require(calibratedThreshold in -1f..1f && similarity.isFinite())
        return similarity >= calibratedThreshold
    }
}
