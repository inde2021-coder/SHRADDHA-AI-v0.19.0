package com.Lia.assistant.data

enum class LanguagePreference(val displayName: String, val description: String) {
    AUTO("Auto-detect", "Follows whatever you speak (Hindi, Hinglish or English)"),
    HINDI("Hindi", "Always responds in natural Hindi"),
    HINGLISH("Hinglish", "Conversational blend of Hindi and English"),
    ENGLISH("English", "Clear and natural English"),
    MARATHI("Marathi", "मराठीत उत्तरे; speech models are not yet integrated"),
    SANSKRIT("Sanskrit", "संस्कृत में उत्तर; speech models are not yet integrated"),
    PAWARI("Pawari", "पावरी बोली; choose a community-verified local variety");

    companion object {
        fun fromString(value: String): LanguagePreference {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}
