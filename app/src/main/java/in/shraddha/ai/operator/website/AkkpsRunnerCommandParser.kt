package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

data class AkkpsRunnerCommand(
    val assignments: List<Pair<String, String>>,
    val submit: Boolean,
    val hasDuplicateKeys: Boolean = false
)

/** Parses explicit key/value instructions without silently choosing between conflicting values. */
object AkkpsRunnerCommandParser {
    private fun normalizedKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")

    private val submitCommands = setOf("submit", "सबमिट", "जमा", "जमा करें", "भेजें", "भेज दें")

    fun parse(raw: String): AkkpsRunnerCommand {
        val assignments = mutableListOf<Pair<String, String>>()
        var submit = false
        raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val normalizedPart = Normalizer.normalize(part, Normalizer.Form.NFKC).trim()
            if (normalizedKey(normalizedPart) in submitCommands) {
                submit = true
            } else {
                val splitAt = normalizedPart.indexOf('=').takeIf { it >= 0 } ?: normalizedPart.indexOf(':')
                if (splitAt > 0) {
                    val key = normalizedPart.substring(0, splitAt).trim()
                    val value = normalizedPart.substring(splitAt + 1).trim()
                    if (key.length in 1..70 && value.length in 1..500) assignments += key to value
                }
            }
        }
        val keys = assignments.map { normalizedKey(it.first) }
        return AkkpsRunnerCommand(assignments, submit, keys.size != keys.distinct().size)
    }
}
