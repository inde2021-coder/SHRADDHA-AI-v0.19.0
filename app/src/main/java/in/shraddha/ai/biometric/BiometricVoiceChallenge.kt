package `in`.shraddha.ai.biometric

import java.util.Locale

object BiometricVoiceChallenge {
    fun normalizeTranscript(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun matches(expectedPhrase: String, recognizedText: String): Boolean {
        val expected = normalizeTranscript(expectedPhrase)
        val actual = normalizeTranscript(recognizedText)
        return expected.isNotBlank() && actual.contains(expected)
    }
}