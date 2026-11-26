package `in`.shraddha.ai.brain

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/** Local text inference. No network client, telemetry, or cloud fallback is used here. */
object LocalOfflineLlm {
    private val lock = Any()
    private var loadedPath: String? = null
    private var loadedBackend: String? = null
    private var loadedPreference: String? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile var lastRuntimeInfo: String = "No local model loaded"
        private set

    data class DeviceCheck(val allowed: Boolean, val report: String)

    fun checkDevice(context: Context, modelPath: String, backendPreference: String = "auto"): DeviceCheck {
        val model = File(modelPath).canonicalFile
        if (!model.isFile || !model.extension.equals("litertlm", true)) return DeviceCheck(false, "Compatible .litertlm model file not found.")
        val expectedDigest = context.applicationContext.getSharedPreferences("shraddha_offline_model", Context.MODE_PRIVATE)
            .getString("active_model_sha256", "").orEmpty().trim()
        if (!expectedDigest.matches(Regex("[a-fA-F0-9]{64}"))) {
            return DeviceCheck(false, "Model integrity fingerprint is missing. Remove and re-import this model to verify it before use.")
        }
        val actualDigest = runCatching { sha256File(model) }.getOrElse {
            return DeviceCheck(false, "Could not verify imported model integrity: ${it.localizedMessage ?: "file read failed"}")
        }
        if (!actualDigest.equals(expectedDigest, true)) {
            return DeviceCheck(false, "Imported model integrity check failed. The file changed after import; remove and import a trusted model again.")
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val modelBytes = model.length()
        val normalizedPreference = backendPreference.lowercase().ifBlank { "auto" }
        val alreadyLoaded = loadedPath == model.absolutePath && loadedPreference == normalizedPreference && engine != null && conversation != null
        val requiredBytes = if (alreadyLoaded) 256L * 1024L * 1024L else (modelBytes * 1.25).toLong() + 450L * 1024L * 1024L
        val storage = StatFs(context.filesDir.absolutePath)
        val freeStorage = storage.availableBytes
        val heapMax = Runtime.getRuntime().maxMemory()
        val heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val ramGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availableGb = memory.availMem / (1024.0 * 1024.0 * 1024.0)
        val modelGb = modelBytes / (1024.0 * 1024.0 * 1024.0)
        val requiredGb = requiredBytes / (1024.0 * 1024.0 * 1024.0)
        val storageGb = freeStorage / (1024.0 * 1024.0 * 1024.0)
        val lowRam = activityManager.isLowRamDevice || memory.lowMemory
        val allowed = !lowRam && memory.availMem >= requiredBytes && freeStorage >= 256L * 1024L * 1024L
        val report = "Device RAM: %.2f GB • Available now: %.2f GB • Model: %.2f GB • Estimated memory requirement: %.2f GB • Free storage: %.2f GB • Java heap: %.0f/%.0f MB • Native heap: %.0f MB • Low-RAM mode: %s".format(
            ramGb, availableGb, modelGb, requiredGb, storageGb,
            heapUsed / (1024.0 * 1024.0), heapMax / (1024.0 * 1024.0), nativeHeap / (1024.0 * 1024.0), lowRam
        ) + if (allowed) "\nMemory pre-check: PASS (runtime may still need additional memory)." else "\nMemory pre-check: BLOCKED — close background apps or import a smaller quantized model."
        return DeviceCheck(allowed, report)
    }

    fun generate(context: Context, modelPath: String, prompt: String, backendPreference: String = "auto"): String = synchronized(lock) {
        require(prompt.isNotBlank() && prompt.length <= 6000) { "Prompt must contain 1-6000 characters" }
        val model = File(modelPath).canonicalFile
        require(model.isFile && model.extension.equals("litertlm", ignoreCase = true)) { "Select a valid local .litertlm model file" }
        require(model.length() >= 10_000_000L) { "Model file is too small to be valid" }
        val preference = backendPreference.lowercase().ifBlank { "auto" }
        require(preference in setOf("auto", "cpu", "gpu", "npu")) { "Backend must be auto, cpu, gpu or npu" }
        val check = checkDevice(context, model.absolutePath, preference)
        require(check.allowed) { check.report }
        val candidates = when (preference) {
            "cpu" -> listOf("cpu")
            "gpu" -> listOf("gpu", "cpu")
            "npu" -> listOf("npu", "gpu", "cpu")
            else -> listOf("npu", "gpu", "cpu")
        }
        if (loadedPath != model.absolutePath || engine == null || conversation == null || loadedPreference != preference) {
            closeLocked()
            var lastFailure: Throwable? = null
            var loaded = false
            for (candidate in candidates) {
                val created = try {
                    val backend = when (candidate) {
                        "gpu" -> Backend.GPU()
                        "npu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                        else -> Backend.CPU()
                    }
                    Engine(EngineConfig(modelPath = model.absolutePath, backend = backend, cacheDir = context.cacheDir.absolutePath))
                } catch (error: Throwable) { lastFailure = error; continue }
                try {
                    created.initialize()
                    val assistantName = NovaPreferences.getString(
                        context.applicationContext,
                        NovaPreferences.Keys.ASSISTANT_NAME,
                        AssistantBrand.FULL_NAME
                    ).trim().take(48).ifBlank { AssistantBrand.FULL_NAME }
                    val chat = created.createConversation(ConversationConfig(
                        systemInstruction = Contents.of(
                            "You are $assistantName, a helpful bilingual Hindi and English assistant. " +
                            "You run fully offline on this device. Be accurate, concise, admit uncertainty, " +
                            "and never claim to have performed an action."
                        )
                    ))
                    engine = created
                    conversation = chat
                    loadedPath = model.absolutePath
                    loadedBackend = candidate
                    loadedPreference = preference
                    lastRuntimeInfo = "Backend: ${candidate.uppercase()} • ${check.report}"
                    loaded = true
                    break
                } catch (error: Throwable) {
                    lastFailure = error
                    runCatching { created.close() }
                }
            }
            if (!loaded) {
                lastRuntimeInfo = "All backends failed. ${check.report}"
                throw IllegalStateException("LiteRT-LM could not load model on NPU/GPU/CPU. " + (lastFailure?.message ?: "Unknown runtime error"))
            }
        }
        val response = requireNotNull(conversation).sendMessage(prompt)
        response.contents.toString().trim().ifBlank { "Local model ने कोई उत्तर नहीं दिया।" }
    }

    private fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    fun close() = synchronized(lock) { closeLocked() }
    private fun closeLocked() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedPath = null
        loadedBackend = null
        loadedPreference = null
    }
}