package `in`.shraddha.ai.biometric

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads only the consent-selected biometric model into app-private storage.
 * Model bytes are pinned by SHA-256 and never shipped inside the base APK.
 */
object BiometricModelInstaller {
    private data class Model(
        val kind: String,
        val fileName: String,
        val url: String,
        val sha256: String
    )

    private val models = listOf(
        Model(
            "face",
            "sface.onnx",
            "https://huggingface.co/opencv/opencv_zoo/resolve/39a1451/models/face_recognition_sface/face_recognition_sface_2021dec.onnx",
            "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
        ),
        Model(
            "speaker",
            "ecapa-any-length.onnx",
            "https://huggingface.co/kenrunio/transcriber-speaker-onnx/resolve/4467ef1/ecapa-any-length.onnx",
            "700b63064ee98981186f59f247d28e56f7f6ab265d8b50ebb450db5a8ce21dc7"
        )
    )

    @Volatile private var verifiedFiles: Map<String, Pair<Long, Long>> = emptyMap()
    @Volatile private var failure: String? = null

    @Synchronized
    fun ensureInstalled(context: Context, kind: String) {
        require(kind == "face" || kind == "speaker") { "Unknown biometric model type." }
        val model = models.single { it.kind == kind }
        val directory = File(context.filesDir, "biometric-models")
        check(directory.exists() || directory.mkdirs()) { "Cannot create private model directory." }

        val target = File(directory, model.fileName)
        val fingerprint = verifiedFiles[model.fileName]
        if (target.isFile && fingerprint == (target.length() to target.lastModified())) return
        if (target.isFile && sha256(target) == model.sha256) {
            verifiedFiles = verifiedFiles + (model.fileName to (target.length() to target.lastModified()))
            failure = null
            return
        }

        val temporary = File(directory, model.fileName + ".part")
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
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "HTTP ${connection.responseCode} while downloading ${model.fileName}."
                    }
                    check(connection.url.protocol.equals("https", ignoreCase = true)) {
                        "Biometric model download redirected to a non-HTTPS URL."
                    }
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            input.copyTo(output, 1024 * 1024)
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                check(temporary.isFile && temporary.length() > 1024L * 1024L) {
                    "Downloaded biometric model is empty or unexpectedly small."
                }
                val actual = sha256(temporary)
                check(actual == model.sha256) {
                    "SHA-256 verification failed for ${model.fileName}."
                }
                if (target.exists()) check(target.delete()) {
                    "Could not replace an invalid cached biometric model."
                }
                check(temporary.renameTo(target)) {
                    "Could not move the verified model into private storage."
                }
                verifiedFiles = verifiedFiles + (model.fileName to (target.length() to target.lastModified()))
                failure = null
                return
            } catch (error: Exception) {
                lastError = error
                temporary.delete()
                if (attempt < 3) Thread.sleep(2_000L * attempt)
            }
        }

        failure = lastError?.message ?: "Unknown download error"
        throw IllegalStateException(
            "Could not download and verify ${model.fileName} after 3 attempts: $failure",
            lastError
        )
    }

    fun status(): String? = failure?.let { "Biometric model download failed: $it. Retry is available." }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
