package `in`.shraddha.ai.vision

import android.graphics.ImageFormat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device live subtitle OCR and object tracking for camera JPEG frames.
 * No image bytes or OCR text are persisted by this processor.
 */
object LiveFrameIntelligence {
    data class Observation(
        val subtitles: String,
        val objects: List<String>,
        val trackingIds: List<Int>
    ) {
        fun toContext(): String = buildString {
            if (subtitles.isNotBlank()) append("Live subtitles/sign text: ").append(subtitles).append(". ")
            if (objects.isNotEmpty()) append("Visible tracked objects: ").append(objects.joinToString()).append(". ")
            if (trackingIds.isNotEmpty()) append("Object tracking IDs: ").append(trackingIds.joinToString()).append(".")
        }.take(1800)
    }

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val inFlight = AtomicBoolean(false)

    fun analyzeJpeg(jpeg: ByteArray, onResult: (Result<Observation>) -> Unit) {
        if (jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES || !inFlight.compareAndSet(false, true)) return
        val frame = jpeg.copyOf()
        try {
            val image = InputImage.fromByteArray(frame, 0, frame.size, 0, ImageFormat.JPEG)
            val latinTask = latin.process(image)
            val devanagariTask = devanagari.process(image)
            val objectTask = detector.process(image)
            Tasks.whenAllComplete(latinTask, devanagariTask, objectTask)
                .addOnSuccessListener {
                    try {
                        val latinText = latinTask.result?.text.orEmpty()
                        val devanagariText = devanagariTask.result?.text.orEmpty()
                        val combinedText = listOf(latinText, devanagariText)
                            .filter { it.isNotBlank() }.distinct().joinToString("\n")
                            .take(MAX_TEXT_CHARS)
                        val objects = objectTask.result.orEmpty().mapNotNull { item ->
                            val label = item.labels.maxByOrNull { it.confidence }?.text
                                ?: item.labels.firstOrNull()?.text
                            label?.takeIf { it.isNotBlank() }?.let { name ->
                                val id = item.trackingId
                                if (id == null) name else "$name#$id"
                            }
                        }.distinct().take(20)
                        val ids = objectTask.result.orEmpty().mapNotNull { it.trackingId }.distinct().take(20)
                        onResult(Result.success(Observation(combinedText, objects, ids)))
                    } catch (error: Exception) {
                        onResult(Result.failure(error))
                    } finally {
                        frame.fill(0)
                        inFlight.set(false)
                    }
                }
                .addOnFailureListener { error ->
                    frame.fill(0)
                    inFlight.set(false)
                    onResult(Result.failure(error))
                }
        } catch (error: Exception) {
            frame.fill(0)
            inFlight.set(false)
            onResult(Result.failure(error))
        }
    }

    fun close() {
        runCatching { latin.close() }
        runCatching { devanagari.close() }
        runCatching { detector.close() }
    }

    private const val MAX_FRAME_BYTES = 2_500_000
    private const val MAX_TEXT_CHARS = 1400
}
