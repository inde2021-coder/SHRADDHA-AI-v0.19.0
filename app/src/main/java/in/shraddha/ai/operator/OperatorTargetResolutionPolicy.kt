package `in`.shraddha.ai.operator

import java.text.Normalizer
import java.util.Locale

/**
 * Resolves UI labels only when the screen snapshot contains one defensible target.
 * Exact labels win; partial matching is allowed only when it identifies one candidate.
 */
internal object OperatorTargetResolutionPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    fun <T> selectUnique(
        target: String,
        candidates: List<T>,
        labels: (T) -> List<String>
    ): T? {
        val wanted = normalize(target)
        if (wanted.isBlank()) return null
        val visible = candidates.mapNotNull { candidate ->
            val normalizedLabels = labels(candidate).map(::normalize).filter { it.isNotBlank() }
            if (normalizedLabels.isEmpty()) null else candidate to normalizedLabels
        }
        val exact = visible.filter { (_, values) -> values.any { it == wanted } }
        if (exact.size == 1) return exact.single().first
        if (exact.size > 1) return null
        val partial = visible.filter { (_, values) -> values.any { it.contains(wanted) || wanted.contains(it) } }
        return partial.singleOrNull()?.first
    }
}
