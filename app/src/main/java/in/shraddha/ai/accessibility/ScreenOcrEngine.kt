package `in`.shraddha.ai.accessibility

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device OCR only. Text is redacted in memory and never uploaded or persisted. */
object ScreenOcrEngine {
    data class OcrLine(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val sensitive: Boolean = false
    )

    fun recognize(bitmap: Bitmap, done: (Result<List<OcrLine>>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val latinTask = latin.process(image)
        val devanagariTask = devanagari.process(image)
        Tasks.whenAllComplete(latinTask, devanagariTask).addOnSuccessListener { tasks ->
            try {
                val lines = tasks.flatMap { task -> if (task.isSuccessful) (task.result as? Text)?.textBlocks.orEmpty() else emptyList() }
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
                    .distinctBy { it.text.trim().lowercase() }
                val safe = mutableListOf<OcrLine>()
                var hideFollowing = 0
                for (line in lines) {
                    val value = line.text.trim()
                    if (value.isBlank()) continue
                    if (SensitiveFieldDetector.isSensitive(false, listOf(value))) {
                        hideFollowing = 2
                        continue
                    }
                    if (hideFollowing > 0) {
                        hideFollowing--
                        continue
                    }
                    val bounds = line.boundingBox ?: continue
                    val digitsSensitive = SensitiveFieldDetector.containsPrivateIdentifier(value)
                    safe += OcrLine(
                        text = if (digitsSensitive) "संवेदनशील अंक छिपाए गए" else value.take(120),
                        centerX = ((bounds.exactCenterX() / bitmap.width).coerceIn(0f, 1f)),
                        centerY = ((bounds.exactCenterY() / bitmap.height).coerceIn(0f, 1f)),
                        sensitive = digitsSensitive
                    )
                }
                if (safe.none { !it.sensitive }) done(Result.failure(IllegalStateException("No readable non-sensitive screen text was found")))
                else done(Result.success(safe.take(100)))
            } catch (error: Exception) {
                done(Result.failure(error))
            } finally {
                latin.close()
                devanagari.close()
            }
        }.addOnFailureListener { error ->
            latin.close()
            devanagari.close()
            done(Result.failure(error))
        }
    }
}
