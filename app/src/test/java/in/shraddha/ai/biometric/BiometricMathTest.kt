package `in`.shraddha.ai.biometric

import org.junit.Assert.*
import org.junit.Test

class BiometricMathTest {
    @Test fun normalizesEmbedding() {
        val result = BiometricMath.unitNormalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, result[0], 0.0001f)
        assertEquals(0.8f, result[1], 0.0001f)
    }

    @Test fun cosineSimilarityFindsSameDirection() {
        assertEquals(1f, BiometricMath.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 0.0001f)
        assertEquals(-1f, BiometricMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 0.0001f)
    }

    @Test fun thresholdMustBeExplicit() {
        assertTrue(BiometricMath.isMatch(0.8f, 0.7f))
        assertFalse(BiometricMath.isMatch(0.69f, 0.7f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNanEmbedding() {
        BiometricMath.unitNormalize(floatArrayOf(Float.NaN, 1f))
    }
    @Test fun thresholdCalibrationUsesGenuineAndImpostorSeparation() {
        val result = BiometricMath.calibrateThreshold(listOf(0.82f, 0.86f, 0.91f), listOf(0.21f, 0.31f, 0.42f))
        assertEquals(0.82f, result.minimumGenuine, 0.0001f)
        assertEquals(0.42f, result.maximumImpostor, 0.0001f)
        assertEquals(0.62f, result.threshold, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun thresholdCalibrationFailsClosedWhenDistributionsOverlap() {
        BiometricMath.calibrateThreshold(listOf(0.65f, 0.72f), listOf(0.68f, 0.40f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun thresholdCalibrationRequiresBothSampleClasses() {
        BiometricMath.calibrateThreshold(listOf(0.8f), emptyList())
    }
}