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
 * On-device ONNX inference for the checksum-verified downloaded models:
 * OpenCV SFace (data -> fc1) and ECAPA-TDNN (waveform -> embedding).
 *
 * The engine never uploads biometric samples or embeddings. Review model licensing
 * and provenance before redistribution or deployment.
 */
class LocalBiometricModelEngine : Closeable {
    companion object {
        /** MiniFASNet V2: BGR NCHW float32 normalized to [0,1]. */
        internal fun preprocessMiniFasNetBgr(bgr80: ByteArray): FloatArray {
            require(bgr80.size == 80 * 80 * 3) { "Expected 80x80 BGR image." }
            val plane = 80 * 80
            val data = FloatArray(3 * plane)
            for (channel in 0..2) for (y in 0 until 80) for (x in 0 until 80) {
                val pixel = (y * 80 + x) * 3 + channel
                data[channel * plane + y * 80 + x] = (bgr80[pixel].toInt() and 0xff) / 255f
            }
            return data
        }

        internal fun isLiveFace(scores: FloatArray, minLiveScore: Float = 0.70f): Boolean {
            require(scores.size == 3 && scores.all { it.isFinite() } && minLiveScore in 0f..1f)
            return scores[1] >= minLiveScore && scores[1] > scores[0] && scores[1] > scores[2]
        }

        internal fun softmax(scores: FloatArray): FloatArray {
            require(scores.isNotEmpty() && scores.all { it.isFinite() })
            val max = scores.maxOrNull() ?: return FloatArray(0)
            val exp = scores.map { kotlin.math.exp((it - max).toDouble()) }
            val sum = exp.sum()
            require(sum.isFinite() && sum > 0.0)
            return exp.map { (it / sum).toFloat() }.toFloatArray()
        }

        /** OpenCV FaceRecognizerSF feature() preprocessing for an already aligned RGB crop. */
        internal fun preprocessSFaceRgb(rgb112: ByteArray): FloatArray {
            require(rgb112.size == 112 * 112 * 3) { "Expected aligned 112x112 RGB image." }
            val data = FloatArray(3 * 112 * 112)
            var p = 0
            for (channel in 0..2) for (y in 0 until 112) for (x in 0 until 112) {
                val index = (y * 112 + x) * 3 + channel
                data[p++] = (rgb112[index].toInt() and 0xff).toFloat()
            }
            return data
        }
    }
    private val environment = OrtEnvironment.getEnvironment()
    private var faceSession: OrtSession? = null
    private var speakerSession: OrtSession? = null
    @Volatile var faceModelLoadMs: Long = 0
        private set
    @Volatile var speakerModelLoadMs: Long = 0
        private set
    @Volatile var lastFaceInferenceMs: Long = 0
        private set
    @Volatile var lastSpeakerInferenceMs: Long = 0
        private set

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
            require(candidate.inputNames.contains("waveform")) {
                "Speaker model must expose input 'waveform'"
            }
            require(candidate.outputNames.contains("embedding")) { "Speaker model must expose output 'embedding'" }
            val waveformShape = (candidate.inputInfo["waveform"]?.info as? TensorInfo)?.shape
                ?: error("Speaker model input 'waveform' must be a tensor")
            val embeddingShape = (candidate.outputInfo["embedding"]?.info as? TensorInfo)?.shape
                ?: error("Speaker model output 'embedding' must be a tensor")
            require(waveformShape.size == 2 && (waveformShape[0] == 1L || waveformShape[0] == -1L)) {
                "Speaker waveform input must be [1|dynamic,samples]"
            }
            // Exporters may encode the same vector as [192], [1,192], or another rank.
            // Validate the actual runtime tensor rather than assuming metadata rank.
            require(embeddingShape.isNotEmpty()) { "Speaker model output embedding has an empty shape" }
            val probeBuffer = nativeFloatBuffer(FloatArray(16_000))
            try {
                val probe = OnnxTensor.createTensor(environment, probeBuffer, longArrayOf(1, 16_000))
                probe.use { audio ->
                    val result = candidate.run(mapOf("waveform" to audio))
                    result.use {
                        val output = result.get("embedding").orElseThrow {
                            IllegalArgumentException("Speaker model output embedding is missing")
                        }
                        val values = flatten(output.value)
                        try {
                            require(values.size == 192) {
                                "Speaker model must produce 192 embedding values; " +
                                    "metadata shape=${embeddingShape.contentToString()}, " +
                                    "runtime shape=${(output.info as? TensorInfo)?.shape?.contentToString()}, " +
                                    "runtime values=${values.size}"
                            }
                        } finally { values.fill(0f) }
                    }
                }
            } finally { zero(probeBuffer) }
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
        val inputBuffer = nativeFloatBuffer(values)
        try {
            val tensor = OnnxTensor.createTensor(
                environment, inputBuffer, longArrayOf(1, 3, 112, 112)
            )
            tensor.use {
                val result = session.run(mapOf("data" to tensor))
                result.use {
                    val output = result.get("fc1").orElseThrow { IllegalStateException("Face model output fc1 missing") }
                    val rawEmbedding = flatten(output.value)
                    try {
                        return BiometricMath.unitNormalize(rawEmbedding)
                    } finally {
                        rawEmbedding.fill(0f)
                    }
                }
            }
        } finally {
            zero(inputBuffer)
            values.fill(0f)
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
        val waveformBuffer = nativeFloatBuffer(monoPcm16k)
        try {
            val waveform = OnnxTensor.createTensor(
                environment, waveformBuffer, longArrayOf(1, monoPcm16k.size.toLong())
            )
            waveform.use { audio ->
                val result = session.run(mapOf("waveform" to audio))
                result.use {
                    val output = result.get("embedding").orElseThrow {
                        IllegalStateException("Speaker model output embedding missing")
                    }
                    val rawEmbedding = flatten(output.value)
                    try {
                        return BiometricMath.unitNormalize(rawEmbedding)
                    } finally {
                        rawEmbedding.fill(0f)
                    }
                }
            }
        } finally {
            zero(waveformBuffer)
            lastSpeakerInferenceMs = (System.nanoTime() - started) / 1_000_000L
        }
    }

    private fun nativeFloatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); rewind() }

    private fun zero(buffer: FloatBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0f)
        buffer.clear()
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
