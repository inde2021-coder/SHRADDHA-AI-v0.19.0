package `in`.shraddha.ai.vision

/**
 * Rate limits ambient scene analysis and proactive speech.
 * Pure Kotlin so timing and interruption rules can be verified with unit tests.
 */
object AmbientIntelligencePolicy {
    const val CLOUD_ANALYSIS_INTERVAL_MS = 8_000L
    const val ANNOUNCEMENT_COOLDOWN_MS = 45_000L
    const val MAX_OBSERVATION_CHARS = 1_200

    fun shouldAnalyzeCloudFrame(nowMs: Long, lastAnalysisMs: Long): Boolean =
        nowMs >= lastAnalysisMs && nowMs - lastAnalysisMs >= CLOUD_ANALYSIS_INTERVAL_MS


    /**
     * Cloud frame upload is a separate opt-in from camera permission and Live Eyes.
     * Local OCR/object tracking may continue when this returns false.
     */
    fun mayAnalyzeCloudFrame(
        cameraPermissionGranted: Boolean,
        cloudAnalysisConsentEnabled: Boolean,
        secureBackendReady: Boolean,
        nowMs: Long,
        lastAnalysisMs: Long
    ): Boolean = cameraPermissionGranted &&
        cloudAnalysisConsentEnabled &&
        secureBackendReady &&
        shouldAnalyzeCloudFrame(nowMs, lastAnalysisMs)

    fun cleanObservation(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_OBSERVATION_CHARS)

    fun shouldAnnounce(
        currentObservation: String,
        previousObservation: String,
        nowMs: Long,
        lastAnnouncementMs: Long
    ): Boolean {
        val current = cleanObservation(currentObservation)
        val previous = cleanObservation(previousObservation)
        if (current.isBlank() || current == previous) return false
        if (lastAnnouncementMs <= 0L) return true
        if (nowMs < lastAnnouncementMs) return false
        return nowMs - lastAnnouncementMs >= ANNOUNCEMENT_COOLDOWN_MS
    }

    fun buildScenePrompt(assistantName: String, previousObservation: String): String = buildString {
        append("You are $assistantName, an ambient visual companion. Analyze the current camera frame as an environment, not as surveillance. ")
        append("Describe the room or outdoor setting, visible objects, spatial layout, readable signs, changes, ongoing non-sensitive activities, and practical context. ")
        append("Notice obvious environmental hazards such as smoke, fire, spills, blocked paths, or approaching vehicles, but report only clearly supported evidence and state uncertainty. ")
        append("Do not identify people, infer identity, age, health, religion, or private traits. Never diagnose emotions from facial appearance. ")
        append("If a person appears alone or quiet, do not assume they are sad or ask intrusive questions; use a gentle optional check-in only when the visual context genuinely supports it. ")
        append("Compare with the previous scene only when evidence supports a change. Do not invent sound, motion, intent, or events from a still frame. ")
        if (previousObservation.isNotBlank()) {
            append("Previous scene context: ")
            append(cleanObservation(previousObservation))
            append(". ")
        }
        append("Reply in natural Hindi in 1-3 short sentences. First give the most useful grounded observation, then a helpful suggestion or gentle question only if appropriate. ")
        append("Do not claim to have taken an action. Current request: आसपास के वातावरण को समझो और उपयोगी बदलाव बताओ।")
    }
}
