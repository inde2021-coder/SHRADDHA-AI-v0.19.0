package `in`.shraddha.ai.operator.security

import java.text.Normalizer
import java.util.Locale

/** Central guardrails for actions planned by local or remote AI models. */
object OperatorSafetyPolicy {
    enum class Risk { LOW, CONFIRM, BLOCK }
    data class Decision(val risk: Risk, val reason: String)

    // Conservative by design: a generic "code" field may be an OTP or verification code.
    private val secretPattern = Regex(
        "(?i)(?:^|[^a-z0-9])(?:password|passcode|otp|code|one[\\s-]*time[\\s-]*(?:password|code)|onetime(?:password|code)|pin|cvv|security[\\s-]*code|verification[\\s-]*code|recovery[\\s-]*phrase|account[\\s-]*number|card[\\s-]*number|upi)(?:$|[^a-z0-9])|पासवर्ड|ओटीपी|पिन|खाता[\\s-]*संख्या|कार्ड[\\s-]*नंबर|यूपीआई"
    )
    // Keep this independent of the composite pattern: generic UI labels such as "Code" are sensitive too.
    private val genericCodePattern = Regex("(?i)\\bcode\\b")
    private val separatedSecretTokens = setOf("otp", "pin", "cvv", "ओटीपी", "पिन")
    private val zeroWidthCharacters = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val destructiveWords = listOf("delete", "remove", "send", "pay", "transfer", "purchase", "buy", "publish", "post", "submit", "confirm", "place order", "हटाओ", "मिटाओ", "भुगतान", "पैसे भेज", "खरीद", "पोस्ट करो", "भेजो", "पुष्टि करो", "जमा करो")
    private val finalButtonLabels = setOf(
        "send", "send message", "submit", "pay", "pay now", "confirm", "delete", "remove", "publish", "post",
        "भेजें", "भेजो", "सबमिट", "भुगतान", "अभी भुगतान करें", "पुष्टि करें", "हटाएं", "मिटाएं", "प्रकाशित करें"
    )
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(zeroWidthCharacters, "")
            .lowercase(Locale.ROOT)

    private fun containsSeparatedSecretLabel(value: String): Boolean {
        val tokens = value.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.windowed(2, 1, partialWindows = false).any { pair ->
                pair.joinToString("") in separatedSecretTokens
            }) return true
        if (tokens.windowed(3, 1, partialWindows = false).any { group ->
                group.joinToString("") in separatedSecretTokens
            }) return true
        return false
    }

    fun assess(action: String, target: String = "", value: String = "", expectedVisibleText: String = ""): Decision {
        val all = normalize("$action $target $value $expectedVisibleText")
        val tokens = all.split(Regex("[^\\p{L}\\p{N}]+"))
        if (secretPattern.containsMatchIn(all) || genericCodePattern.containsMatchIn(all) ||
            "code" in tokens || containsSeparatedSecretLabel(all)) {
            return Decision(Risk.BLOCK, "पासवर्ड, OTP, PIN या भुगतान-सुरक्षा जानकारी AI द्वारा नहीं भरी जाएगी।")
        }
        if (destructiveWords.any { all.contains(it) }) return Decision(Risk.CONFIRM, "इस कार्रवाई से संदेश, भुगतान, खरीद, प्रकाशन या डेटा हट सकता है; स्पष्ट पुष्टि आवश्यक है।")
        if (all.contains("accessibility settings") || all.contains("unknown sources") || all.contains("install apk")) return Decision(Risk.BLOCK, "सुरक्षा सेटिंग/अनजान APK की अनुमति AI स्वयं नहीं बदलेगा।")
        return Decision(Risk.LOW, "सामान्य कार्रवाई")
    }
}
