package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** AI may only map literal values that the user supplied in the current command. */
internal object AkkpsAiMappingPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun canSubmit(aiRequestedSubmit: Boolean, missingRequiredInformation: Int): Boolean =
        aiRequestedSubmit && missingRequiredInformation == 0

    fun areValuesExplicitInCommand(command: String, assignments: List<Pair<String, String>>): Boolean {
        if (assignments.isEmpty()) return false
        val source = normalize(command)
        if (source.isBlank()) return false
        return assignments.all { (_, value) ->
            val candidate = normalize(value)
            candidate.isNotBlank() && containsWholeValue(source, candidate)
        }
    }

    private fun containsWholeValue(source: String, candidate: String): Boolean {
        fun isWordCharacter(char: Char): Boolean {
            val type = Character.getType(char)
            return char.isLetterOrDigit() ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }

        var index = source.indexOf(candidate)
        while (index >= 0) {
            val end = index + candidate.length
            val leftBoundary = index == 0 || !isWordCharacter(source[index - 1])
            val rightBoundary = end == source.length || !isWordCharacter(source[end])
            if (leftBoundary && rightBoundary) return true
            index = source.indexOf(candidate, index + 1)
        }
        return false
    }
}
