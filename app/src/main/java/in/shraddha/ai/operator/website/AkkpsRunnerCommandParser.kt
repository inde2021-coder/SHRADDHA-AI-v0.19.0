package `in`.shraddha.ai.operator.website

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean
)

/** Small deterministic parser; it never guesses field values or invents missing data. */
object AkkpsRunnerCommandParser {
    /** Submission intent must be a standalone command/token, not a substring such as "resubmit". */
    fun hasExplicitSubmitIntent(raw: String): Boolean =
        Regex("(?:^|[\\s;])(?:submit|सबमिट|जमा करें|जमा|भेजो|भेज दें)(?:$|[\\s;])", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw.trim())

    fun parse(raw: String): AkkpsRunnerCommand {
        val assignments = mutableListOf<Pair<String, String>>()
        var submit = false
        raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            if (part.equals("submit", ignoreCase = true)) {
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
