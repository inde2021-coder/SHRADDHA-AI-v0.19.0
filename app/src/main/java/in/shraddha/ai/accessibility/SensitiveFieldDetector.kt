package `in`.shraddha.ai.accessibility

/** Shared conservative label detector for password, payment and verification controls. */
internal object SensitiveFieldDetector {
    private val labels = Regex(
        """(?i)password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|upi|account number|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड|यूपीआई"""
    )

    fun isSensitive(password: Boolean, nearbyLabels: Iterable<String>): Boolean {
        if (password) return true
        return nearbyLabels.any { it.isNotBlank() && labels.containsMatchIn(it) }
    }
}
