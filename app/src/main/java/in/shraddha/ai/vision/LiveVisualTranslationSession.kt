package `in`.shraddha.ai.vision

import `in`.shraddha.ai.link.DeviceLinkManager
import android.os.SystemClock

/** Live frame-to-text-translation loop. One frame at a time; stale frames are never queued. */
class LiveVisualTranslationSession(
    private val baseUrl: String,
    private val gatewayToken: String,
    private val provider: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val onUpdate: (String) -> Unit,
    private val audit: (String, String) -> Unit = { _, _ -> },
    private val intervalMs: Long = 1_500L
) {
    private val gate = LiveVisualTranslationGate(intervalMs)

    fun start() {
        require(baseUrl.startsWith("https://")) { "Live translation requires an HTTPS AI backend" }
        require(sourceLanguage in LANGUAGES && targetLanguage in LANGUAGES && sourceLanguage != targetLanguage)
        gate.start()
        audit("started", "provider=$provider; source=$sourceLanguage; target=$targetLanguage")
    }

    fun stop() {
        gate.stop()
        audit("stopped", "frame_content_not_logged")
    }

    fun offerFrame(jpeg: ByteArray) {
        if (jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES || !gate.tryBegin(SystemClock.elapsedRealtime())) return
        val frame = jpeg.copyOf()
        val prompt = "Read all legible text in this live camera frame. Return a concise translation from $sourceLanguage to $targetLanguage. Preserve names, numbers, dates and units exactly. If no text is visible, say 'No readable text'. Mark uncertain words as [unclear]. Do not infer missing content."
        audit("frame_requested", "bytes=${frame.size}; frame_content_not_logged")
        DeviceLinkManager.analyzeScreen(baseUrl, gatewayToken, provider, prompt, frame, "document") { result ->
            frame.fill(0)
            result.onSuccess { translated ->
                if (gate.isActive()) onUpdate(translated)
                audit("frame_completed", "result_not_logged")
            }.onFailure { error ->
                if (gate.isActive()) onUpdate("Live translation error: ${error.message ?: "backend unavailable"}")
                audit("frame_failed", "error_type=${error.javaClass.simpleName}")
            }
            gate.complete()
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 2_500_000
        private val LANGUAGES = setOf("auto", "hi", "en", "mr", "es", "fr", "de")
    }
}

/** Deterministic throttle: no overlapping calls and no backlog of old camera frames. */
class LiveVisualTranslationGate(private val intervalMs: Long = 1_500L) {
    private var active = false
    private var inFlight = false
    private var lastStartedAt = Long.MIN_VALUE

    @Synchronized fun start() { active = true; inFlight = false; lastStartedAt = Long.MIN_VALUE }
    @Synchronized fun stop() { active = false; inFlight = false }
    @Synchronized fun isActive(): Boolean = active
    @Synchronized fun tryBegin(now: Long): Boolean {
        if (!active || inFlight || (lastStartedAt != Long.MIN_VALUE && now - lastStartedAt < intervalMs)) return false
        inFlight = true
        lastStartedAt = now
        return true
    }
    @Synchronized fun complete() { inFlight = false }
}
