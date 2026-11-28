package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

internal object AkkpsRunnerCreateCommandPolicy {
    private val createPrefixes = listOf("create", "new", "add", "register", "नया", "नई", "जोड़ो", "जोड़ें", "बनाएं", "बनाओ", "पंजीकरण")
    private val moduleAliases = mapOf(
        AkkpsRunnerModule.FARMERS to listOf("farmer", "farmers", "kisan", "किसान", "किसानों"),
        AkkpsRunnerModule.PLOTS to listOf("plot", "plots", "land", "khet", "खेत", "प्लॉट", "खसरा"),
        AkkpsRunnerModule.AWD_REPORTS to listOf("awd report", "awd reports", "awd", "report", "reports", "रिपोर्ट", "एडब्ल्यूडी")
    )

    fun resolve(raw: String): AkkpsRunnerModule? {
        if (raw.isBlank() || raw.length > 300 || raw.contains('\n')) return null
        val withoutSubmit = raw.replace(
            Regex("(?i)(?:^|[\\\\s,;:])(?:submit(?:\\\\s+now)?|save(?:\\\\s+record)?|जमा(?:\\\\s+करें|\\\\s+करो)?|सबमिट(?:\\\\s+करें)?|भेजो|भेजें)$"),
            ""
        ).trim()
        val normalized = Normalizer.normalize(withoutSubmit, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (Regex("(?:^|\\s)(?:delete|payment|भुगतान|हटाएं|मिटाएं)(?:\\s|$)").containsMatchIn(normalized)) return null

        val prefixCommand = createPrefixes.any { prefix ->
            normalized.startsWith("$prefix ") && normalized.removePrefix("$prefix ").isNotBlank()
        }
        val suffixCommand = Regex("(?:^|\\s)(?:बनाओ|बनाएं|जोड़ो|जोड़ें|पंजीकरण)$").containsMatchIn(normalized)
        if (!prefixCommand && !suffixCommand) return null

        val matches = moduleAliases.filterValues { aliases ->
            aliases.any { alias ->
                Regex("(?:^|\\s)" + Regex.escape(alias) + "(?:$|\\s)").containsMatchIn(normalized)
            }
        }.keys
        return matches.singleOrNull()
    }

    fun route(module: AkkpsRunnerModule): String? = when (module) {
        AkkpsRunnerModule.FARMERS -> "/runner/farmers/new"
        AkkpsRunnerModule.PLOTS -> "/runner/plots/new"
        AkkpsRunnerModule.AWD_REPORTS -> "/awd-reports/new"
        else -> null
    }
}
