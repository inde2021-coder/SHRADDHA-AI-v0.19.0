package `in`.shraddha.ai.operator.security

/** Central guardrails for actions planned by local or remote AI models. */
object OperatorSafetyPolicy {
    enum class Risk { LOW, CONFIRM, BLOCK }
    data class Decision(val risk: Risk, val reason: String)
    private val secretPattern = Regex("(?i)(?:^|[^a-z0-9])(?:password|passcode|otp|one-time code|pin|cvv|security code|recovery phrase)(?:$|[^a-z0-9])|पासवर्ड|ओटीपी|पिन")
    private val destructiveWords = listOf("delete", "remove", "send", "pay", "transfer", "purchase", "buy", "publish", "post", "submit", "confirm", "place order", "हटाओ", "मिटाओ", "भुगतान", "पैसे भेज", "खरीद", "पोस्ट करो", "भेजो", "पुष्टि करो", "जमा करो")
    fun assess(action: String, target: String = "", value: String = ""): Decision {
        val all = "$action $target $value".lowercase()
        if (secretPattern.containsMatchIn(all)) return Decision(Risk.BLOCK, "पासवर्ड, OTP, PIN या भुगतान-सुरक्षा जानकारी AI द्वारा नहीं भरी जाएगी।")
        if (destructiveWords.any { all.contains(it) }) return Decision(Risk.CONFIRM, "इस कार्रवाई से संदेश, भुगतान, खरीद, प्रकाशन या डेटा हट सकता है; स्पष्ट पुष्टि आवश्यक है।")
        if (all.contains("accessibility settings") || all.contains("unknown sources") || all.contains("install apk")) return Decision(Risk.BLOCK, "सुरक्षा सेटिंग/अनजान APK की अनुमति AI स्वयं नहीं बदलेगा।")
        return Decision(Risk.LOW, "सामान्य कार्रवाई")
    }
}
