package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** AI may only map literal values that the user supplied in the current command. */
internal object AkkpsAiMappingPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun canSubmit(aiRequestedSubmit: Boolean, missingRequiredInformation: Int): Boolean =
        aiRequestedSubmit && missingRequiredInformation == 0

    fun areValuesExplicitInCommand(command: String, assignments: List<Pair<String, String>>): Boolean {
        if (assignments.isEmpty()) return false
        val source = normalize(command)
        if (source.isBlank()) return false
        if (assignments.size > 100) return false
        return assignments.all { (_, value) ->
            val candidate = normalize(value)
            candidate.isNotBlank() && Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(candidate) + "(?![\\p{L}\\p{N}])"
            ).containsMatchIn(source)
        }
    }
}
