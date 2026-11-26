package com.Lia.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun testBlankAndEmpty() {
        assertEquals("UNKNOWN", LanguageDetector.detect(""))
        assertEquals("UNKNOWN", LanguageDetector.detect("   "))
    }

    @Test
    fun devanagariAutoDetectionDoesNotGuessHindiForMarathiSanskritOrPawari() {
        assertEquals("DEVANAGARI_UNSPECIFIED", LanguageDetector.detect("नमस्ते, आप कैसे हैं?"))
        assertEquals("DEVANAGARI_UNSPECIFIED", LanguageDetector.detect("नमस्कार, तुम्ही कसे आहात?"))
        assertEquals("DEVANAGARI_UNSPECIFIED", LanguageDetector.detect("कर्मण्येवाधिकारस्ते मा फलेषु कदाचन"))
        assertEquals("DEVANAGARI_UNSPECIFIED", LanguageDetector.detect("पावरी बोलीचा नमुना"))
    }

    @Test
    fun testHinglishShortAndLong() {
        assertEquals("HINGLISH", LanguageDetector.detect("kya haal hai yaar"))
        assertEquals("HINGLISH", LanguageDetector.detect("bhai suno"))
        assertEquals("HINGLISH", LanguageDetector.detect("aaj mausam kaisa hai aur baarish hogi kya"))
    }

    @Test
    fun detectedLanguagesSelectMatchingVoiceModel() {
        assertEquals("hi", LanguageDetector.toVoiceModelLanguage("HINDI"))
        assertEquals("hi", LanguageDetector.toVoiceModelLanguage("hi-IN"))
        assertEquals("en", LanguageDetector.toVoiceModelLanguage("HINGLISH"))
        assertEquals("en", LanguageDetector.toVoiceModelLanguage("ENGLISH"))
    }


    @Test
    fun devanagariScriptIsNotEnoughToIdentifyHindiVersusMarathiOrSanskrit() {
        assertEquals("DEVANAGARI", LanguageDetector.detectScript("नमस्कार"))
        assertEquals("LATIN", LanguageDetector.detectScript("hello"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedCustomVoiceLanguageDoesNotSilentlyFallBackToEnglish() {
        LanguageDetector.toVoiceModelLanguage("MARATHI")
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousDevanagariDoesNotSilentlySelectHindiOrEnglishVoice() {
        LanguageDetector.toVoiceModelLanguage("DEVANAGARI_UNSPECIFIED")
    }

    @Test
    fun testEnglish() {
        assertEquals("ENGLISH", LanguageDetector.detect("what's the weather"))
        assertEquals("ENGLISH", LanguageDetector.detect("Can you open YouTube for me please?"))
    }
}
