package `in`.shraddha.ai.operator.website

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean
)

/** Deterministic parser: values are copied literally and never inferred. */
object AkkpsRunnerCommandParser {
    private val submitToken = Regex(
        "^(?:submit|सबमिट|जमा करें|जमा|भेजो|भेज दें)$",
        RegexOption.IGNORE_CASE
    )

    /** Submission intent must be a standalone command segment, never a substring of a value. */
    fun hasExplicitSubmitIntent(raw: String): Boolean =
        raw.split(';', '\\n').any { submitToken.matches(it.trim()) }

    fun parse(raw: String): AkkpsRunnerCommand {
        val assignments = mutableListOf<Pair<String, String>>()
        var submit = false
        raw.split(';', '\\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            if (submitToken.matches(part)) {
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
