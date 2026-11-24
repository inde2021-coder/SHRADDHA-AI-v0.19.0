package `in`.shraddha.ai.accessibility

/** Conservative on-device OCR redaction. These values are never uploaded as OCR text. */
object ScreenTextRedactor {
    private val sensitiveLabels = Regex(
        """(?i)(?<![a-z0-9])(?:password|passcode|one[\s.-]?time(?:[\s.-]?password)?|otp|pin|cvv|security[\s-]?code|verification[\s-]?code|upi|account[\s]+(?:no(?:\.)?|number)|aadhaar|aadhar|bank[\s]+account|card[\s]+number|debit[\s]+card|credit[\s]+card|ifsc|recovery[\s]+phrase)(?![a-z0-9])|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड|यूपीआई|आधार|बैंक खाता|खाता संख्या|कार्ड नंबर"""
    )
    private val longNumericIdentifier = Regex("""(?<![A-Z0-9])(?:\d[ -]?){8,18}\d(?![A-Z0-9])""", RegexOption.IGNORE_CASE)
    private val panIdentifier = Regex("""(?i)\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val atIdentifier = Regex("""(?i)\b[A-Z0-9._-]{2,}@[A-Z0-9.-]{2,}\b""")

    fun isSensitiveLabel(text: String): Boolean = sensitiveLabels.containsMatchIn(text)
    fun isSensitiveIdentifier(text: String): Boolean =
        longNumericIdentifier.containsMatchIn(text) ||
            panIdentifier.containsMatchIn(text) ||
            atIdentifier.containsMatchIn(text)
}
