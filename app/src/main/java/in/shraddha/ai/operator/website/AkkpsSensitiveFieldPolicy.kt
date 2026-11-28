package `in`.shraddha.ai.operator.website

/** Prevents authentication and verification controls from entering AI-visible Runner inventory. */
internal object AkkpsSensitiveFieldPolicy {
    private val sensitiveLabel = Regex(
        "(?i)\\b(password|passcode|otp|one.?time|pin|cvv|cvc|captcha|verification|security.?code|secret.?key|recovery.?phrase)\\b|" +
            "पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड"
    )
    private val sensitiveTypes = setOf("password", "hidden", "file")

    fun isSensitive(type: String?, label: String?): Boolean =
        type.orEmpty().trim().lowercase() in sensitiveTypes ||
            sensitiveLabel.containsMatchIn(label.orEmpty())
}
