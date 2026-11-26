package com.Lia.assistant.data

enum class LanguagePreference(val displayName: String, val description: String) {
    AUTO("Auto-detect", "Follows whatever you speak (Hindi, Hinglish or English)"),
    HINDI("Hindi", "Natural Hindi; optional downloadable offline speech recognition"),
    HINGLISH("Hinglish", "Conversational blend of Hindi and English"),
    ENGLISH("English", "Clear and natural English"),
    MARATHI("Marathi", "मराठीत उत्तरे; offline ASR model awaits a verified checksum"),
    SANSKRIT("Sanskrit", "संस्कृत में उत्तर; offline ASR requires the downloadable verified model"),
    PAWARI("Pawari", "पावरी बोली; choose a community-verified local variety");

    companion object {
        fun fromString(value: String): LanguagePreference {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}
