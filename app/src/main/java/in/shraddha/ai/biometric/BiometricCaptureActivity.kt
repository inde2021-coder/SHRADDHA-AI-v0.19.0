package `in`.shraddha.ai.biometric

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.android.gms.tasks.Tasks
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Foreground, user-started biometric capture. Samples are processed locally and discarded. */
class BiometricCaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PERSON_ID = "person_id"
        const val EXTRA_PERSON_NAME = "person_name"
        const val EXTRA_CONSENT = "consent"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_RESULT = "result_text"
        private const val CAMERA_PERMISSION = 701
        private const val AUDIO_PERMISSION = 702
    }
    private lateinit var message: TextView
    private lateinit var action: Button
    private lateinit var cameraExecutor: ExecutorService
    private val challengeIndex = AtomicInteger(0)
    private val blinkClosed = AtomicBoolean(false)
    private val livenessPassed = AtomicBoolean(false)
    private val challenges = listOf("BLINK", "TURN_LEFT", "TURN_RIGHT").shuffled()
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var detector: com.google.mlkit.vision.face.FaceDetector? = null
    private var previewView: PreviewView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val randomPhrase = listOf("blue river seven", "silver mango twenty", "quiet garden five", "orange window nine").random()
    private var busy = false
    private var activeMode = "enroll"
    private var activePersonId = ""
    private var activePersonName = ""
    private var activeThreshold = Float.NaN
    private val engine by lazy { LocalBiometricModelEngine() }
    private val vault by lazy { BiometricConsentVault(EncryptedFeatureStore(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "face"
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "enroll"
        val id = intent.getStringExtra(EXTRA_PERSON_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
        val consent = intent.getBooleanExtra(EXTRA_CONSENT, false)
        val threshold = intent.getFloatExtra(EXTRA_THRESHOLD, Float.NaN)
        if (!consent || id.isBlank() || name.isBlank() || (mode == "match" && !threshold.isFinite())) {
            finishWith("Consent, person profile or calibrated threshold missing; capture blocked.")
            return
        }
        val modelDirectory = File(filesDir, "biometric-models")
        val model = File(modelDirectory, if (kind == "face") "sface.onnx" else "ecapa-any-length.onnx")
        if (!model.isFile) { finishWith("Required local biometric model is not imported."); return }
        try {
            if (kind == "face") engine.loadFaceModel(model.absolutePath) else engine.loadSpeakerModel(model.absolutePath)
        } catch (error: Exception) { finishWith("Biometric model could not load: ${error.message}"); return }
        if (kind == "speaker") buildSpeakerScreen(mode, id, name, threshold)
        else buildFaceScreen(mode, id, name, threshold)
    }

    private fun baseLayout(title: String, detail: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(7, 11, 20))
            addView(TextView(this@BiometricCaptureActivity).apply { text = title; textSize = 21f; setTextColor(Color.WHITE) })
            addView(TextView(this@BiometricCaptureActivity).apply { text = detail; textSize = 13f; setTextColor(0xFFB9C8E4.toInt()); setPadding(0, 10, 0, 14) })
            message = TextView(this@BiometricCaptureActivity).apply { textSize = 14f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12) }
            addView(message)
            action = Button(this@BiometricCaptureActivity).apply { isAllCaps = false }
            addView(action, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun buildFaceScreen(mode: String, id: String, name: String, threshold: Float) {
        val root = baseLayout(if (mode == "enroll") "Face enrollment" else "Live face match", "Front camera • randomized blink and head-turn challenge • processing remains on this phone")
        previewView = PreviewView(this).also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        activeMode = mode
        activePersonId = id
        activePersonName = name
        activeThreshold = threshold
        message.text = challengeText()
        action.text = "Start live face check"
        action.setOnClickListener {
            if (imageCapture == null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
                } else startCamera()
            } else if (livenessPassed.get()) captureFace(activeMode, activePersonId, activePersonName, activeThreshold)
        }
        setContentView(root)
        action.isEnabled = true
        action.tag = Pair(mode, Triple(id, name, threshold))
    }

    private fun challengeText(): String = when (challenges.getOrNull(challengeIndex.get())) {
        "BLINK" -> "Live check 1/3: blink naturally, then open both eyes"
        "TURN_LEFT" -> "Live check 2/3: slowly turn your face to your left"
        "TURN_RIGHT" -> "Live check 3/3: slowly turn your face to your right"
        else -> "Live check complete. Tap capture to continue."
    }

    private fun startCamera() {
        val view = previewView ?: return
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f).build()
        detector = FaceDetection.getClient(options)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeChallengeFrame(proxy) }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, imageAnalysis)
                message.text = challengeText()
                action.text = "Complete the live challenge"
                action.isEnabled = false
            } catch (error: Exception) { finishWith("Camera could not start: ${error.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeChallengeFrame(proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val task = detector?.process(input)
        if (task == null) { proxy.close(); return }
        task.addOnSuccessListener { faces ->
            if (livenessPassed.get()) return@addOnSuccessListener
            if (faces.size != 1) { runOnUiThread { message.text = "Keep exactly one face in the frame." }; return@addOnSuccessListener }
            val face = faces.first()
            val step = challenges.getOrNull(challengeIndex.get()) ?: return@addOnSuccessListener
            val left = face.leftEyeOpenProbability ?: 1f
            val right = face.rightEyeOpenProbability ?: 1f
            val eyes = minOf(left, right)
            val passed = when (step) {
                "BLINK" -> { if (eyes < 0.25f) blinkClosed.set(true); blinkClosed.get() && eyes > 0.65f }
                "TURN_LEFT" -> face.headEulerAngleY < -18f
                "TURN_RIGHT" -> face.headEulerAngleY > 18f
                else -> false
            }
            if (passed) {
                val next = challengeIndex.incrementAndGet()
                if (next >= challenges.size) {
                    livenessPassed.set(true)
                    runOnUiThread { message.text = "Live challenge passed. Tap capture to finish."; action.text = "Capture and continue"; action.isEnabled = true }
                    imageAnalysis?.clearAnalyzer()
                    return@addOnSuccessListener
                }
                if (challenges[next] == "BLINK") blinkClosed.set(false)
                runOnUiThread { message.text = challengeText() }
            }
        }.addOnFailureListener { runOnUiThread { message.text = "Face tracking error: ${it.message}" } }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) { finishWith("Permission denied; biometric capture cancelled."); return }
        if (requestCode == CAMERA_PERMISSION) startCamera()
        if (requestCode == AUDIO_PERMISSION) startSpeakerChallenge()
    }

    override fun onResume() { super.onResume() }

    private fun captureFace(mode: String, id: String, name: String, threshold: Float) {
        val capture = imageCapture ?: run { finishWith("Camera capture is not ready."); return }
        if (!livenessPassed.get() || busy) return
        busy = true
        action.isEnabled = false
        message.text = "Capturing one live frame and checking face quality…"
        val file = File(cacheDir, "biometric-frame-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val embedding = faceEmbeddingFromFile(file)
                    file.delete()
                    if (mode == "enroll") {
                        vault.enroll(id, name, "face", embedding, true).getOrThrow()
                        finishWith("Face template encrypted and saved for $name. Original camera frame was discarded.")
                    } else {
                        val match = vault.match("face", embedding, threshold)
                        finishWith(match?.let { "Face match: ${it.name} • similarity %.3f. This is not proof of identity.".format(it.similarity) } ?: "Unknown person. No face template passed the calibrated threshold.")
                    }
                } catch (error: Exception) { file.delete(); finishWith("Face capture failed: ${error.message}") }
            }
            override fun onError(exception: ImageCaptureException) { file.delete(); finishWith("Camera capture failed: ${exception.message}") }
        })
    }

    private fun faceEmbeddingFromFile(file: File): FloatArray {
        var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Camera image could not be decoded")
        val rotation = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) { val matrix = Matrix().apply { postRotate(rotation) }; bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        val faces = try { Tasks.await(detector!!.process(InputImage.fromBitmap(bitmap, 0))) } catch (error: Exception) { bitmap.recycle(); throw error }
        require(faces.size == 1) { "एक ही स्पष्ट चेहरा चाहिए; group/ambiguous frame स्वीकार नहीं है" }
        val face = faces.first()
        require(face.boundingBox.width() >= 120 && face.boundingBox.height() >= 120) { "चेहरा camera में और पास लाएँ" }
        require(kotlin.math.abs(face.headEulerAngleY) <= 22f && kotlin.math.abs(face.headEulerAngleX) <= 22f) { "सीधे camera की ओर देखें" }
        require((face.leftEyeOpenProbability ?: 0f) > 0.45f && (face.rightEyeOpenProbability ?: 0f) > 0.45f) { "आँखें साफ दिखाई देनी चाहिए" }
        val landmarks = listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT)
            .map { type -> face.getLandmark(type)?.position ?: error("Face alignment landmark missing: $type") }
        val source = FloatArray(10)
        landmarks.forEachIndexed { index, point -> source[index * 2] = point.x; source[index * 2 + 1] = point.y }
        val target = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
        val transform = Matrix()
        require(transform.setPolyToPoly(source, 0, target, 0, 5)) { "Five-point face alignment failed" }
        val aligned = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))
        val rgb = ByteArray(112 * 112 * 3)
        var offset = 0
        for (y in 0 until 112) for (x in 0 until 112) {
            val pixel = aligned.getPixel(x, y)
            rgb[offset++] = Color.red(pixel).toByte(); rgb[offset++] = Color.green(pixel).toByte(); rgb[offset++] = Color.blue(pixel).toByte()
        }
        bitmap.recycle(); aligned.recycle()
        return engine.faceEmbedding(rgb)
    }

    private fun buildSpeakerScreen(mode: String, id: String, name: String, threshold: Float) {
        val root = baseLayout(if (mode == "enroll") "Speaker enrollment" else "Live speaker match", "On-device speech challenge • raw audio is processed in memory and discarded")
        val phrase = TextView(this).apply { text = "Read this phrase clearly:  $randomPhrase"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 20) }
        root.addView(phrase, 1)
        message.text = "A random phrase is generated for this session. On-device speech recognition must verify it before the biometric sample is recorded."
        action.text = "Start voice challenge"
        action.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) { finishWith("On-device speech recognition is unavailable. Voice capture is blocked to avoid cloud speech upload."); return@setOnClickListener }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION); return@setOnClickListener }
            startSpeakerChallenge()
        }
        setContentView(root)
    }

    private fun startSpeakerChallenge() {
        if (busy) return
        busy = true
        action.isEnabled = false
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        speechRecognizer = recognizer
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, randomPhrase)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                val expected = randomPhrase.lowercase(Locale.US)
                if (heard.contains(expected)) recordSpeaker() else finishWith("Voice challenge phrase did not match. No biometric sample was saved.")
            }
            override fun onError(error: Int) { finishWith("On-device speech challenge failed ($error); no voiceprint saved.") }
            override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { message.text = "अब स्क्रीन पर दिया गया random phrase बोलें…" } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(request)
    }

    private fun recordSpeaker() {
        message.text = "Phrase verified locally. अब 5 सेकंड वही phrase दोहराएँ…"
        Thread({
            var recorder: AudioRecord? = null
            try {
                val rate = 16000
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minBuffer > 0) { "Microphone unavailable" }
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 16384))
                require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                val pcm = ShortArray(rate * 5)
                recorder.startRecording()
                var offset = 0
                while (offset < pcm.size) { val n = recorder.read(pcm, offset, pcm.size - offset); require(n > 0) { "Microphone read failed" }; offset += n }
                recorder.stop()
                val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
                val rms = sqrt(samples.map { it * it }.average()).toFloat()
                require(rms > 0.008f) { "आवाज़ बहुत धीमी है; शांत जगह में दोबारा कोशिश करें" }
                val embedding = engine.speakerEmbedding(samples)
                val extras = intent
                val mode = extras.getStringExtra(EXTRA_MODE) ?: "enroll"
                val id = extras.getStringExtra(EXTRA_PERSON_ID).orEmpty()
                val name = extras.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
                if (mode == "enroll") { vault.enroll(id, name, "speaker", embedding, true).getOrThrow(); finishWith("Speaker voiceprint encrypted locally for $name. Raw audio discarded.") }
                else {
                    val threshold = extras.getFloatExtra(EXTRA_THRESHOLD, Float.NaN)
                    val match = vault.match("speaker", embedding, threshold)
                    finishWith(match?.let { "Speaker match: ${it.name} • similarity %.3f. This is not proof of identity.".format(it.similarity) } ?: "Unknown speaker. No voiceprint passed the calibrated threshold.")
                }
            } catch (error: Exception) { finishWith("Speaker capture failed: ${error.message}") }
            finally { runCatching { recorder?.stop() }; runCatching { recorder?.release() } }
        }, "SpeakerCapture").start()
    }

    private fun finishWith(text: String) {
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
            if (!::action.isInitialized) { finish(); return@runOnUiThread }
            messageOrStatus(text)
            action.text = "Close"
            action.isEnabled = true
            action.setOnClickListener { finish() }
        }
    }
    private fun messageOrStatus(text: String) { if (::message.isInitialized) message.text = text }

    override fun onDestroy() {
        runCatching { speechRecognizer?.destroy() }
        runCatching { detector?.close() }
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        runCatching { engine.close() }
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }
}