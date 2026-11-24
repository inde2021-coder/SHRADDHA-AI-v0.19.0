package `in`.shraddha.ai.vision

/**
 * Keeps core Visual Lens capabilities independent from Google's optional ARCore runtime.
 * ARCore's SDK is packaged with the app, but its proprietary device service remains external.
 */
enum class VisualEngineCapability(
    val arMappingAvailable: Boolean,
    val userMessage: String
) {
    AR_READY(
        arMappingAvailable = true,
        userMessage = "ARCore उपलब्ध है। 3D world mapping शुरू की जा सकती है।"
    ),
    AR_INSTALLABLE(
        arMappingAvailable = true,
        userMessage = "इस डिवाइस पर ARCore समर्थित है, लेकिन Google की AR सर्विस इंस्टॉल/अपडेट चाहिए।"
    ),
    AR_UNSUPPORTED(
        arMappingAvailable = false,
        userMessage = "इस डिवाइस पर ARCore 3D mapping समर्थित नहीं है। कोई बात नहीं—Shraddha Visual Lens का कैमरा, OCR और AI दृश्य विश्लेषण ARCore के बिना काम करते हैं।"
    ),
    AR_STATUS_UNKNOWN(
        arMappingAvailable = false,
        userMessage = "ARCore की उपलब्धता जाँची नहीं जा सकी। सामान्य Visual Lens फिर भी ARCore के बिना उपलब्ध है।"
    );

    companion object {
        fun fromArCoreStatus(status: String): VisualEngineCapability =
            when (status.substringAfterLast('.').uppercase()) {
                "SUPPORTED_INSTALLED" -> AR_READY
                "SUPPORTED_NOT_INSTALLED", "SUPPORTED_APK_TOO_OLD" -> AR_INSTALLABLE
                "UNSUPPORTED_DEVICE_NOT_CAPABLE" -> AR_UNSUPPORTED
                else -> AR_STATUS_UNKNOWN
            }
    }
}
