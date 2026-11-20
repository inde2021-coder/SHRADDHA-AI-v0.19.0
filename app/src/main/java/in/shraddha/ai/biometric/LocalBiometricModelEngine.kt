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
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var faceSession: OrtSession? = null
    private var speakerSession: OrtSession? = null

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

    @Synchronized fun faceEmbedding(rgb112: ByteArray): FloatArray {
        require(rgb112.size == 112 * 112 * 3) { "Expected aligned 112x112 RGB image." }
        val session = faceSession ?: error("SFace model is not loaded.")
        val inputName = session.inputNames.first()
        val data = FloatArray(3 * 112 * 112)
        var p = 0
        for (channel in 0..2) for (y in 0 until 112) for (x in 0 until 112) {
            val index = (y * 112 + x) * 3 + channel
            // OpenCV FaceRecognizerSF::feature uses blobFromImage(scale=1, mean=0,
            // swapRB=true). The caller already supplies RGB bytes, so preserve 0..255
            // values and do not apply ArcFace-style [-1, 1] normalization here.
            data[p++] = (rgb112[index].toInt() and 0xff).toFloat()
        }
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
    }
}
