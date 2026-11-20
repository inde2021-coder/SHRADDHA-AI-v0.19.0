package `in`.shraddha.ai.biometric

import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight active-challenge state machine. This is a usability check, not certified
 * anti-spoofing or proof of identity; callers must not treat it as such.
 */
internal class BiometricLivenessGate {
    private var phase = 0
    private var stableFrames = 0

    @Synchronized fun prompt(): String = when (phase) {
        0 -> "Live check: blink both eyes, then look slightly left."
        1 -> "Now look slightly right."
        else -> "Challenge complete. Keep your face centered."
    }

    @Synchronized fun observe(faceCount: Int, leftEye: Float, rightEye: Float, yaw: Float): Boolean {
        if (faceCount != 1 || !leftEye.isFinite() || !rightEye.isFinite() || !yaw.isFinite()) {
            stableFrames = 0
            return false
        }
        when (phase) {
            0 -> {
                if (leftEye < 0.25f && rightEye < 0.25f) stableFrames++
                else if (leftEye > 0.55f && rightEye > 0.55f && stableFrames > 0) {
                    phase = 1
                    stableFrames = 0
                }
            }
            1 -> {
                if (abs(yaw) > 12f) stableFrames++
                if (stableFrames >= 2) phase = 2
            }
        }
        return phase >= 2
    }
}

/** Exact phrase comparison only; not speaker identity verification. */
internal object BiometricVoiceChallenge {
    fun matches(expected: String, heard: String): Boolean {
        fun normalize(value: String) = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .trim().replace(Regex("\\s+"), " ")
        val a = normalize(expected)
        val b = normalize(heard)
        return a.isNotBlank() && (a == b || b.contains(a))
    }
}

internal data class BiometricMatch(val name: String, val similarity: Float)

/**
 * Encrypted local consented template storage. This class deliberately does not claim
 * that a template is a real face/speaker embedding; matching is disabled until a
 * vetted model runtime and calibrated model are integrated.
 */
internal class BiometricConsentVault(private val store: EncryptedFeatureStore) {
    fun enroll(id: String, name: String, kind: String, embedding: FloatArray, consent: Boolean): Result<Unit> = runCatching {
        require(consent) { "Explicit biometric consent is required" }
        require(id.isNotBlank() && name.isNotBlank())
        require(kind == "face" || kind == "speaker")
        require(embedding.isNotEmpty() && embedding.all { it.isFinite() })
        val root = JSONObject(store.get("biometric_consent_templates_v1").getOrThrow().ifBlank { "{}" })
        root.put(id, JSONObject()
            .put("name", name.take(100))
            .put("kind", kind)
            .put("consent", true)
            .put("createdAt", System.currentTimeMillis())
            .put("template", JSONArray().also { array -> embedding.forEach { array.put(it.toDouble()) } }))
        store.put("biometric_consent_templates_v1", root.toString()).getOrThrow()
    }

    fun match(kind: String, embedding: FloatArray, threshold: Float, onlyId: String? = null): BiometricMatch? {
        // Fail closed: no identity matching until approved SFace/ECAPA inference and
        // device/dataset-specific threshold calibration are supplied and validated.
        require(kind == "face" || kind == "speaker")
        require(threshold.isFinite() && threshold in 0f..1f)
        require(embedding.isNotEmpty())
        return null
    }
}

/**
 * Build-safe model adapter. It refuses inference rather than returning fabricated
 * vectors. Replace with ONNX Runtime-backed SFace and ECAPA implementations after
 * validating the exact model files, tensor shapes, preprocessing and licences.
 */
internal class LocalBiometricModelEngine : Closeable {
    fun loadFaceModel(path: String) = requireModelUnavailable(path, "SFace")
    fun loadSpeakerModel(path: String) = requireModelUnavailable(path, "ECAPA-TDNN")

    private fun requireModelUnavailable(path: String, label: String) {
        require(File(path).isFile) { "$label model file is missing" }
        error("$label model file detected, but its inference runtime/preprocessing is not integrated. Biometric matching is disabled to prevent false identification.")
    }

    fun faceEmbedding(rgb: ByteArray): FloatArray =
        error("SFace inference is not available; no face identity result was produced.")

    fun speakerEmbedding(samples: FloatArray): FloatArray =
        error("ECAPA inference is not available; no speaker identity result was produced.")

    override fun close() = Unit
}
