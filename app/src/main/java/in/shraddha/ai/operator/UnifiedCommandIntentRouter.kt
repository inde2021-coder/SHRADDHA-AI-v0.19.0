package `in`.shraddha.ai.operator

/**
 * First-stage language-independent intent extraction shared by voice, chat and
 * paired-device entry points. This class classifies a request only; it never
 * executes an action, invents field values, or treats classification as consent.
 */
enum class UnifiedCommandDomain {
    AKKPS, PHONE, PC, FILES, BROWSER, WHATSAPP, EMAIL, SOCIAL, REMINDER, UNKNOWN
}

enum class UnifiedCommandAction {
    OPEN, SEARCH, READ, CREATE, UPDATE, SAVE, SUBMIT, SEND, SHARE, LIKE,
    COMMENT, FOLLOW, DOWNLOAD, UPLOAD, CALL, REMIND, DELETE, PAY, PUBLISH, UNKNOWN
}

data class UnifiedCommandIntent(
    val domain: UnifiedCommandDomain,
    val action: UnifiedCommandAction,
    val confidence: Float,
    val needsClarification: Boolean,
    val requiresHighImpactApproval: Boolean,
    val originalCommand: String
)

/**
 * Deterministic Hindi/English/Hinglish classifier. Unknown or incomplete
 * commands remain UNKNOWN and must be clarified before any adapter is called.
 */
object UnifiedCommandIntentRouter {
    private val domainTerms = linkedMapOf(
        UnifiedCommandDomain.AKKPS to listOf("akkps", "farmer", "किसान", "खेत", "plot", "awd"),
        UnifiedCommandDomain.WHATSAPP to listOf("whatsapp", "व्हाट्सएप"),
        UnifiedCommandDomain.EMAIL to listOf("email", "e-mail", "mail", "ईमेल"),
        UnifiedCommandDomain.PHONE to listOf("phone", "फोन", "call", "कॉल", "sms", "सms", "संदेश"),
        UnifiedCommandDomain.PC to listOf("pc", "computer", "laptop", "windows", "कंप्यूटर", "लैपटॉप"),
        UnifiedCommandDomain.FILES to listOf("file", "folder", "document", "फ़ाइल", "फाइल", "फोल्डर", "दस्तावेज"),
        UnifiedCommandDomain.BROWSER to listOf("website", "browser", "webpage", "वेबसाइट", "ब्राउज़र", "वेबपेज"),
        UnifiedCommandDomain.SOCIAL to listOf("instagram", "facebook", "youtube", "telegram", "social", "इंस्टाग्राम", "फेसबुक"),
        UnifiedCommandDomain.REMINDER to listOf("reminder", "remind", "याद दिलाना", "रिमाइंडर", "अलार्म")
    )

    private val actionTerms = linkedMapOf(
        UnifiedCommandAction.DELETE to listOf("delete", "remove", "erase", "डिलीट", "हटाओ", "मिटाओ"),
        UnifiedCommandAction.PAY to listOf("pay", "payment", "भुगतान", "पेमेंट", "पैसे भेज"),
        UnifiedCommandAction.PUBLISH to listOf("publish", "post publicly", "प्रकाशित", "पब्लिश"),
        UnifiedCommandAction.SUBMIT to listOf("submit", "जमा करो", "जमा करें", "सबमिट"),
        UnifiedCommandAction.SEND to listOf("send", "message", "भेजो", "भेजना", "मैसेज"),
        UnifiedCommandAction.CALL to listOf("call", "dial", "कॉल करो", "फोन लगाओ"),
        UnifiedCommandAction.DOWNLOAD to listOf("download", "export", "डाउनलोड", "निकालो"),
        UnifiedCommandAction.UPLOAD to listOf("upload", "attach", "अपलोड", "लगाओ"),
        UnifiedCommandAction.SEARCH to listOf("search", "find", "lookup", "खोजो", "ढूंढो", "सर्च"),
        UnifiedCommandAction.READ to listOf("read", "inspect", "देखो", "पढ़ो", "जांचो"),
        UnifiedCommandAction.CREATE to listOf("create", "add", "new", "बनाओ", "जोड़ो", "नया"),
        UnifiedCommandAction.UPDATE to listOf("update", "edit", "change", "बदलो", "अपडेट", "सुधारो"),
        UnifiedCommandAction.SAVE to listOf("save", "store", "सेव", "सहेजो"),
        UnifiedCommandAction.SHARE to listOf("share", "forward", "शेयर", "साझा"),
        UnifiedCommandAction.LIKE to listOf("like", "लाइक"),
        UnifiedCommandAction.COMMENT to listOf("comment", "reply", "कमेंट", "जवाब"),
        UnifiedCommandAction.FOLLOW to listOf("follow", "subscribe", "फॉलो", "सब्सक्राइब"),
        UnifiedCommandAction.REMIND to listOf("remind", "reminder", "याद दिलाना", "रिमाइंडर"),
        UnifiedCommandAction.OPEN to listOf("open", "launch", "खोलो", "खोलना", "चालू करो")
    )

    fun classify(command: String): UnifiedCommandIntent {
        val raw = command.trim()
        if (raw.isEmpty() || raw.length > 4000) return unknown(raw)
        val normalized = raw.lowercase().replace(Regex("\\s+"), " ")
        val domain = domainTerms.entries.firstOrNull { (_, terms) ->
            terms.any { normalized.contains(it) }
        }?.key ?: UnifiedCommandDomain.UNKNOWN
        val action = actionTerms.entries.firstOrNull { (_, terms) ->
            terms.any { normalized.contains(it) }
        }?.key ?: UnifiedCommandAction.UNKNOWN
        val missingPart = domain == UnifiedCommandDomain.UNKNOWN || action == UnifiedCommandAction.UNKNOWN
        val confidence = when {
            domain != UnifiedCommandDomain.UNKNOWN && action != UnifiedCommandAction.UNKNOWN -> 0.90f
            domain != UnifiedCommandDomain.UNKNOWN || action != UnifiedCommandAction.UNKNOWN -> 0.55f
            else -> 0.0f
        }
        val highImpact = action in setOf(
            UnifiedCommandAction.DELETE,
            UnifiedCommandAction.PAY,
            UnifiedCommandAction.PUBLISH
        )
        return UnifiedCommandIntent(
            domain = domain,
            action = action,
            confidence = confidence,
            needsClarification = missingPart,
            requiresHighImpactApproval = highImpact,
            originalCommand = raw
        )
    }

    private fun unknown(raw: String) = UnifiedCommandIntent(
        domain = UnifiedCommandDomain.UNKNOWN,
        action = UnifiedCommandAction.UNKNOWN,
        confidence = 0.0f,
        needsClarification = true,
        requiresHighImpactApproval = false,
        originalCommand = raw
    )
}
