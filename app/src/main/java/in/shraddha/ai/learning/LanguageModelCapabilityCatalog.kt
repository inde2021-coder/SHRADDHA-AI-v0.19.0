package `in`.shraddha.ai.learning

/** Evidence-based model candidates; no candidate is marked as installed in this APK. */
object LanguageModelCapabilityCatalog {
    enum class IntegrationState { NOT_INTEGRATED, COMMUNITY_DATA_REQUIRED }
    data class LanguageCapability(
        val code: String,
        val name: String,
        val asrCandidate: String,
        val ttsCandidate: String,
        val translationCandidate: String,
        val state: IntegrationState,
        val note: String
    )

    val languages = listOf(
        LanguageCapability("hi", "Hindi", "AI4Bharat IndicConformer", "IndicF5 / Indic-Speak Production", "IndicTrans2", IntegrationState.NOT_INTEGRATED, "Current app voice is online custom Hindi/English only."),
        LanguageCapability("en", "English", "sherpa-onnx FastConformer / Whisper", "Indic-Speak Production / Piper candidate", "IndicTrans2", IntegrationState.NOT_INTEGRATED, "No offline ASR or TTS checkpoint is bundled."),
        LanguageCapability("sa", "Sanskrit", "AI4Bharat IndicConformer", "Indic-Speak Preview", "IndicTrans2", IntegrationState.NOT_INTEGRATED, "Preview TTS requires Sanskrit pronunciation review; classical grammar is a separate content task."),
        LanguageCapability("mr", "Marathi", "AI4Bharat IndicConformer", "IndicF5 / Indic-Speak Production", "IndicTrans2", IntegrationState.NOT_INTEGRATED, "Devanagari text must not be automatically assumed to be Hindi."),
        LanguageCapability("pav", "Pawari", "No verified model", "No verified model", "No validated parallel corpus/model", IntegrationState.COMMUNITY_DATA_REQUIRED, "Requires consented local-variety speech/text and fluent-speaker review.")
    )

    fun get(code: String): LanguageCapability? = languages.firstOrNull { it.code.equals(code, true) }
}
