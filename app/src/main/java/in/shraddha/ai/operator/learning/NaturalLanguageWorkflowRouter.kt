package `in`.shraddha.ai.operator.learning

import java.text.Normalizer
import java.util.Locale

/** Routes a command to a workflow the user has already taught; never invents unseen app actions. */
object NaturalLanguageWorkflowRouter {
    data class Route(val profile: AppTrainingProfile, val workflowName: String, val confidence: Float, val values: Map<String, String>, val note: String)
    data class Result(val route: Route? = null, val message: String)

    private val stopWords = setOf("please", "karo", "kar", "do", "folder", "from", "in", "inside", "se", "ko", "mein", "में", "से", "को", "करो", "कर", "दो", "फोल्डर", "downloads", "pictures", "picture", "documents", "document", "dcim")
    private val synonymGroups = listOf(
        setOf("add", "create", "new", "जोड़", "बनाओ", "बनाना", "नया"),
        setOf("customer", "client", "contact", "ग्राहक", "कस्टमर"),
        setOf("upload", "attach", "select", "अपलोड", "लगाओ", "जोड़ो"),
        setOf("file", "फ़ाइल", "फाइल", "document", "दस्तावेज़", "दस्तावेज", "photo", "image", "फ़ोटो", "तस्वीर"),
        setOf("invoice", "bill", "receipt", "इनवॉइस", "इनवॉयस", "चालान", "बिल"),
        setOf("search", "find", "lookup", "खोज", "ढूंढ", "सर्च"),
        setOf("save", "submit", "done", "सेव", "जमा", "सबमिट", "सहेज"),
        setOf("delete", "remove", "erase", "trash", "हटाओ", "हटाना", "मिटाओ", "डिलीट"),
        setOf("update", "edit", "change", "modify", "अपडेट", "बदलना", "बदलो", "संशोधन"),
        setOf("send", "share", "forward", "message", "भेजो", "भेजना", "सेंड", "साझा"),
        setOf("download", "fetch", "export", "डाउनलोड", "उतारो", "निकालो"),
        setOf("report", "रिपोर्ट", "विवरण", "प्रतिवेदन"),
        setOf("upload", "attach", "select", "अपलोड", "लगाओ", "संलग्न")
    )

