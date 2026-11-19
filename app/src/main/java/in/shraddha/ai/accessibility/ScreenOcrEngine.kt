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
    private val sensitive = Regex("""(?i)password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|upi|account number|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड|यूपीआई""")
    private val longDigits = Regex("""(?<!\d)\d{4,}(?!\d)""")

    fun recognize(bitmap: Bitmap, done: (Result<String>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val latinTask = latin.process(image)
        val devanagariTask = devanagari.process(image)
        Tasks.whenAllComplete(latinTask, devanagariTask).addOnSuccessListener { tasks ->
            try {
                val lines = tasks.flatMap { (it.result as? Text)?.textBlocks.orEmpty() }
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val safe = mutableListOf<String>()
                var hideFollowing = 0
                for (line in lines) {
                    if (sensitive.containsMatchIn(line)) {
                        hideFollowing = 2
                        continue
                    }
                    if (hideFollowing > 0) {
                        hideFollowing--
                        continue
                    }
                    safe += longDigits.replace(line, "••••")
                }
                val output = safe.joinToString("\n").take(6000)
                if (output.isBlank()) done(Result.failure(IllegalStateException("No readable non-sensitive screen text was found")))
                else done(Result.success(output))
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
