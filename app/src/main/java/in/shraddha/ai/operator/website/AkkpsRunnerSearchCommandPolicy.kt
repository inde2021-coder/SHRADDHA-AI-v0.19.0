package `in`.shraddha.ai.operator.website

import java.text.Normalizer

internal object AkkpsRunnerSearchCommandPolicy {
    private val prefix = Regex("^(?:search(?:\\s+for)?|find|खोजो|खोजें|ढूंढो|ढूँढो|ढूंढें|ढूँढें)(?:\\s*:\\s*|\\s+)(.+)$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): String? {
        if (raw.isBlank() || raw.length > 300 || raw.contains('\n')) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ")
        val match = prefix.matchEntire(normalized) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank() || query.length > 120) return null
        if (Regex("(?:^|\\s)(?:submit|delete|payment|भुगतान|हटाएं|मिटाएं)(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(query)) return null
        return query
    }
}
