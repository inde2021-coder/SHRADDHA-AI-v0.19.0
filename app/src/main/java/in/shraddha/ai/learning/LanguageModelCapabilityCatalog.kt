package `in`.shraddha.ai.learning

/** A licensed candidate is not the same as an integrated, evaluated or installed model. */
object LanguageModelCapabilityCatalog {
    enum class State { READY_TO_DOWNLOAD, CHECKSUM_BLOCKED, CANDIDATE_NOT_INTEGRATED, COMMUNITY_DATA_REQUIRED }
    data class LanguageCapability(
        val code: String, val name: String,
        val asrCandidate: String, val asrState: State,
        val ttsCandidate: String, val ttsState: State,
        val translationCandidate: String, val translationState: State,
        val note: String
    )
    val languages = listOf(
        LanguageCapability("hi", "Hindi", "AI4Bharat IndicConformer / sherpa-onnx", State.READY_TO_DOWNLOAD,
            "AI4Bharat IndicF5 (MIT; speaker reference consent required)", State.CANDIDATE_NOT_INTEGRATED,
            "AI4Bharat IndicTrans2 (MIT)", State.CANDIDATE_NOT_INTEGRATED,
            "Offline ASR runtime is integrated; download and SHA-256 verification are required. TTS/translation runtimes are not integrated."),
        LanguageCapability("en", "English", "sherpa-onnx NeMo FastConformer / Whisper candidate", State.CANDIDATE_NOT_INTEGRATED,
            "Piper / other licensed voice candidate", State.CANDIDATE_NOT_INTEGRATED,
            "IndicTrans2 English-Indic", State.CANDIDATE_NOT_INTEGRATED,
            "Existing online English path is separate. Offline English ASR/TTS is not enabled in this Android runtime."),
        LanguageCapability("sa", "Sanskrit", "AI4Bharat IndicConformer / sherpa-onnx", State.READY_TO_DOWNLOAD,
            "No release-approved Sanskrit voice", State.CANDIDATE_NOT_INTEGRATED,
            "No validated Sanskrit translation runtime", State.CANDIDATE_NOT_INTEGRATED,
            "Offline ASR runtime is integrated; model download and SHA-256 verification are required. Sanskrit pronunciation and TTS need specialist review."),
        LanguageCapability("mr", "Marathi", "AI4Bharat IndicConformer / sherpa-onnx", State.CHECKSUM_BLOCKED,
            "AI4Bharat IndicF5 (MIT; speaker reference consent required)", State.CANDIDATE_NOT_INTEGRATED,
            "AI4Bharat IndicTrans2 (MIT)", State.CANDIDATE_NOT_INTEGRATED,
            "The current Marathi ONNX digest is not independently pinned; downloads and inference remain blocked safely."),
        LanguageCapability("pav", "Pawari/Powari", "No verified district-variety model", State.COMMUNITY_DATA_REQUIRED,
            "No consented community voice model", State.COMMUNITY_DATA_REQUIRED,
            "No reviewed parallel corpus", State.COMMUNITY_DATA_REQUIRED,
            "Balaghat, Seoni, Gondia and Bhandara remain separate collection profiles. No Pawari capability is claimed until community data and native-speaker review pass.")
    )
    fun get(code: String): LanguageCapability? = languages.firstOrNull { it.code.equals(code, true) }
}
