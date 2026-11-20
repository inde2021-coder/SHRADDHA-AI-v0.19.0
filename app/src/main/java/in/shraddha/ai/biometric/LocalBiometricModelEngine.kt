package `in`.shraddha.ai.biometric

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Strict ONNX adapter. It refuses unknown tensor contracts instead of returning fake embeddings.
 * SFace expects NCHW RGB 112x112 input. ECAPA variants differ; only a rank-2 waveform input is
 * currently accepted. Feature/mel-input ECAPA exports are rejected until their exact preprocessing
 * contract is documented and tested against the supplied licensed model.
 */
class LocalBiometricModelEngine : AutoCloseable {
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
                // OpenCV blobFromImage(scale=1, mean=0, swapRB=true). The caller
                // already supplies RGB, so preserve 0..255 values without normalization.
                data[p++] = (rgb112[index].toInt() and 0xff).toFloat()
            }
            return data
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var faceSession: OrtSession? = null
    private var speakerSession: OrtSession? = null
    private var antiSpoofSession: OrtSession? = null

    @Synchronized fun loadFaceModel(path: String) {
        faceSession?.close()
        val session = env.createSession(path, OrtSession.SessionOptions())
        val shape = session.inputInfo.values.firstOrNull()?.info?.let { (it as? ai.onnxruntime.TensorInfo)?.shape }
        require(shape != null && shape.size == 4 && shape[1] == 3L && shape[2] == 112L && shape[3] == 112L) {
            session.close(); "Unsupported SFace input tensor. Expected [1,3,112,112]; model contract was not verified."
        }
        faceSession = session
    }

    @Synchronized fun loadSpeakerModel(path: String) {
        speakerSession?.close()
        val session = env.createSession(path, OrtSession.SessionOptions())
        val shape = session.inputInfo.values.firstOrNull()?.info?.let { (it as? ai.onnxruntime.TensorInfo)?.shape }
        require(shape != null && shape.size == 2 && (shape[0] == 1L || shape[0] == -1L) && (shape[1] == -1L || shape[1] >= 80000L)) {
            session.close(); "Unsupported ECAPA input tensor. This export needs a verified waveform/preprocessing contract."
        }
        speakerSession = session
    }

    @Synchronized fun loadAntiSpoofModel(path: String) {
        antiSpoofSession?.close()
        val session = env.createSession(path, OrtSession.SessionOptions())
        val shape = session.inputInfo.values.firstOrNull()?.info?.let { (it as? ai.onnxruntime.TensorInfo)?.shape }
        require(shape != null && shape.size == 4 && shape[1] == 3L && shape[2] == 80L && shape[3] == 80L) {
            session.close(); "Unsupported MiniFASNet input tensor. Expected [1,3,80,80]."
        }
        antiSpoofSession = session
    }

    /** Returns [live, print, replay] probabilities; supplementary PAD signal only. */
    @Synchronized fun antiSpoofScores(bgr80: ByteArray): FloatArray {
        require(bgr80.size == 80 * 80 * 3) { "Expected 80x80 BGR crop." }
        val session = antiSpoofSession ?: error("MiniFASNet anti-spoof model is not loaded.")
        val data = preprocessMiniFasNetBgr(bgr80)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 80, 80))
        tensor.use {
            val results = session.run(mapOf(session.inputNames.first() to tensor))
            results.use {
                val value = results.firstOrNull()?.value ?: error("MiniFASNet returned no output.")
                val raw = flatten(value)
                require(raw.size == 3 && raw.all { it.isFinite() }) { "MiniFASNet output contract must be three finite scores." }
                val sum = raw.sum()
                return if (raw.all { it in 0f..1f } && kotlin.math.abs(sum - 1f) < 0.02f) raw else softmax(raw)
            }
        }
    }

    @Synchronized fun faceEmbedding(rgb112: ByteArray): FloatArray {
        require(rgb112.size == 112 * 112 * 3) { "Expected aligned 112x112 RGB image." }
        val session = faceSession ?: error("SFace model is not loaded.")
        val inputName = session.inputNames.first()
        val data = preprocessSFaceRgb(rgb112)
        return runModel(session, inputName, data, longArrayOf(1, 3, 112, 112))
    }

    @Synchronized fun speakerEmbedding(samples: FloatArray): FloatArray {
        require(samples.size in 80000..160000 && samples.all { it.isFinite() }) { "Expected 5–10 seconds of finite 16 kHz mono audio." }
        val session = speakerSession ?: error("ECAPA model is not loaded.")
        val shape = (session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
        require(shape.size == 2 && (shape[1] == -1L || shape[1] >= samples.size.toLong())) {
            "ECAPA waveform input shape does not match captured audio."
        }
        return runModel(session, session.inputNames.first(), samples, longArrayOf(1, samples.size.toLong()))
    }

    private fun runModel(session: OrtSession, inputName: String, values: FloatArray, shape: LongArray): FloatArray {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
        tensor.use {
            val results = session.run(mapOf(inputName to tensor))
            results.use {
                val output = results.firstOrNull()?.value ?: error("ONNX model returned no output.")
                val vector = when (output) {
                    is FloatArray -> output
                    is Array<*> -> flatten(output)
                    else -> error("Unsupported ONNX output type: ${output.javaClass.name}")
                }
                require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Model returned invalid embedding." }
                var norm = 0.0
                vector.forEach { norm += it * it }
                require(norm > 1e-12) { "Model returned a zero embedding." }
                val scale = kotlin.math.sqrt(norm).toFloat()
                return vector.map { it / scale }.toFloatArray()
            }
        }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).asIterable() }.toFloatArray()
        else -> error("Unsupported nested ONNX output.")
    }

    @Synchronized override fun close() {
        faceSession?.close(); faceSession = null
        speakerSession?.close(); speakerSession = null
        antiSpoofSession?.close(); antiSpoofSession = null
    }
}
