package `in`.shraddha.ai.biometric

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Copies the checksum-pinned ONNX files embedded in the APK into app-private storage. */
object BiometricModelInstaller {
    private data class Model(val asset: String, val output: String, val sha256: String) {
        val assetPath: String get() = "biometric-models/$asset"
    }
    private val models = listOf(
        Model("sface.onnx", "sface.onnx", "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"),
        Model("ecapa-any-length.onnx", "ecapa-any-length.onnx", "700b63064ee98981186f59f247d28e56f7f6ab265d8b50ebb450db5a8ce21dc7")
    )
    @Volatile private var finished = false
    @Volatile private var failure: String? = null
    private var verifiedFiles: Map<String, Pair<Long, Long>> = emptyMap()

    @Synchronized
    fun ensureInstalled(context: Context) {
        val directory = File(context.filesDir, "biometric-models")
        if (finished && models.all { model ->
                val target = File(directory, model.output)
                val fingerprint = verifiedFiles[model.output]
                target.isFile && fingerprint == (target.length() to target.lastModified())
            }) return
        finished = false
        try {
            check(directory.exists() || directory.mkdirs()) { "Cannot create private model directory" }
            check(directory.exists() || directory.mkdirs()) { "Cannot create private model directory" }
            models.forEach { model ->
                val target = File(directory, model.output)
                if (target.isFile && sha256(target) == model.sha256) return@forEach
                val temporary = File(directory, model.output + ".part")
                temporary.delete()
                context.assets.open(model.assetPath).use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                check(sha256(temporary) == model.sha256) { "Bundled model checksum mismatch: ${model.output}" }
                if (target.exists()) check(target.delete()) { "Could not replace invalid model: ${model.output}" }
                check(temporary.renameTo(target)) { "Could not install model: ${model.output}" }
            }
            verifiedFiles = models.associate { model ->
                val target = File(directory, model.output)
                model.output to (target.length() to target.lastModified())
            }
            failure = null
            finished = true
        } catch (error: Exception) {
            verifiedFiles = emptyMap()
            failure = error.message ?: error.javaClass.simpleName
            // A transient storage or I/O failure must be retryable in the same process.
            finished = false
            throw error
        }
    }

    fun status(): String? = when {
        finished -> null
        failure != null -> "Last model installation failed: $failure. Retry is available."
        else -> "Biometric models are being installed locally"
    }

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
