package `in`.shraddha.ai.operator.website

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean
)

/** Deterministic parser: values are copied literally and never inferred. */
object AkkpsRunnerCommandParser {
    private val submitTokens = setOf(
        "submit", "सबमिट", "जमा करें", "जमा", "भेजो", "भेज दें",
        "कृपया रिकॉर्ड जमा करें", "कृपया फॉर्म जमा करें",
        "कृपया सबमिट करें", "रिकॉर्ड जमा करें", "फॉर्म जमा करें",
        "अब जमा करें", "अब सबमिट करें", "please submit", "kindly submit"
    )

    private fun normalizeCommand(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun isSubmitCommandSegment(segment: String): Boolean =
        normalizeCommand(segment) in submitTokens

    /** Submission intent must occupy a whole command segment, never appear inside a field value. */
    fun hasExplicitSubmitIntent(raw: String): Boolean =
        raw.split(';', '\n').any(::isSubmitCommandSegment)

    fun parse(raw: String): AkkpsRunnerCommand {
        val assignments = mutableListOf<Pair<String, String>>()
        var submit = false
        raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            if (isSubmitCommandSegment(part)) {
                submit = true
            } else {
                val splitAt = part.indexOf('=').takeIf { it >= 0 } ?: part.indexOf(':')
                if (splitAt > 0) {
                    val key = part.substring(0, splitAt).trim()
                    val value = part.substring(splitAt + 1).trim()
                    if (key.length in 1..70 && value.length in 1..500) assignments += key to value
                }
            }
        }
        return AkkpsRunnerCommand(assignments.distinctBy { it.first.lowercase() }, submit)
    }
}
