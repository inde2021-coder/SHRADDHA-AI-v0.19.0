package `in`.shraddha.ai.accessibility

/** Shared conservative label detector for password, payment and verification controls. */
internal object SensitiveFieldDetector {
    // Keep English security keywords on token boundaries: "shipping" is not a PIN field.
    private val labels = Regex(
        """(?i)(?<![A-Za-z0-9])(?:password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|upi|account number)(?![A-Za-z0-9])|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड|यूपीआई"""
    )

    fun isSensitive(password: Boolean, nearbyLabels: Iterable<String>): Boolean {
        if (password) return true
        return nearbyLabels.any { it.isNotBlank() && labels.containsMatchIn(it) }
    }
}
