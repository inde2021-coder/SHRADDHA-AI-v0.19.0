package `in`.shraddha.ai.biometric

import java.text.Normalizer
import java.util.Locale

object BiometricVoiceChallenge {
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun matches(expected: String, actual: String): Boolean {
        val a = normalize(expected)
        val b = normalize(actual)
        return a.isNotBlank() && b.isNotBlank() && (a == b || b.contains(a))
    }
}