    fun route(command: String, profile: AppTrainingProfile): Result {
        val normalized = normalize(command)
        if (normalized.isBlank()) return Result(message = "कृपया बताएं कि कौन-सा काम करना है।")
        if (containsHighImpactAction(normalized)) {
            return Result(message = "यह Delete/Payment/Publish जैसा high-impact काम है। पहले अलग से स्पष्ट पुष्टि लें; सीखे हुए Workflow को अपने-आप चलाया नहीं गया।")
        }
        if (containsMultipleActionIntents(normalized)) {
            return Result(message = "एक निर्देश में कई अलग काम हैं। कृपया उन्हें क्रम से अलग-अलग बताएं; एक Workflow को गलत तरीके से नहीं चुना गया।")
        }
        val ranked = profile.workflows.keys.map { name -> name to score(normalized, normalize(name)) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return Result(message = "इस ऐप के लिए कोई सिखाया हुआ Workflow नहीं है।")
        if (best.second < .22f) return Result(message = "इस निर्देश से मिलता हुआ सीखा Workflow नहीं मिला। पहले Training Lab में यह काम सिखाएँ।")
        if (ranked.size > 1 && best.second - ranked[1].second < .08f) return Result(message = "एक से अधिक Workflow मिल रहे हैं। कृपया Training Lab में Workflow का स्पष्ट नाम चुनें।")
        val file = Regex("(?i)[\\p{L}0-9_-][\\p{L}0-9_.-]*\\.(?:jpg|jpeg|png|webp|pdf|docx?|xlsx?|txt)").findAll(command).lastOrNull()?.value?.trim()
        val rawFolder = Regex("(?i)(?:from|in|inside|से|में)\\s+(downloads?|pictures?|documents?|dcim|डाउनलोड|तस्वीरें|दस्तावेज़)").find(command)?.groupValues?.getOrNull(1).orEmpty()
        val folder = when (rawFolder.lowercase()) { "downloads", "download", "डाउनलोड" -> "Download"; "pictures", "picture", "तस्वीरें" -> "Pictures"; "documents", "document", "दस्तावेज़" -> "Documents"; "dcim" -> "DCIM"; else -> rawFolder }
        val values = linkedMapOf<String, String>()
        if (!file.isNullOrBlank()) {
            profile.workflows[best.first].orEmpty().filter { it.action.equals("SELECT_FILE", true) }.forEach { values[it.inputTemplate] = file }
            if (folder.isNotBlank()) values["__file_folder"] = folder
        }
        return Result(Route(profile, best.first, best.second, values, "सीखे हुए Workflow से निर्देश मिलाया गया; कोई नया ऐप व्यवहार अनुमान से नहीं बनाया गया।"), "Workflow चुना गया: ${best.first}")
    }

    private fun containsHighImpactAction(value: String): Boolean {
        val tokens = value.split(' ').filter { it.isNotBlank() }.toSet()
        val highImpact = setOf(
            "delete", "remove", "erase", "trash", "pay", "payment", "purchase", "buy", "publish", "post",
            "हटाओ", "हटाना", "हटाएं", "हटाएँ", "हटाइए", "हटा", "मिटाओ", "मिटाना", "मिटा", "डिलीट",
            "भुगतान", "पेमेंट", "खरीदो", "प्रकाशित", "पब्लिश", "पोस्ट",
            "hata", "hatao", "hatana", "mita", "mitao", "mitana", "bhugtan", "bhugtaan",
            "paisa", "paise"
        )
        if (tokens.any { it in highImpact }) return true
        val phrases = listOf(
            "हटा दो", "हटा दें", "हटा देना", "मिटा दो", "मिटा दें", "मिटा देना",
            "hata do", "hata dena", "mita do", "mita dena",
            "पैसे भेजो", "पैसा भेजो", "भुगतान करो", "पेमेंट करो",
            "paise bhejo", "paisa bhejo", "bhugtan karo", "payment karo"
        )
        return phrases.any { value.contains(it) }
    }
    private fun containsMultipleActionIntents(value: String): Boolean {
        val groups = listOf(
            setOf("create", "add", "बनाओ", "बनाना", "जोड़ो", "banao", "jodo"),
            setOf("search", "find", "खोजो", "ढूंढो", "सर्च", "khojo", "dhundo"),
            setOf("update", "edit", "change", "अपडेट", "बदलो", "update", "badlo"),
            setOf("send", "share", "forward", "भेजो", "भेजना", "सेंड", "bhejo", "bhejna"),
            setOf("save", "submit", "सेव", "जमा", "सबमिट", "save", "jama"),
            setOf("download", "export", "डाउनलोड", "download"),
            setOf("upload", "attach", "अपलोड", "upload"),
            setOf("delete", "remove", "हटाओ", "हटाना", "मिटाओ", "डिलीट", "hatao", "hata", "mitao"),
            setOf("pay", "payment", "भुगतान", "पेमेंट", "paisa", "paise", "bhugtan"),
            setOf("publish", "प्रकाशित", "पब्लिश", "पोस्ट", "publish", "post")
        )
        return groups.count { group -> value.split(' ').any { it in group } } > 1
    }
    private val canonicalAliases = linkedMapOf(
        "ग्राहक" to "customer", "कस्टमर" to "customer",
        "हटाओ" to "delete", "हटाना" to "delete", "मिटाओ" to "delete", "डिलीट" to "delete",
        "अपडेट" to "update", "बदलो" to "update", "बदलना" to "update",
        "भेजो" to "send", "भेजना" to "send", "सेंड" to "send",
        "डाउनलोड" to "download", "उतारो" to "download",
        "रिपोर्ट" to "report", "इनवॉइस" to "invoice", "इनवॉयस" to "invoice",
        "सेव" to "save", "सहेज" to "save", "जमा" to "submit",
        "अपलोड" to "upload", "दस्तावेज़" to "document", "दस्तावेज" to "document"
    )

    private fun normalize(value: String): String {
        val tokens = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("(?i)[\\p{L}0-9_-][\\p{L}0-9_.-]*\\.(?:jpg|jpeg|png|webp|pdf|docx?|xlsx?|txt)"), " ")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
            .trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.joinToString(" ") { canonicalAliases[it] ?: it }
    }
    private fun score(a: String, b: String): Float {
        val at = a.split(" ").filter { it.length > 1 && it !in stopWords }.toSet(); val bt = b.split(" ").filter { it.length > 1 && it !in stopWords }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return 0f
        var overlap = at.intersect(bt).size.toFloat()
        for (group in synonymGroups) if (at.any { it in group } && bt.any { it in group }) overlap += .65f
        return (overlap / maxOf(at.size, bt.size)).coerceAtMost(1f)
    }
}
