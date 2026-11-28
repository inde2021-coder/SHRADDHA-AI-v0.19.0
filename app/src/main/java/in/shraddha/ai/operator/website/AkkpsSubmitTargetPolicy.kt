package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Rejects blank or duplicate field keys after Unicode normalization. */
internal object AkkpsSubmitTargetPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

    fun hasValidKeys(keys: List<String>): Boolean {
        val normalized = keys.map(::normalize)
        return normalized.isNotEmpty() && normalized.none { it.isBlank() } &&
            normalized.distinct().size == normalized.size
    }

    /** Save Draft is deliberately not a submit target; only explicit final-submit labels qualify. */
    val explicitSubmitLabels = setOf(
        "submit", "submit report", "submit plot", "submit registration",
        "submit farmer", "submit farmer registration", "submit awd", "submit awd report",
        "जमा करें", "जमा करो", "सबमिट", "सबमिट करें", "भेजें", "भेजो"
    )

    fun isExplicitSubmitLabel(label: String): Boolean = normalize(label) in explicitSubmitLabels
}
