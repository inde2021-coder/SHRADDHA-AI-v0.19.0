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
}