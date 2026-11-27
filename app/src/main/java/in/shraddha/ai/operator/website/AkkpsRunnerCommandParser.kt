package `in`.shraddha.ai.operator.website

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean,
    val hasDuplicateKeys: Boolean = false
)

/** Small deterministic parser; it never guesses field values or invents missing data. */
object AkkpsRunnerCommandParser {
    fun parse(raw: String): AkkpsRunnerCommand {
        val assignments = mutableListOf<Pair<String, String>>()
        var submit = false
        raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            if (part.lowercase() in setOf("submit", "सबमिट", "जमा", "जमा करें", "भेजें", "भेज दें")) {
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
        val duplicateKeys = assignments.map { it.first.trim().lowercase() }
            .let { it.size != it.distinct().size }
        return AkkpsRunnerCommand(assignments, submit, duplicateKeys)
    }
}
