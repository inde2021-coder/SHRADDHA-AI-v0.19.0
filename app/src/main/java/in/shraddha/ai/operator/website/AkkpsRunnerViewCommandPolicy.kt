package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

internal object AkkpsRunnerViewCommandPolicy {
    private val prefix = Regex("^(?:view|show record|open record|open|देखो|देखें|रिकॉर्ड खोलो|रिकॉर्ड खोलें|खोलो|खोलें)\\s+(.+)$")

    fun parse(raw: String): String? {
        if (raw.isBlank() || raw.length > 300 || raw.contains('\n')) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val query = prefix.matchEntire(normalized)?.groupValues?.get(1)?.trim() ?: return null
        if (query.isBlank() || query.length > 100) return null
        if (Regex("(?:^|\\s)(?:farmers|plots|reports|submit|delete|payment|भुगतान|हटाएं|मिटाएं)(?:\\s|$)").containsMatchIn(query)) return null
        return query
    }
}
