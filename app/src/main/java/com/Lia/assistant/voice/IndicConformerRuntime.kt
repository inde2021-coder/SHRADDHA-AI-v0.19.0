package com.Lia.assistant.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Optional local-only IndicConformer runtime. It never authorizes cloud fallback. */
object IndicConformerRuntime {
    enum class Language(val code: String, val label: String) {
        HINDI("hi", "Hindi"), MARATHI("mr", "Marathi"), SANSKRIT("sa", "Sanskrit")
    }
    enum class Availability { READY, CHECKSUM_NOT_PINNED, MODEL_MISSING, TOKENS_MISSING, INVALID_MODEL }
    data class Pack(
        val language: Language, val modelUrl: String, val modelSha256: String?,
        val tokensUrl: String, val tokensSha256: String?
    ) {
        val checksumsPinned: Boolean get() = isSha256(modelSha256) && isSha256(tokensSha256)
    }
    data class Status(val language: Language, val availability: Availability, val detail: String)

    // Hindi model digest is published in Hugging Face Xet file metadata.
    // Shared tokenizer and other model digests are not guessed; those packs stay blocked.
    private val packs = mapOf(
        Language.HINDI to Pack(
            Language.HINDI,
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/1a1e39e88931f4d6f19642085dcad5c47353d965/hi/model.int8.onnx",
            "915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa",
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/1a1e39e88931f4d6f19642085dcad5c47353d965/tokens.txt",
            "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
        ),
        Language.MARATHI to Pack(
            Language.MARATHI,
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/mr/model.int8.onnx",
            null,
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/1a1e39e88931f4d6f19642085dcad5c47353d965/tokens.txt",
            "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
        ),
        Language.SANSKRIT to Pack(
            Language.SANSKRIT,
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/46e904e48b5fc622eaf99588f827744cc0ba8d76/sa/model.int8.onnx",
            "08a878cdc31ef93c2a05545dbd0c44c5a95b3884d0754104beed984262a5b981",
            "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/1a1e39e88931f4d6f19642085dcad5c47353d965/tokens.txt",
            "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
        )
    )
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES).followRedirects(true).build()

    fun status(context: Context, language: Language): Status {
        val pack = packs.getValue(language)
        if (!pack.checksumsPinned) return Status(language, Availability.CHECKSUM_NOT_PINNED,
            "इस भाषा के model/token files के verified SHA-256 अभी pin नहीं हैं। सुरक्षा के लिए download/load रोका गया है।")
        val dir = modelDirectory(context, language)
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile) return Status(language, Availability.MODEL_MISSING, "Offline model डाउनलोड नहीं हुआ है।")
        if (!tokens.isFile) return Status(language, Availability.TOKENS_MISSING, "Tokenizer file उपलब्ध नहीं है।")
        if (!sha256(model).equals(pack.modelSha256, true) || !sha256(tokens).equals(pack.tokensSha256, true))
            return Status(language, Availability.INVALID_MODEL, "SHA-256 जाँच विफल। Model इस्तेमाल नहीं होगा।")
        return Status(language, Availability.READY, "Offline ${language.label} model verified और तैयार है।")
    }

    /** Downloads only files with source-controlled SHA-256 pins; .part is verified before rename. */
    fun download(context: Context, language: Language, onProgress: (String) -> Unit = {}) {
        val pack = packs.getValue(language)
        require(pack.checksumsPinned) {
            "${language.label} model download blocked: model और tokenizer SHA-256 pin नहीं हैं।"
        }
        val dir = modelDirectory(context, language)
        check(dir.exists() || dir.mkdirs()) { "Model storage folder could not be created." }
        downloadVerified(pack.modelUrl, pack.modelSha256!!, File(dir, "model.int8.onnx"), onProgress)
        downloadVerified(pack.tokensUrl, pack.tokensSha256!!, File(dir, "tokens.txt"), onProgress)
        val verified = status(context, language)
        check(verified.availability == Availability.READY) { verified.detail }
    }

    private fun downloadVerified(url: String, expectedSha: String, destination: File, onProgress: (String) -> Unit) {
        require(isSha256(expectedSha)) { "A pinned SHA-256 digest is required." }
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        val request = Request.Builder().url(url).get().header("User-Agent", "ShraddhaAI-Offline-ASR").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Model download failed (HTTP ${response.code})." }
            val body = requireNotNull(response.body) { "Model download returned an empty response." }
            FileOutputStream(partial).buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= 350L * 1024L * 1024L) { "Model artifact exceeded the 350 MB safety limit." }
                        output.write(buffer, 0, read)
                        onProgress("Downloaded ${total / (1024 * 1024)} MB")
                    }
                    buffer.fill(0)
                }
            }
        }
        if (!sha256(partial).equals(expectedSha, true)) {
            partial.delete()
            throw SecurityException("SHA-256 mismatch. Downloaded file deleted; it will not be loaded.")
        }
        if (destination.exists() && !destination.delete()) {
            partial.delete()
            throw IllegalStateException("Could not replace previous model file.")
        }
        check(partial.renameTo(destination)) {
            partial.delete()
            "Verified model could not be atomically installed."
        }
    }

    /** Offline one-utterance decode; input is mono Float32 PCM sampled at 16 kHz. */
    fun transcribe(context: Context, language: Language, pcm16kMono: FloatArray): String {
        require(pcm16kMono.isNotEmpty()) { "Audio is empty." }
        require(pcm16kMono.size <= 16_000 * 60) { "Audio segment is longer than 60 seconds." }
        val current = status(context, language)
        check(current.availability == Availability.READY) { current.detail }
        val dir = modelDirectory(context, language)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(dir, "model.int8.onnx").absolutePath),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2, provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
        val recognizer = OfflineRecognizer(config = config)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(16_000, pcm16kMono)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
            runCatching { recognizer.release() }
        }
    }

    private fun modelDirectory(context: Context, language: Language) =
        File(context.noBackupFilesDir, "speech/indicconformer/${language.code}")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun isSha256(value: String?) = value != null && value.matches(Regex("(?i)[a-f0-9]{64}"))
}
