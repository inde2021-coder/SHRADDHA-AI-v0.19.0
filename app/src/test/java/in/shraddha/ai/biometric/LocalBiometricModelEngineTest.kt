package `in`.shraddha.ai.biometric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalBiometricModelEngineTest {
    @Test
    fun sfacePreprocessingProducesRgbNchwWithRawPixelRange() {
        val rgb = ByteArray(112 * 112 * 3)
        rgb[0] = 1
        rgb[1] = 2
        rgb[2] = 3
        val secondPixel = 3
        rgb[secondPixel] = 255.toByte()
        rgb[secondPixel + 1] = 128.toByte()
        rgb[secondPixel + 2] = 64

        val tensor = LocalBiometricModelEngine.preprocessSFaceRgb(rgb)
        val plane = 112 * 112

        assertEquals(3 * plane, tensor.size)
        assertEquals(1f, tensor[0], 0f)
        assertEquals(255f, tensor[1], 0f)
        assertEquals(2f, tensor[plane], 0f)
        assertEquals(128f, tensor[plane + 1], 0f)
        assertEquals(3f, tensor[2 * plane], 0f)
        assertEquals(64f, tensor[2 * plane + 1], 0f)
    }

    @Test
    fun sfacePreprocessingRejectsWrongImageSize() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalBiometricModelEngine.preprocessSFaceRgb(ByteArray(10))
        }
    }
}
