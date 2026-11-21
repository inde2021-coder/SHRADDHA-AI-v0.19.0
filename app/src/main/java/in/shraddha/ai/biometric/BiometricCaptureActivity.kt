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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    @Volatile private var modelLoadThread: Thread? = null
    @Volatile private var captureThread: Thread? = null
    private var livenessGate = BiometricLivenessGate()
    private val livenessPassed = AtomicBoolean(false)
    @Volatile private var livenessPassedAtMs = 0L
    private val captureCancelled = AtomicBoolean(false)
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: com.google.mlkit.vision.face.FaceDetector? = null
    private var previewView: PreviewView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speechTimeout = Runnable {
        if (busy && speechRecognizer != null) {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            busy = false
            finishWith("Voice challenge timed out. No sample was saved; restart the capture.")
        }
    }
    @Volatile private var activeRecorder: AudioRecord? = null
    // Three independently selected words provide thousands of fresh spoken challenges
    // per language, reducing trivial replay compared with a short fixed phrase list.
    private val randomChallenge: Pair<String, String> = if (kotlin.random.Random.nextBoolean()) {
        val first = listOf("amber", "silver", "quiet", "green", "golden", "gentle", "bright", "blue", "violet", "narrow", "calm", "rapid")
        val second = listOf("river", "mango", "garden", "window", "forest", "candle", "mountain", "mirror", "basket", "planet", "lantern", "meadow")
        val third = listOf("seven", "twenty", "five", "nine", "thirteen", "thirty", "four", "eleven", "sixteen", "eight", "twelve", "eighteen")
        "${first.random()} ${second.random()} ${third.random()}" to "en-US"
    } else {
        val first = listOf("नीला", "सुनहरा", "शांत", "मीठा", "उजला", "हरा", "लाल", "छोटा", "तेज", "ठंडा", "चमकीला", "नया")
        val second = listOf("आसमान", "आम", "बगीचा", "खिड़की", "जंगल", "दीपक", "पहाड़", "दर्पण", "नदी", "बादल", "किताब", "कमल")
        val third = listOf("सात", "बीस", "पाँच", "नौ", "तेरह", "तीस", "चार", "ग्यारह", "सोलह", "आठ", "बारह", "अठारह")
        "${first.random()} ${second.random()} ${third.random()}" to "hi-IN"
    }
    private val randomPhrase get() = randomChallenge.first
    private val randomPhraseLocale get() = randomChallenge.second
    private var busy = false
    private var activeMode = "enroll"
    private var activePersonId = ""
    private var activePersonName = ""
    private var activeThreshold = Float.NaN
    private var faceTrackingFailed = false
    private var cameraStarting = false
    private val engineLazy = lazy { LocalBiometricModelEngine() }
    private val engine by engineLazy
    private val vault by lazy { BiometricConsentVault(EncryptedFeatureStore(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        cacheDir.listFiles()?.filter { it.name.startsWith("biometric-frame-") }?.forEach { it.delete() }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "face"
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "enroll"
        val id = intent.getStringExtra(EXTRA_PERSON_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
        val consent = intent.getBooleanExtra(EXTRA_CONSENT, false)
        val threshold = intent.getFloatExtra(EXTRA_THRESHOLD, Float.NaN)
        if (!consent || (mode == "enroll" && (id.isBlank() || name.isBlank())) || kind !in setOf("face", "speaker") || mode !in setOf("enroll", "match", "identify") || (mode != "enroll" && (!threshold.isFinite() || threshold !in -1f..1f))) {
            finishWith("Consent, person profile or calibrated threshold missing; capture blocked.")
            return
        }
        message = TextView(this).apply { text = "Installing and verifying bundled biometric models…"; setTextColor(android.graphics.Color.WHITE) }
        setContentView(LinearLayout(this).apply { setPadding(24, 32, 24, 32); setBackgroundColor(Color.rgb(7, 11, 20)); addView(message) })
        modelLoadThread = Thread({
            try {
                BiometricModelInstaller.ensureInstalled(applicationContext)
                val modelDirectory = File(filesDir, "biometric-models")
                val model = File(modelDirectory, if (kind == "face") "sface.onnx" else "ecapa-any-length.onnx")
                check(model.isFile) { "Verified biometric model is not available in private storage." }
                if (kind == "face") engine.loadFaceModel(model.absolutePath) else engine.loadSpeakerModel(model.absolutePath)
                runOnUiThread { if (!isFinishing) { if (kind == "speaker") buildSpeakerScreen(mode, id, name, threshold) else buildFaceScreen(mode, id, name, threshold) } }
            } catch (error: Exception) { finishWith("Biometric model installation/loading failed: ${error.message}") }
        }, "BiometricModelLoad").apply { start() }
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
        val root = baseLayout(
            when (mode) { "enroll" -> "Face enrollment • $name"; "identify" -> "Identify consented person"; else -> "Live face match • $name" },
            if (mode == "identify") "Search all separately consented profiles • current subject must consent • fresh capture within 8 seconds"
            else "Selected consented profile • front camera • randomized blink/head-turn challenge • on-device processing"
        )
        previewView = PreviewView(this).also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        activeMode = mode
        activePersonId = id
        activePersonName = name
        activeThreshold = threshold
        message.text = challengeText()
        action.text = "Start live face check"
        action.setOnClickListener {
            when {
                imageCapture == null -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
                    } else startCamera()
                }
                livenessPassed.get() -> captureFace(activeMode, activePersonId, activePersonName, activeThreshold)
                livenessGate.isExpired || faceTrackingFailed -> restartFaceChallenge()
            }
        }
        setContentView(root)
        action.isEnabled = true
    }

    private fun challengeText(): String = livenessGate.prompt()

    private fun startCamera() {
        if (cameraStarting) return
        val view = previewView ?: return
        cameraStarting = true
        action.isEnabled = false
        // Start the 45-second challenge window only after the user actually starts the camera.
        livenessGate = BiometricLivenessGate()
        livenessPassed.set(false)
        livenessPassedAtMs = 0L
        faceTrackingFailed = false
        runCatching { detector?.close() }
        detector = null
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f).build()
        detector = FaceDetection.getClient(options)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        try {
            cameraExecutor.execute {
                try {
                    val provider: ProcessCameraProvider = providerFuture.get()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            cameraStarting = false
                            return@runOnUiThread
                        }
                        try {
                            cameraProvider = provider
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                            imageCapture = capture
                            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { useCase ->
                                useCase.setAnalyzer(cameraExecutor) { proxy -> analyzeChallengeFrame(proxy) }
                            }
                            imageAnalysis = analysis
                            provider.unbindAll()
                            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture, analysis)
                            cameraStarting = false
                            message.text = challengeText()
                            action.text = "Complete the live challenge"
                            action.isEnabled = false
                        } catch (error: Exception) {
                            cameraStarting = false
                            finishWith("Camera could not start: ${error.message}")
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        cameraStarting = false
                        finishWith("Camera provider could not start: ${error.message}")
                    }
                }
            }
        } catch (error: Exception) {
            cameraStarting = false
            finishWith("Camera setup could not start: ${error.message}")
        }
    }

    private fun analyzeChallengeFrame(proxy: ImageProxy) {
        val media = proxy.image ?: run {
            livenessGate.observe(0, Float.NaN, Float.NaN, Float.NaN)
            proxy.close()
            if (livenessGate.isExpired) showChallengeRestart("Live challenge expired. Restart it to continue.")
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val task = detector?.process(input)
        if (task == null) {
            livenessGate.observe(0, Float.NaN, Float.NaN, Float.NaN)
            proxy.close()
            if (livenessGate.isExpired) showChallengeRestart("Live challenge expired. Restart it to continue.")
            return
        }
        task.addOnSuccessListener { faces ->
            if (livenessPassed.get()) return@addOnSuccessListener
            if (faces.size != 1) {
                livenessGate.observe(faces.size, Float.NaN, Float.NaN, Float.NaN)
                if (livenessGate.isExpired) showChallengeRestart("Live challenge expired. Restart it to continue.")
                else runOnUiThread { message.text = "Keep exactly one face in the frame. Challenge progress was reset." }
                return@addOnSuccessListener
            }
            val face = faces.first()
            val complete = livenessGate.observe(1, face.leftEyeOpenProbability ?: 1f, face.rightEyeOpenProbability ?: 1f, face.headEulerAngleY)
            if (complete) {
                livenessPassed.set(true)
                livenessPassedAtMs = SystemClock.elapsedRealtime()
                runOnUiThread { message.text = "Basic active challenge passed (not certified anti-spoofing). सीधे camera की ओर वापस आएँ, फिर capture करें."; action.text = "Capture and continue"; action.isEnabled = true }
                imageAnalysis?.clearAnalyzer()
            } else if (livenessGate.isExpired) {
                showChallengeRestart("Live challenge expired. Restart it to continue.")
            } else runOnUiThread { message.text = challengeText() }
        }.addOnFailureListener {
            livenessGate.observe(0, Float.NaN, Float.NaN, Float.NaN)
            faceTrackingFailed = true
            showChallengeRestart("Face tracking error: ${it.message}. Restart the live check.")
        }.addOnCompleteListener { proxy.close() }
    }

    private fun showChallengeRestart(text: String) {
        imageAnalysis?.clearAnalyzer()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            message.text = text
            action.text = "Restart live challenge"
            action.isEnabled = true
        }
    }

    private fun restartFaceChallenge() {
        cameraStarting = false
        imageAnalysis?.clearAnalyzer()
        runCatching { cameraProvider?.unbindAll() }
        imageCapture = null
        imageAnalysis = null
        livenessPassed.set(false)
        livenessPassedAtMs = 0L
        action.isEnabled = false
        startCamera()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) { finishWith("Permission denied; biometric capture cancelled."); return
        }
        if (requestCode == CAMERA_PERMISSION) startCamera()
        if (requestCode == AUDIO_PERMISSION) startSpeakerChallenge()
    }

    override fun onResume() { super.onResume() }

    private fun captureFace(mode: String, id: String, name: String, threshold: Float) {
        val capture = imageCapture ?: run { finishWith("Camera capture is not ready."); return }
        if (!livenessPassed.get() || busy) return
        if (!BiometricLivenessGate.isCaptureFresh(livenessPassedAtMs, SystemClock.elapsedRealtime())) {
            restartFaceChallenge()
            return
        }
        busy = true
        action.isEnabled = false
        message.text = "Capturing one live frame and checking face quality…"
        val file = File(cacheDir, "biometric-frame-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                if (!BiometricLivenessGate.isCaptureFresh(livenessPassedAtMs, SystemClock.elapsedRealtime())) {
                    file.delete()
                    busy = false
                    runOnUiThread { if (!isFinishing && !isDestroyed) restartFaceChallenge() }
                    return
                }
                try {
                    val embedding = faceEmbeddingFromFile(file)
                    file.delete()
                    try {
                        if (mode == "enroll") {
                            vault.enroll(id, name, "face", embedding, true).getOrThrow()
                            finishWith("Face template encrypted and saved for $name. SFace inference ${engine.lastFaceInferenceMs} ms • model load ${engine.faceModelLoadMs} ms • original camera frame discarded.")
                        } else {
                            val match = vault.match("face", embedding, threshold, if (mode == "identify") null else id.takeIf { it.isNotBlank() })
                            finishWith(match?.let { "${if (mode == "identify") "Identified among consented profiles" else "Face match"}: ${it.name} • similarity %.3f / threshold %.3f • SFace inference ${engine.lastFaceInferenceMs} ms. This is not proof of identity.".format(it.similarity, threshold) } ?: "Unknown person. No consented face template passed threshold %.3f • SFace inference ${engine.lastFaceInferenceMs} ms.".format(threshold))
                        }
                    } finally {
                        embedding.fill(0f)
                    }
                } catch (error: Exception) {
                    file.delete()
                    busy = false
                    runOnUiThread {
                        if (!isFinishing) {
                            message.text = "Face capture failed: ${error.message}. सुधार करके फिर capture करें।"
                            action.text = "Capture again"
                            action.isEnabled = true
                        }
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                busy = false
                runOnUiThread {
                    if (!isFinishing) {
                        message.text = "Camera capture failed: ${exception.message}"
                        action.text = "Capture again"
                        action.isEnabled = true
                    }
                }
            }
        })
    }

    private fun faceEmbeddingFromFile(file: File): FloatArray {
        var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Camera image could not be decoded")
        var aligned: Bitmap? = null
        var rawRgb: ByteArray? = null
        var rawGray: IntArray? = null
        var sourceLandmarks: FloatArray? = null
        var targetLandmarks: FloatArray? = null
        var transformValues: FloatArray? = null
        try {
            val rotation = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotated
            }
            val faces = Tasks.await(detector!!.process(InputImage.fromBitmap(bitmap, 0)))
            require(faces.size == 1) { "एक ही स्पष्ट चेहरा चाहिए; group/ambiguous frame स्वीकार नहीं है" }
            val face = faces.first()
            require(face.boundingBox.width() >= 120 && face.boundingBox.height() >= 120) { "चेहरा camera में और पास लाएँ" }
            require(kotlin.math.abs(face.headEulerAngleY) <= 12f && kotlin.math.abs(face.headEulerAngleX) <= 12f) { "चेहरा सीधा camera की ओर रखें; head pose center में होना चाहिए" }
            require((face.leftEyeOpenProbability ?: 0f) > 0.45f && (face.rightEyeOpenProbability ?: 0f) > 0.45f) { "आँखें साफ दिखाई देनी चाहिए" }
            val landmarks = listOf(FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT)
                .map { type -> face.getLandmark(type)?.position ?: error("Face alignment landmark missing: $type") }
            val source = FloatArray(10)
            sourceLandmarks = source
            landmarks.forEachIndexed { index, point -> source[index * 2] = point.x; source[index * 2 + 1] = point.y }
            val target = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
            targetLandmarks = target
            val transformArray = FaceAlignmentMath.similarityTransform(source, target)
            transformValues = transformArray
            val transform = Matrix().apply { setValues(transformArray) }
            val alignedBitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
            aligned = alignedBitmap
            Canvas(alignedBitmap).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))

            val gray = IntArray(112 * 112)
            rawGray = gray
            var brightness = 0.0
            for (y in 0 until 112) for (x in 0 until 112) {
                val pixel = alignedBitmap.getPixel(x, y)
                val value = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                gray[y * 112 + x] = value
                brightness += value
            }
            val meanBrightness = brightness / gray.size
            var lapSum = 0.0
            var lapSquare = 0.0
            var lapCount = 0
            for (y in 1 until 111) for (x in 1 until 111) {
                val i = y * 112 + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - 112] - gray[i + 112]
                lapSum += lap
                lapSquare += lap.toDouble() * lap
                lapCount++
            }
            val sharpness = lapSquare / lapCount - (lapSum / lapCount) * (lapSum / lapCount)
            require(meanBrightness in 35.0..220.0) { "रोशनी कम/ज्यादा है; समान रोशनी में फिर से capture करें" }
            require(sharpness > 18.0) { "चेहरा धुंधला है; फोन स्थिर रखकर दोबारा capture करें" }

            val rgb = ByteArray(112 * 112 * 3)
            rawRgb = rgb
            var offset = 0
            for (y in 0 until 112) for (x in 0 until 112) {
                val pixel = alignedBitmap.getPixel(x, y)
                rgb[offset++] = Color.red(pixel).toByte()
                rgb[offset++] = Color.green(pixel).toByte()
                rgb[offset++] = Color.blue(pixel).toByte()
            }
            return engine.faceEmbedding(rgb)
        } finally {
            rawRgb?.fill(0)
            rawGray?.fill(0)
            sourceLandmarks?.fill(0f)
            targetLandmarks?.fill(0f)
            transformValues?.fill(0f)
            if (!bitmap.isRecycled) bitmap.recycle()
            aligned?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun buildSpeakerScreen(mode: String, id: String, name: String, threshold: Float) {
        val root = baseLayout(
            when (mode) { "enroll" -> "Speaker enrollment • $name"; "identify" -> "Identify consented speaker"; else -> "Live speaker match • $name" },
            if (mode == "identify") "Search all separately consented profiles • on-device speech challenge • current speaker must consent to this session"
            else "Selected consented profile • on-device speech challenge • raw audio is processed in memory and discarded"
        )
        val phrase = TextView(this).apply { text = "Read this phrase clearly:  $randomPhrase"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 20) }
        root.addView(phrase, 1)
        message.text = "A random phrase is checked on-device, then a separate 5-second voice sample is captured. These are two capture steps, not certified anti-spoofing."
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
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } catch (error: Exception) {
            busy = false
            finishWith("On-device speech recognizer could not start: ${error.message}")
            return
        }
        speechRecognizer = recognizer
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, randomPhraseLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, randomPhraseLocale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, randomPhrase)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                mainHandler.removeCallbacks(speechTimeout)
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (BiometricVoiceChallenge.matches(randomPhrase, heard)) recordSpeaker() else finishWith("Voice challenge phrase did not match. No biometric sample was saved.")
            }
            override fun onError(error: Int) {
                mainHandler.removeCallbacks(speechTimeout)
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
                finishWith("On-device speech challenge failed ($error); no voiceprint saved.")
            }
            override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { message.text = "अब स्क्रीन पर दिया गया random phrase बोलें…" } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        mainHandler.postDelayed(speechTimeout, 20_000L)
        try {
            recognizer.startListening(request)
        } catch (error: Exception) {
            mainHandler.removeCallbacks(speechTimeout)
            runCatching { recognizer.destroy() }
            speechRecognizer = null
            finishWith("Voice challenge could not start: ${error.message}")
        }
    }

    private fun recordSpeaker() {
        message.text = "Phrase verified locally. अब अलग 5-second voice sample में वही phrase दोहराएँ; यह handoff/replay-proof नहीं है…"
        captureThread = Thread({
            var recorder: AudioRecord? = null
            var rawPcm: ShortArray? = null
            var normalizedPcm: FloatArray? = null
            try {
                val rate = 16000
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minBuffer > 0) { "Microphone unavailable" }
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 16384))
                activeRecorder = recorder
                require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                val pcm = ShortArray(rate * 5)
                rawPcm = pcm
                check(!captureCancelled.get()) { "Voice capture cancelled" }
                recorder.startRecording()
                var offset = 0
                while (offset < pcm.size && !captureCancelled.get()) {
                    val n = recorder.read(pcm, offset, pcm.size - offset)
                    require(n > 0) { "Microphone read failed" }
                    offset += n
                }
                check(!captureCancelled.get() && offset == pcm.size) { "Voice capture cancelled" }
                recorder.stop()
                val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
                normalizedPcm = samples
                var sumSquares = 0.0
                var peak = 0f
                var clipped = 0
                var activeWindows = 0
                for (start in samples.indices step 4000) {
                    val end = minOf(start + 4000, samples.size)
                    var windowSquares = 0.0
                    for (i in start until end) {
                        val value = samples[i]
                        val magnitude = kotlin.math.abs(value)
                        sumSquares += value.toDouble() * value
                        windowSquares += value.toDouble() * value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0.995f) clipped++
                    }
                    if (sqrt(windowSquares / (end - start)) > 0.006) activeWindows++
                }
                val rms = sqrt(sumSquares / samples.size).toFloat()
                require(rms > 0.008f && activeWindows >= 3) { "आवाज़ बहुत धीमी/रुकी हुई है; शांत जगह में पूरा phrase दोहराएँ" }
                require(peak < 0.999f && clipped < samples.size / 100) { "Audio clipping/distortion है; microphone से थोड़ा दूर बोलें" }
                val embedding = engine.speakerEmbedding(samples)
                try {
                    val extras = intent
                    val mode = extras.getStringExtra(EXTRA_MODE) ?: "enroll"
                    val id = extras.getStringExtra(EXTRA_PERSON_ID).orEmpty()
                    val name = extras.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
                    if (mode == "enroll") {
                        vault.enroll(id, name, "speaker", embedding, true).getOrThrow()
                        finishWith("Speaker voiceprint encrypted locally for $name. ECAPA inference ${engine.lastSpeakerInferenceMs} ms • model load ${engine.speakerModelLoadMs} ms • raw audio discarded.")
                    } else {
                        val threshold = extras.getFloatExtra(EXTRA_THRESHOLD, Float.NaN)
                        val match = vault.match("speaker", embedding, threshold, if (mode == "identify") null else id.takeIf { it.isNotBlank() })
                        finishWith(match?.let { "${if (mode == "identify") "Identified among consented profiles" else "Speaker match"}: ${it.name} • similarity %.3f / threshold %.3f • ECAPA inference ${engine.lastSpeakerInferenceMs} ms. This is not proof of identity.".format(it.similarity, threshold) } ?: "Unknown speaker. No consented voiceprint passed threshold %.3f • ECAPA inference ${engine.lastSpeakerInferenceMs} ms.".format(threshold))
                    }
                } finally {
                    embedding.fill(0f)
                }
            } catch (error: Exception) { finishWith("Speaker capture failed: ${error.message}") }
            finally {
                rawPcm?.fill(0)
                normalizedPcm?.fill(0f)
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                activeRecorder = null
            }
        }, "SpeakerCapture").apply { start() }
    }

    private fun finishWith(text: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
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
        captureCancelled.set(true)
        activeRecorder?.let { recorder ->
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
        }
        mainHandler.removeCallbacks(speechTimeout)
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        imageAnalysis?.clearAnalyzer()
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        runCatching { detector?.close() }
        detector = null
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        Thread({
            val modelFinished = runCatching { modelLoadThread?.join(); true }.getOrDefault(false)
            val captureFinished = runCatching { captureThread?.join(); true }.getOrDefault(false)
            val cameraFinished = if (::cameraExecutor.isInitialized) {
                runCatching { cameraExecutor.awaitTermination(30, TimeUnit.SECONDS) }.getOrDefault(false)
            } else true
            if (modelFinished && captureFinished && cameraFinished) {
                cacheDir.listFiles()?.filter { it.name.startsWith("biometric-frame-") }?.forEach { it.delete() }
                if (engineLazy.isInitialized()) runCatching { engine.close() }
            }
        }, "BiometricCleanup").start()
        super.onDestroy()
    }
}