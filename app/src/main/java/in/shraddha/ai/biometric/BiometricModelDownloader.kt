package `in`.shraddha.ai.biometric

import android.content.Context
import java.io.File

/**
 * Backward-compatible asynchronous API facade.
 *
 * All model metadata, download policy, verification and installation live in
 * BiometricModelInstaller. Do not add URLs or hashes here.
 */
@Deprecated("Use BiometricModelInstaller as the canonical ONNX model repository.")
object BiometricModelDownloader {
    fun downloadFaceModel(context: Context, onComplete: (Result<File>) -> Unit) =
        download(context, "face", onComplete)

    fun downloadSpeakerModel(context: Context, onComplete: (Result<File>) -> Unit) =
        download(context, "speaker", onComplete)

    fun downloadAntiSpoofModel(context: Context, onComplete: (Result<File>) -> Unit) =
        download(context, "anti_spoof", onComplete)

    private fun download(
        context: Context,
        kind: String,
        onComplete: (Result<File>) -> Unit
    ) {
        Thread({
            val result = runCatching {
                BiometricModelInstaller.ensureInstalled(context.applicationContext, kind)
            }
            onComplete(result)
        }, "CanonicalOnnxModelInstall").start()
    }
}
