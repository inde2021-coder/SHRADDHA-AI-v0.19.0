package `in`.shraddha.ai.biometric

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * On-device ONNX inference for the checksum-verified bundled models:
 * OpenCV SFace (data -> fc1) and ECAPA-TDNN (wav/lens -> emb).
 *
 * The engine never uploads biometric samples or embeddings. Review model licensing
 * and provenance before redistribution or deployment.
 */
class LocalBiometricModelEngine : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private var faceSession: OrtSession? = null
    private var speakerSession: OrtSession? = null
    @Volatile var faceModelLoadMs: Long = 0; private set
    @Volatile var speakerModelLoadMs: Long = 0; private set
    @Volatile var lastFaceInferenceMs: Long = 0; private set
    @Volatile var lastSpeakerInferenceMs: Long = 0; private set

    private fun createSession(modelPath: String): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        return try {
            environment.createSession(modelPath, options)
        } finally {
            options.close()
        }
    }

    @Synchronized
    fun loadFaceModel(modelPath: String) {
        val started = System.nanoTime()
        val candidate = createSession(modelPath)
        try {
            require(candidate.inputNames.contains("data")) { "Face model must expose input 'data'" }
            require(candidate.outputNames.contains("fc1")) { "Face model must expose output 'fc1'" }
            val inputShape = (candidate.inputInfo["data"]?.info as? TensorInfo)?.shape ?: error("Face model input 'data' must be a tensor")
            require(inputShape.size == 4 && (inputShape[0] == 1L || inputShape[0] == -1L) && inputShape[1] == 3L && inputShape[2] == 112L && inputShape[3] == 112L) { "Face model input shape must be [1|dynamic,3,112,112]" }
            val outputShape = (candidate.outputInfo["fc1"]?.info as? TensorInfo)?.shape ?: error("Face model output 'fc1' must be a tensor")
            require(outputShape.lastOrNull() == 128L) { "SFace model must output a 128-value embedding" }
            faceSession?.close()
            faceSession = candidate
            faceModelLoadMs = (System.nanoTime() - started) / 1_000_000L
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }

    @Synchronized
    fun loadSpeakerModel(modelPath: String) {
        val started = System.nanoTime()
        val candidate = createSession(modelPath)
        try {
            require(candidate.inputNames.containsAll(setOf("wav", "lens"))) {
                "Speaker model must expose 'wav' and 'lens' inputs"
            }
            require(candidate.outputNames.contains("emb")) { "Speaker model must expose output 'emb'" }
            val wavShape = (candidate.inputInfo["wav"]?.info as? TensorInfo)?.shape ?: error("Speaker model input 'wav' must be a tensor")
            val lensShape = (candidate.inputInfo["lens"]?.info as? TensorInfo)?.shape ?: error("Speaker model input 'lens' must be a tensor")
            val embeddingShape = (candidate.outputInfo["emb"]?.info as? TensorInfo)?.shape ?: error("Speaker model output 'emb' must be a tensor")
            require(wavShape.size == 2 && (wavShape[0] == 1L || wavShape[0] == -1L)) { "Speaker wav input must be [1|dynamic,samples]" }
            require(lensShape.size == 1 && (lensShape[0] == 1L || lensShape[0] == -1L)) { "Speaker lens input must be [1|dynamic]" }
            require(embeddingShape.lastOrNull() == 192L) { "Speaker model must output a 192-value embedding" }
            speakerSession?.close()
            speakerSession = candidate
            speakerModelLoadMs = (System.nanoTime() - started) / 1_000_000L
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
        val started = System.nanoTime()
        try {
            val tensor = OnnxTensor.createTensor(
                environment, nativeFloatBuffer(values), longArrayOf(1, 3, 112, 112)
            )
            tensor.use {
                val result = session.run(mapOf("data" to tensor))
                result.use {
                    val output = result.get("fc1").orElseThrow { IllegalStateException("Face model output fc1 missing") }
                    return BiometricMath.unitNormalize(flatten(output.value))
                }
            }
        } finally {
            lastFaceInferenceMs = (System.nanoTime() - started) / 1_000_000L
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
        val started = System.nanoTime()
        try {
            val wav = OnnxTensor.createTensor(environment, nativeFloatBuffer(monoPcm16k), longArrayOf(1, monoPcm16k.size.toLong()))
            val lens = OnnxTensor.createTensor(environment, nativeFloatBuffer(floatArrayOf(1f)), longArrayOf(1))
            wav.use { w ->
                lens.use { l ->
                    val result = session.run(mapOf("wav" to w, "lens" to l))
                    result.use {
                        val output = result.get("emb").orElseThrow { IllegalStateException("Speaker model output emb missing") }
                        return BiometricMath.unitNormalize(flatten(output.value))
                    }
                }
            }
        } finally {
            lastSpeakerInferenceMs = (System.nanoTime() - started) / 1_000_000L
        }
    }

    private fun nativeFloatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); rewind() }

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
