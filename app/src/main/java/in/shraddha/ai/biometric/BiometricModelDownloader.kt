package `in`.shraddha.ai.biometric

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads pinned ONNX models only after an explicit user action and verifies SHA-256. */
object BiometricModelDownloader {
    private const val FACE_URL =
        "https://huggingface.co/opencv/face_recognition_sface/resolve/89e1f6f/face_recognition_sface_2021dec.onnx"
    private const val FACE_SHA256 =
        "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
    private const val FACE_BYTES = 38_696_353L

    private const val SPEAKER_URL =
        "https://huggingface.co/Pawls2theWall/equivl-ecapa-voxceleb-onnx/resolve/dd6e443/model_fp16.onnx"
    private const val SPEAKER_SHA256 =
        "c59760b0a30dd905e3757cbb9ddf5dde22f9a099dc3e74de77c263cbb202e67c"
    private const val MAX_BYTES = 100L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    fun downloadFaceModel(context: Context, onComplete: (Result<File>) -> Unit) =
        downloadVerified(context, FACE_URL, "sface.onnx", FACE_SHA256, FACE_BYTES, onComplete)

    fun downloadSpeakerModel(context: Context, onComplete: (Result<File>) -> Unit) =
        downloadVerified(context, SPEAKER_URL, "ecapa-any-length.onnx", SPEAKER_SHA256, null, onComplete)

    private fun downloadVerified(
        context: Context,
        url: String,
        fileName: String,
        expectedSha256: String,
        expectedBytes: Long?,
        onComplete: (Result<File>) -> Unit
    ) {
        Thread({
            val directory = File(context.applicationContext.filesDir, "biometric-models")
            val target = File(directory, fileName)
            val temporary = File(directory, "$fileName.download")
            try {
                require(directory.exists() || directory.mkdirs()) { "Could not create model directory." }
                val request = Request.Builder().url(url).get()
                    .header("User-Agent", "ShraddhaAI-Android/0.19").build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Model server returned HTTP ${response.code}." }
                    val body = response.body ?: error("Model download returned an empty body.")
                    val declaredLength = body.contentLength()
                    require(declaredLength in 1..MAX_BYTES) { "Model size is missing or exceeds the 100 MB safety limit." }
                    if (expectedBytes != null) require(declaredLength == expectedBytes) {
                        "Model size changed from the pinned release; refusing the download."
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BYTES) { "Downloaded model exceeds the 100 MB safety limit." }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    require(total == declaredLength) { "Downloaded model length does not match the server declaration." }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(expectedSha256, ignoreCase = true)) {
                        "Model checksum mismatch. The downloaded file was rejected."
                    }
                }
                if (target.exists() && !target.delete()) error("Could not replace the previous model.")
                if (!temporary.renameTo(target)) error("Could not finalize the verified model.")
                onComplete(Result.success(target))
            } catch (error: Exception) {
                temporary.delete()
                onComplete(Result.failure(error))
            }
        }, "BiometricModelDownload").start()
    }
}
