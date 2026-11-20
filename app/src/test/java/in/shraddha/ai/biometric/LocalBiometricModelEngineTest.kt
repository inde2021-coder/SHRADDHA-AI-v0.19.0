package `in`.shraddha.ai.biometric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalBiometricModelEngineTest {
    @Test
    fun miniFasNetUsesUpstreamClassOneAsRealFace() {
        assertEquals(true, LocalBiometricModelEngine.isLiveFace(floatArrayOf(0.10f, 0.82f, 0.08f)))
        assertEquals(false, LocalBiometricModelEngine.isLiveFace(floatArrayOf(0.82f, 0.10f, 0.08f)))
        assertEquals(false, LocalBiometricModelEngine.isLiveFace(floatArrayOf(0.10f, 0.20f, 0.70f)))
    }

    @Test
    fun miniFasNetPreprocessingProducesBgrNchwAndNormalizesToZeroOne() {
        val bgr = ByteArray(80 * 80 * 3)
        bgr[0] = 255.toByte()
        bgr[1] = 128.toByte()
        bgr[2] = 64
        val tensor = LocalBiometricModelEngine.preprocessMiniFasNetBgr(bgr)
        val plane = 80 * 80
        assertEquals(3 * plane, tensor.size)
        assertEquals(1f, tensor[0], 0f)
        assertEquals(128f / 255f, tensor[plane], 0.0001f)
        assertEquals(64f / 255f, tensor[2 * plane], 0.0001f)
    }

    @Test
    fun miniFasNetSoftmaxIsStableAndSumsToOne() {
        val p = LocalBiometricModelEngine.softmax(floatArrayOf(1000f, 999f, -1000f))
        assertEquals(1f, p.sum(), 0.0001f)
        assertEquals(true, p[0] > p[1] && p[1] > p[2])
    }

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
