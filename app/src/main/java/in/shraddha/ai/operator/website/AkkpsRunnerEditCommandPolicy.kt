package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

internal object AkkpsRunnerEditCommandPolicy {
    fun isExplicit(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 120 || raw.contains('\n')) return false
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return normalized in setOf(
            "edit", "edit record", "update", "update record",
            "संपादित करो", "संपादित करें", "बदलें", "रिकॉर्ड बदलें", "रिकॉर्ड संपादित करें"
        )
    }
}
