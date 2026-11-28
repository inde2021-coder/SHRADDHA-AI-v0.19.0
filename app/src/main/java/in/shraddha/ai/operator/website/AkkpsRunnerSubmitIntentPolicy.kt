package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Detects explicit submit intent only when a recognized command is at the end of the utterance. */
internal object AkkpsRunnerSubmitIntentPolicy {
    private val suffix = Regex(
        "(?:^|[\\s,;:])(?:submit(?:\\s+now)?|save(?:\\s+record)?|record\\s+save|send|जमा(?:\\s+करें|\\s+करो)?|" +
            "सबमिट(?:\\s+करें)?|भेजो|भेज\\s+दें|भेजें|सहेजें|सेव(?:\\s+करें)?)$"
    )

    fun stripSuffix(raw: String): String {
        if (!isExplicit(raw)) return raw
        return raw.replace(
            Regex("(?i)(?:^|[\\s,;:])(?:submit(?:\\s+now)?|save(?:\\s+record)?|record\\s+save|send|जमा(?:\\s+करें|\\s+करो)?|सबमिट(?:\\s+करें)?|भेजो|भेज\\s+दें|भेजें|सहेजें|सेव(?:\\s+करें)?)$"),
            ""
        ).trim()
    }

    fun isExplicit(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 4_000) return false
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return suffix.containsMatchIn(normalized)
    }
}
