package `in`.shraddha.ai.biometric

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricModelSmokeTest {
    @Test fun packagedModelsLoadAndRunOnDeviceWithoutNetworkInference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BiometricModelInstaller.ensureInstalled(context)

        val directory = File(context.filesDir, "biometric-models")
        val faceFile = File(directory, "sface.onnx")
        val speakerFile = File(directory, "ecapa-any-length.onnx")
        assertTrue("SFace asset was not installed", faceFile.isFile && faceFile.length() > 1_000_000L)
        assertTrue("ECAPA asset was not installed", speakerFile.isFile && speakerFile.length() > 1_000_000L)

        val engine = LocalBiometricModelEngine()
        try {
            engine.loadFaceModel(faceFile.absolutePath)
            val syntheticRgb = ByteArray(112 * 112 * 3) { index ->
                ((index * 37 + index / 11) and 0xff).toByte()
            }
            val faceEmbedding = engine.faceEmbedding(syntheticRgb)
            assertEquals(128, faceEmbedding.size)
            assertUnitVector(faceEmbedding)

            engine.loadSpeakerModel(speakerFile.absolutePath)
            val syntheticSpeech = FloatArray(48_000) { index ->
                (0.12 * sin(2.0 * PI * 220.0 * index / 16_000.0)).toFloat()
            }
            val speakerEmbedding = engine.speakerEmbedding(syntheticSpeech)
            assertEquals(192, speakerEmbedding.size)
            assertUnitVector(speakerEmbedding)
            syntheticRgb.fill(0)
            syntheticSpeech.fill(0f)
        } finally {
            engine.close()
        }
    }

    private fun assertUnitVector(vector: FloatArray) {
        assertTrue("Embedding contains non-finite values", vector.all { it.isFinite() })
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        assertTrue("Embedding is not normalized: $norm", kotlin.math.abs(norm - 1.0) < 0.001)
    }
}
