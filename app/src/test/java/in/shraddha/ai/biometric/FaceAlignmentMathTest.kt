package `in`.shraddha.ai.biometric

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceAlignmentMathTest {
    private val source = floatArrayOf(
        10f, 20f, 30f, 20f, 20f, 40f, 12f, 55f, 28f, 55f
    )

    @Test fun recoversScaleAndTranslationWithoutShear() {
        val target = FloatArray(10)
        for (i in 0 until 5) {
            target[i * 2] = 2f * source[i * 2] + 5f
            target[i * 2 + 1] = 2f * source[i * 2 + 1] - 3f
        }
        val matrix = FaceAlignmentMath.similarityTransform(source, target)
        assertEquals(2f, matrix[0], 0.0001f)
        assertEquals(0f, matrix[1], 0.0001f)
        assertEquals(5f, matrix[2], 0.0001f)
        assertEquals(0f, matrix[3], 0.0001f)
        assertEquals(2f, matrix[4], 0.0001f)
        assertEquals(-3f, matrix[5], 0.0001f)
        assertLandmarksMap(source, target, matrix)
    }

    @Test fun recoversNinetyDegreeRotationAndTranslation() {
        val target = FloatArray(10)
        for (i in 0 until 5) {
            target[i * 2] = -source[i * 2 + 1] + 100f
            target[i * 2 + 1] = source[i * 2] + 50f
        }
        val matrix = FaceAlignmentMath.similarityTransform(source, target)
        assertEquals(0f, matrix[0], 0.0001f)
        assertEquals(-1f, matrix[1], 0.0001f)
        assertEquals(100f, matrix[2], 0.0001f)
        assertEquals(1f, matrix[3], 0.0001f)
        assertEquals(0f, matrix[4], 0.0001f)
        assertEquals(50f, matrix[5], 0.0001f)
        assertLandmarksMap(source, target, matrix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDegenerateLandmarks() {
        FaceAlignmentMath.similarityTransform(FloatArray(10), source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteLandmarks() {
        val invalid = source.copyOf().apply { this[0] = Float.NaN }
        FaceAlignmentMath.similarityTransform(invalid, source)
    }

    private fun assertLandmarksMap(source: FloatArray, target: FloatArray, m: FloatArray) {
        for (i in 0 until 5) {
            val x = source[i * 2]
            val y = source[i * 2 + 1]
            val mappedX = m[0] * x + m[1] * y + m[2]
            val mappedY = m[3] * x + m[4] * y + m[5]
            assertEquals(target[i * 2], mappedX, 0.0002f)
            assertEquals(target[i * 2 + 1], mappedY, 0.0002f)
        }
    }
}
