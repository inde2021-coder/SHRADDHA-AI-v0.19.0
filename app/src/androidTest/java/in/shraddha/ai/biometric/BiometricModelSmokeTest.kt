package `in`.shraddha.ai.biometric

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricModelSmokeTest {
    @Test fun selectedModelsDownloadVerifyAndRunOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelSpecs = BiometricModelInstaller.supportedModels().associateBy { it.kind }
        assertEquals(setOf("face", "speaker", "anti_spoof"), modelSpecs.keys)
        assertEquals(
            "c59760b0a30dd905e3757cbb9ddf5dde22f9a099dc3e74de77c263cbb202e67c",
            modelSpecs.getValue("speaker").sha256
        )
        assertTrue(modelSpecs.getValue("speaker").url.contains("/dd6e443/"))
        assertEquals(38_696_353L, modelSpecs.getValue("face").expectedBytes)
        assertEquals(
            "d7b3cd9ba8a7ceb13baa8c4720902e27ca3112eff52f926c08804af6b6eecc7b",
            modelSpecs.getValue("anti_spoof").sha256
        )
        val bundled = context.assets.list("biometric-models").orEmpty()
        assertFalse("Large ONNX models must not be bundled in the APK", bundled.any { it.endsWith(".onnx") })

        val directory = File(context.filesDir, "biometric-models")
        directory.deleteRecursively() // Keep the on-demand assertion deterministic across repeated test runs.
        val faceFile = File(directory, "sface.onnx")
        val speakerFile = File(directory, "ecapa-any-length.onnx")
        BiometricModelInstaller.ensureInstalled(context, "face")
        assertTrue("SFace model was not downloaded and verified", faceFile.isFile && faceFile.length() > 1_000_000L)
        assertFalse("Speaker model should remain on-demand until selected", speakerFile.exists())
        BiometricModelInstaller.ensureInstalled(context, "speaker")
        assertTrue("ECAPA model was not downloaded and verified", speakerFile.isFile && speakerFile.length() > 1_000_000L)

        val engine = LocalBiometricModelEngine()
        val syntheticRgb = ByteArray(112 * 112 * 3) { index ->
            ((index * 37 + index / 11) and 0xff).toByte()
        }
        val syntheticSpeech = FloatArray(48_000) { index ->
            (0.12 * sin(2.0 * PI * 220.0 * index / 16_000.0)).toFloat()
        }
        try {
            engine.loadFaceModel(faceFile.absolutePath)
            val faceEmbedding = engine.faceEmbedding(syntheticRgb)
            assertEquals(128, faceEmbedding.size)
            assertUnitVector(faceEmbedding)

            engine.loadSpeakerModel(speakerFile.absolutePath)
            val speakerEmbedding = engine.speakerEmbedding(syntheticSpeech)
            assertEquals(192, speakerEmbedding.size)
            assertUnitVector(speakerEmbedding)
        } finally {
            syntheticRgb.fill(0)
            syntheticSpeech.fill(0f)
            engine.close()
        }
    }

    private fun assertUnitVector(vector: FloatArray) {
        assertTrue("Embedding contains non-finite values", vector.all { it.isFinite() })
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        assertTrue("Embedding is not normalized: $norm", kotlin.math.abs(norm - 1.0) < 0.001)
    }
}
