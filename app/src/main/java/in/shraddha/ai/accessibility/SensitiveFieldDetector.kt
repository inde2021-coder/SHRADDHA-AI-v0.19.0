package `in`.shraddha.ai.accessibility

/** Shared conservative label detector for password, payment and verification controls. */
internal object SensitiveFieldDetector {
    // Keep English security keywords on token boundaries: "shipping" is not a PIN field.
    private val labels = Regex(
        """(?i)(?<![A-Za-z0-9])(?:password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|upi|account number|card number)(?![A-Za-z0-9])|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड|यूपीआई"""
    )

    private val privateIdentifiers = Regex(
        """(?<!\\d)\\d{12}(?!\\d)|\\b[A-Z]{5}\\d{4}[A-Z]\\b"""
    )
    private val cardNumberCandidates = Regex("""(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)""")

    /** Redact national identifiers and Luhn-valid payment-card numbers from OCR output. */
    fun containsPrivateIdentifier(value: String): Boolean {
        if (privateIdentifiers.containsMatchIn(value)) return true
        return cardNumberCandidates.findAll(value).any { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length !in 13..19) return@any false
            var sum = 0
            var doubleDigit = false
            for (index in digits.indices.reversed()) {
                var digit = digits[index].digitToInt()
                if (doubleDigit) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
                doubleDigit = !doubleDigit
            }
            sum % 10 == 0
        }
    }

    fun isSensitive(password: Boolean, nearbyLabels: Iterable<String>): Boolean {
        if (password) return true
        return nearbyLabels.any { it.isNotBlank() && labels.containsMatchIn(it) }
    }
}
