package `in`.shraddha.ai.biometric

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Canonical, consent-triggered ONNX model repository.
 *
 * This is the single source of truth for model identity, URL, checksum, size cap,
 * cache validation and atomic installation. Models stay in app-private storage.
 */
object BiometricModelInstaller {
    data class ModelSpec(
        val kind: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        val expectedBytes: Long? = null,
        val maxBytes: Long = DEFAULT_MAX_BYTES
    )

    companion object {
        const val DEFAULT_MAX_BYTES = 100L * 1024L * 1024L
        private const val MODEL_DIRECTORY = "biometric-models"

        // Pins are aligned with docs/BIOMETRIC-MODEL-CATALOG.md and the inference contracts.
        private val models = listOf(
            ModelSpec(
                "face", "sface.onnx",
                "https://huggingface.co/opencv/face_recognition_sface/resolve/89e1f6f/face_recognition_sface_2021dec.onnx",
                "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
                expectedBytes = 38_696_353L
            ),
            ModelSpec(
                "speaker", "ecapa-any-length.onnx",
                "https://huggingface.co/Pawls2theWall/equivl-ecapa-voxceleb-onnx/resolve/dd6e443/model_fp16.onnx",
                "c59760b0a30dd905e3757cbb9ddf5dde22f9a099dc3e74de77c263cbb202e67c"
            ),
            ModelSpec(
                "anti_spoof", "minifasnet-v2.onnx",
                "https://huggingface.co/garciafido/minifasnet-v2-anti-spoofing-onnx/resolve/d29c87568ca9b5662da803b10f217c4db20b142b/minifasnet_v2.onnx",
                "d7b3cd9ba8a7ceb13baa8c4720902e27ca3112eff52f926c08804af6b6eecc7b",
                expectedBytes = 1_744_116L
            )
        )

        fun supportedModels(): List<ModelSpec> = models.toList()
    }

    @Volatile private var verifiedFiles: Map<String, Pair<Long, Long>> = emptyMap()
    @Volatile private var failure: String? = null

    @Synchronized
    fun ensureInstalled(context: Context, kind: String): File {
        val model = models.singleOrNull { it.kind == kind }
            ?: throw IllegalArgumentException("Unsupported ONNX model type: $kind")
        val directory = File(context.applicationContext.filesDir, MODEL_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private model directory." }

        val target = File(directory, model.fileName)
        val cachedFingerprint = verifiedFiles[model.fileName]
        if (target.isFile && cachedFingerprint == (target.length() to target.lastModified())) return target
        if (target.isFile && sha256(target) == model.sha256) {
            verifiedFiles = verifiedFiles + (model.fileName to (target.length() to target.lastModified()))
            failure = null
            return target
        }

        val temporary = File(directory, model.fileName + ".part")
        val previous = File(directory, model.fileName + ".previous")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                temporary.delete()
                val connection = URI(model.url).toURL().openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 180_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "ShraddhaAI-Android/0.19.2")
                    val responseCode = connection.responseCode
                    check(responseCode == HttpURLConnection.HTTP_OK) {
                        "HTTP $responseCode while downloading ${model.fileName}."
                    }
                    check(connection.url.protocol.equals("https", ignoreCase = true)) {
                        "ONNX model download redirected to a non-HTTPS URL."
                    }
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > 0) {
                        require(declaredLength <= model.maxBytes) { "ONNX model exceeds the size safety limit." }
                        model.expectedBytes?.let { require(declaredLength == it) { "Pinned ONNX model size changed." } }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= model.maxBytes) { "ONNX model exceeds the size safety limit." }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    require(total > 1_024L) { "Downloaded ONNX model is empty or unexpectedly small." }
                    if (declaredLength > 0) require(total == declaredLength) {
                        "Downloaded ONNX model length differs from the server declaration."
                    }
                    model.expectedBytes?.let { require(total == it) { "Pinned ONNX model byte length mismatch." } }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(model.sha256, ignoreCase = true)) {
                        "SHA-256 verification failed for ${model.fileName}."
                    }
                } finally {
                    connection.disconnect()
                }

                if (previous.exists()) check(previous.delete()) { "Could not clear stale model backup." }
                val hadTarget = target.exists()
                if (hadTarget) check(target.renameTo(previous)) { "Could not preserve the previous model." }
                if (!temporary.renameTo(target)) {
                    if (hadTarget) previous.renameTo(target)
                    error("Could not atomically install the verified ONNX model.")
                }
                if (previous.exists()) previous.delete()
                verifiedFiles = verifiedFiles + (model.fileName to (target.length() to target.lastModified()))
                failure = null
                return target
            } catch (error: Exception) {
                lastError = error
                temporary.delete()
                if (!target.exists() && previous.exists()) previous.renameTo(target)
                if (attempt < 3) Thread.sleep(1_000L * attempt)
            }
        }
        failure = lastError?.message ?: "Unknown model installation error"
        throw IllegalStateException(
            "Could not install and verify ${model.fileName} after 3 attempts: $failure",
            lastError
        )
    }

    fun status(): String? = failure?.let { "ONNX model installation failed: $it. Retry is available." }

    @Synchronized
    fun invalidateCacheForTests() {
        verifiedFiles = emptyMap()
        failure = null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
