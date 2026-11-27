package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** AI may only map literal values that the user supplied in the current command. */
internal object AkkpsAiMappingPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun hasValidMissingItems(items: List<String>): Boolean =
        items.size <= 30 && items.all { it.isNotBlank() && it.length <= 120 }

    fun canSubmit(aiRequestedSubmit: Boolean, missingRequiredInformation: Int): Boolean =
        aiRequestedSubmit && missingRequiredInformation == 0

    fun areValuesExplicitInCommand(command: String, assignments: List<Pair<String, String>>): Boolean {
        if (assignments.isEmpty() || assignments.size > 100) return false
        val source = normalize(command)
        if (source.isBlank()) return false
        return assignments.all { (key, value) ->
            if (key.isBlank() || key.length > 160 || value.length > 500) return@all false
            val candidate = normalize(value)
            if (candidate.isBlank()) return@all false
            val literal = Regex(
                "(?<![\\\\p{L}\\\\p{N}])" + Regex.escape(candidate) + "(?![\\\\p{L}\\\\p{N}])"
            )
            literal.containsMatchIn(source)
        }
    }
}
