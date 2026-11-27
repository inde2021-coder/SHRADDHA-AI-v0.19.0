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
            candidate.isNotBlank() && isWholeSuppliedValue(source, candidate)
        }
    }

    private fun isWholeSuppliedValue(source: String, candidate: String): Boolean {
        if (AkkpsFieldAliasCatalog.isKnownAliasToken(candidate)) return false
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}])" + Regex.escape(candidate) + "(?![\\p{L}\\p{N}])"
        )
        val connectors = setOf("is", "are", "called", "named", "name", "नाम", "है", "हैं")
        return pattern.findAll(source).any { match ->
            val before = source.substring(0, match.range.first)
            val after = source.substring(match.range.last + 1)
            val previousWord = if (before.lastOrNull()?.isWhitespace() == true)
                before.trimEnd().takeLastWhile { it.isLetterOrDigit() } else ""
            val nextWord = if (after.firstOrNull()?.isWhitespace() == true)
                after.trimStart().takeWhile { it.isLetterOrDigit() } else ""
            val previousAllowed = previousWord.isBlank() || previousWord in connectors ||
                AkkpsFieldAliasCatalog.isKnownAliasToken(previousWord)
            val nextAllowed = nextWord.isBlank() || nextWord in connectors ||
                AkkpsFieldAliasCatalog.isKnownAliasToken(nextWord)
            previousAllowed && nextAllowed
        }
    }
}
