package `in`.shraddha.ai.operator.website

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean,
    val issues: List<String> = emptyList()
)

/**
 * Deterministic Hindi/English/Hinglish parser.
 * It never guesses field values, silently overwrites duplicate keys or infers missing data.
 */
object AkkpsRunnerCommandParser {
    private val submitAliases = setOf(
        "submit", "save", "send", "जमा", "जमा करें", "जमा करो", "सबमिट", "सबमिट करें",
        "भेजो", "भेज दें", "भेजें", "सहेजें", "सेव", "सेव करें"
    )

    fun parse(raw: String): AkkpsRunnerCommand {
        if (raw.isBlank()) return AkkpsRunnerCommand(emptyList(), false, listOf("कमांड खाली है।"))
        if (raw.length > MAX_COMMAND_CHARS) {
            return AkkpsRunnerCommand(emptyList(), false, listOf("कमांड बहुत लंबी है; अधिकतम $MAX_COMMAND_CHARS अक्षर।"))
        }

        val values = linkedMapOf<String, Pair<String, String>>()
        val issues = mutableListOf<String>()
        var submit = false

        raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val normalized = part.lowercase().replace(Regex("\\s+"), " ").trim()
            if (normalized in submitAliases) {
                submit = true
                return@forEach
            }
            val splitAt = part.indexOf('=').takeIf { it >= 0 } ?: part.indexOf(':')
            if (splitAt <= 0) {
                issues += "यह हिस्सा समझ नहीं आया: ${part.take(60)}"
                return@forEach
            }
            val suppliedKey = part.substring(0, splitAt).trim()
            val key = AkkpsFieldAliasCatalog.canonicalKey(suppliedKey)
            val value = part.substring(splitAt + 1).trim()
            if (key.length !in 1..MAX_KEY_CHARS || value.isBlank() || value.length > MAX_VALUE_CHARS) {
                issues += "फील्ड नाम या मान की लंबाई अमान्य है।"
                return@forEach
            }
            val identity = key.lowercase().replace(Regex("\\s+"), " ")
            val previous = values[identity]
            if (previous != null) {
                if (previous.second != value) issues += "एक ही फील्ड के लिए अलग-अलग मान दिए गए हैं: $key"
                else issues += "फील्ड दो बार दी गई है: $key"
                values.remove(identity)
            } else {
                values[identity] = key to value
            }
        }

        return AkkpsRunnerCommand(
            assignments = if (issues.isEmpty()) values.values.toList() else emptyList(),
            submit = submit,
            issues = issues.distinct().take(20)
        )
    }

    private const val MAX_COMMAND_CHARS = 4_000
    private const val MAX_KEY_CHARS = 70
    private const val MAX_VALUE_CHARS = 500
}
