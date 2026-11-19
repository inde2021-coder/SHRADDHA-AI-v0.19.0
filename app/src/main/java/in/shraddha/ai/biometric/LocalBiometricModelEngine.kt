package `in`.shraddha.ai.biometric

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * On-device ONNX inference for two explicitly selected, user-supplied models:
 * OpenCV SFace (data -> fc1) and ECAPA-TDNN (wav/lens -> emb).
 *
 * The engine never uploads biometric samples or embeddings. Model provenance and
 * licence must be checked by the user before import.
 */
class LocalBiometricModelEngine : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private var faceSession: OrtSession? = null
    private var speakerSession: OrtSession? = null

    @Synchronized
    fun loadFaceModel(modelPath: String) {
        val candidate = environment.createSession(modelPath, OrtSession.SessionOptions())
        try {
            require(candidate.inputNames.contains("data")) { "Face model must expose input 'data'" }
            require(candidate.outputNames.contains("fc1")) { "Face model must expose output 'fc1'" }
            faceSession?.close()
            faceSession = candidate
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }

    @Synchronized
    fun loadSpeakerModel(modelPath: String) {
        val candidate = environment.createSession(modelPath, OrtSession.SessionOptions())
        try {
            require(candidate.inputNames.containsAll(setOf("wav", "lens"))) {
                "Speaker model must expose 'wav' and 'lens' inputs"
            }
            require(candidate.outputNames.contains("emb")) { "Speaker model must expose output 'emb'" }
            speakerSession?.close()
            speakerSession = candidate
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }

    /**
     * SFace input contract: aligned RGB face, 112x112, channel-first, raw 0..255.
     * The caller must perform face detection and landmark alignment before calling.
     */
    @Synchronized
    fun faceEmbedding(alignedRgb112: ByteArray): FloatArray {
        require(alignedRgb112.size == 112 * 112 * 3) { "Expected an aligned 112x112 RGB face" }
        val session = faceSession ?: error("Face model is not loaded")
        // Camera input is interleaved RGB; the SFace ONNX graph consumes NCHW.
        val plane = 112 * 112
        val values = FloatArray(alignedRgb112.size)
        for (pixel in 0 until plane) {
            values[pixel] = (alignedRgb112[pixel * 3].toInt() and 0xff).toFloat()
            values[plane + pixel] = (alignedRgb112[pixel * 3 + 1].toInt() and 0xff).toFloat()
            values[2 * plane + pixel] = (alignedRgb112[pixel * 3 + 2].toInt() and 0xff).toFloat()
        }
        val tensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 112, 112)
        )
        tensor.use {
            val result = session.run(mapOf("data" to tensor))
            result.use {
                val output = result.get("fc1").orElseThrow { IllegalStateException("Face model output fc1 missing") }
                return BiometricMath.unitNormalize(flatten(output.value))
            }
        }
    }

    /**
     * ECAPA input contract: mono 16 kHz float32 PCM in [-1,1], at least 1 second.
     * Model graph includes its 80-band feature extraction and normalisation.
     */
    @Synchronized
    fun speakerEmbedding(monoPcm16k: FloatArray): FloatArray {
        require(monoPcm16k.size in 16_000..160_000) { "Record 1–10 seconds of 16 kHz audio" }
        require(monoPcm16k.all { it.isFinite() && it in -1.05f..1.05f }) { "Audio must be normalized 16 kHz PCM" }
        val session = speakerSession ?: error("Speaker model is not loaded")
        val wav = OnnxTensor.createTensor(environment, FloatBuffer.wrap(monoPcm16k), longArrayOf(1, monoPcm16k.size.toLong()))
        val lens = OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1))
        wav.use { w ->
            lens.use { l ->
                val result = session.run(mapOf("wav" to w, "lens" to l))
                result.use {
                    val output = result.get("emb").orElseThrow { IllegalStateException("Speaker model output emb missing") }
                    return BiometricMath.unitNormalize(flatten(output.value))
                }
            }
        }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).asIterable() }.toFloatArray()
        else -> error("Unsupported ONNX output tensor type: ${value?.javaClass?.name}")
    }

    @Synchronized override fun close() {
        faceSession?.close()
        faceSession = null
        speakerSession?.close()
        speakerSession = null
    }
}
