package com.Lia.assistant.voice

object LanguageDetector {

    private val HINGLISH_MARKERS = setOf(
        "hai", "hain", "ho", "kya", "kyun", "kaise", "nahi", "haan",
        "mujhe", "tumhe", "aap", "mera", "meri", "mere", "yaar", "bhai",
        "accha", "theek", "matlab", "karna", "karo", "raha", "rahi", "rahe",
        "chahiye", "batao", "suno", "dekho", "abhi", "phir", "lekin", "bahut",
        "thoda", "kuch", "sab", "wala", "wali", "wale", "hoga", "hogi", "hoge",
        "bolo", "samjha", "samjhe", "arre", "namaste", "dost", "kaun", "kab",
        "kahan", "bhi", "toh", "aur", "pe", "se", "ko", "ne", "ka", "ki", "ke"
    )

    /** Maps detected text language to the installed custom voice model language. */
    fun toVoiceModelLanguage(language: String): String = when (language.trim().uppercase()) {
        "HI", "HINDI", "HI-IN" -> "hi"
        "EN", "ENGLISH", "HINGLISH", "UNKNOWN" -> "en"
        else -> throw IllegalArgumentException("The configured custom voice currently supports Hindi and English only; no verified voice model is installed for $language.")
    }

    /** Devanagari is shared by Hindi, Marathi and Sanskrit; script detection alone cannot identify the language. */
    fun detectScript(text: String): String = when {
        text.any { it.code in 0x0900..0x097F } -> "DEVANAGARI"
        text.any { it.code in 0x0041..0x005A || it.code in 0x0061..0x007A } -> "LATIN"
        else -> "UNKNOWN"
    }

    fun detect(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "UNKNOWN"

        // Devanagari is shared by Hindi, Marathi and Sanskrit (and may contain Pawari).
        // AUTO mode must not guess a language from script alone or select the wrong TTS voice.
        if (detectScript(trimmed) == "DEVANAGARI") return "DEVANAGARI_UNSPECIFIED"

        // Tokenize Latin text
        val words = trimmed.lowercase()
            .split(Regex("[^a-zA-Z']+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return "UNKNOWN"

        val hits = words.count { HINGLISH_MARKERS.contains(it) }
        val wordCount = words.size
        val hitRatio = hits.toDouble() / wordCount.toDouble()

        return if (wordCount <= 4) {
            if (hits >= 1) "HINGLISH" else "ENGLISH"
        } else {
            if (hits >= 2 || hitRatio >= 0.15) "HINGLISH" else "ENGLISH"
        }
    }
}
