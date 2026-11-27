package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Prevents sensitive credentials and long numeric identifiers from being sent to Cloud AI. */
internal object AkkpsRunnerSensitiveCommandPolicy {
    private val sensitiveLabels = Regex(
        "(?:password|passcode|one\\s*time\\s*password|\\botp\\b|\\bpin\\b|cvv|cvc|upi\\s*pin|atm\\s*pin|" +
            "bank\\s*account|account\\s*number|aadhaar|aadhar|आधार|ओटीपी|पासवर्ड|पिन|गुप्त\\s*कोड)",
        RegexOption.IGNORE_CASE
    )
    private val longNumber = Regex("(?<!\\d)\\d{6,}(?!\\d)")

    fun containsSensitiveData(raw: String): Boolean {
        if (raw.isBlank()) return false
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .lowercase(Locale.ROOT)
        return sensitiveLabels.containsMatchIn(normalized) || longNumber.containsMatchIn(normalized)
    }
}
