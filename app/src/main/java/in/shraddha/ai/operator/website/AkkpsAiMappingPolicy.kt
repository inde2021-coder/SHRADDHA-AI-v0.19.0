package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Fail-closed check that AI-mapped farmer values were literally present in the user's command. */
internal object AkkpsAiMappingPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun areValuesExplicitInCommand(command: String, assignments: List<Pair<String, String>>): Boolean {
        if (assignments.isEmpty()) return false
        val source = normalize(command)
        if (source.isBlank()) return false
        return assignments.all { (_, value) ->
            val candidate = normalize(value)
            candidate.isNotBlank() && source.contains(candidate)
        }
    }
}
