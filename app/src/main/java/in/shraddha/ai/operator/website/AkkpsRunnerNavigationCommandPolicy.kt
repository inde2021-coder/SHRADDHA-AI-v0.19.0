package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Resolves only explicit, unambiguous requests to known Runner module routes. */
internal object AkkpsRunnerNavigationCommandPolicy {
    private val openIntent = Regex(
        "(?:^|\\s)(?:open|show|go to|navigate to|खोलो|खोलें|दिखाओ|दिखाएं|जाओ|पर जाएं)(?:\\s|$)"
    )
    private val aliases = mapOf(
        AkkpsRunnerModule.HOME to listOf("home", "dashboard", "होम", "डैशबोर्ड"),
        AkkpsRunnerModule.FARMERS to listOf("farmers", "farmer", "kisan", "किसान", "किसान सूची", "किसानों"),
        AkkpsRunnerModule.PLOTS to listOf("plots", "plot", "land", "khet", "खेत", "प्लॉट", "खसरा"),
        AkkpsRunnerModule.AWD_REPORTS to listOf("awd reports", "awd report", "awd", "reports", "रिपोर्ट", "एडब्ल्यूडी")
    )

    fun resolve(raw: String): AkkpsRunnerModule? {
        if (raw.isBlank() || raw.length > 300) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (!openIntent.containsMatchIn(normalized)) return null
        val matches = aliases.filterValues { values ->
            values.any { alias ->
                Regex("(?:^|\\s)" + Regex.escape(alias) + "(?:$|\\s)").containsMatchIn(normalized)
            }
        }.keys
        return matches.singleOrNull()
    }

    fun route(module: AkkpsRunnerModule): String = module.basePath
}
