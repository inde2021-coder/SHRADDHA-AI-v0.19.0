package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Validates exact mapped field keys before selecting an AKKPS submit target. */
internal object AkkpsSubmitTargetPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")

    fun hasValidKeys(keys: List<String>): Boolean {
        val normalized = keys.map(::normalize)
        return normalized.isNotEmpty() && normalized.none { it.isBlank() } &&
            normalized.distinct().size == normalized.size
    }
}
