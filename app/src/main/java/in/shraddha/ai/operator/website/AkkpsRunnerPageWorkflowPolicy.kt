package `in`.shraddha.ai.operator.website

import java.util.Locale

data class AkkpsAutocompleteRequest(
    val fieldLabel: String,
    val query: String
)

/**
 * Parses only bounded, user-spoken page actions for the AKKPS Runner.
 * It never creates farmer/report values or chooses a suggestion not named by the user.
 */
object AkkpsRunnerPageWorkflowPolicy {
    private fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private val repeatedSections = listOf(
        Triple("drying event dates", Regex("(drying event|drying date|सूखने की तारीख|सूखने की घटना)"), "drying event"),
        Triple("re-flooding dates", Regex("(re[- ]?flooding|reflooding|पुनः बाढ़|दोबारा पानी भरने)"), "re-flooding"),
        Triple("fertilizer application dates", Regex("(?<!other )(?:fertilizer|fertiliser)|(?<!अन्य )(?:खाद|उर्वरक)"), "fertilizer"),
        Triple("other fertilizer rows", Regex("(other fertilizer|other fertiliser|अन्य खाद|अन्य उर्वरक)"), "other fertilizer"),
        Triple("field visits", Regex("(field visit|audit visit|खेत का दौरा|खेत के दौरे)"), "field visit"),
        Triple("historical yield rows", Regex("(historical yield|past yield|पुरानी उपज|ऐतिहासिक उपज)"), "historical yield"),
        Triple("water level at each visit", Regex("(water level|water reading|जल स्तर|पानी की रीडिंग)"), "water level")
    )

    fun repeatedEntrySection(command: String): String? {
        val value = normalize(command)
        if (!(value.startsWith("add ") || value.startsWith("add another ") || value.startsWith("new ") || value.startsWith("जोड़ें") || value.startsWith("जोड़ें") || value.startsWith("जोड़ो") || value.startsWith("नई प्रविष्टि") || value.startsWith("एक और"))) return null
        val matches = repeatedSections.filter { it.second.containsMatchIn(value) }
        if (matches.size != 1) return null
        return matches.single().first
    }

    fun autocompleteRequest(command: String): AkkpsAutocompleteRequest? {
        val value = command.trim()
        val patterns = listOf(
            Regex("(?i)^(?:select|choose|pick|चुनें|चुनो)\\s+(village|गाँव|गांव)\\s+(.+)$") to "Village",
            Regex("(?i)^(?:select|choose|pick|चुनें|चुनो)\\s+(rice variety|variety|धान की किस्म|चावल की किस्म)\\s+(.+)$") to "Rice Variety",
            Regex("(?i)^(?:select|choose|pick|चुनें|चुनो)\\s+(linked farmer|farmer|किसान)\\s+(.+)$") to "Linked Farmer",
            Regex("(?i)^(?:select|choose|pick|चुनें|चुनो)\\s+(linked plot|plot|खेत|प्लॉट)\\s+(.+)$") to "Linked Plot"
        )
        val match = patterns.mapNotNull { (pattern, label) ->
            pattern.matchEntire(value)?.let { label to it.groupValues[2].trim().trim('"', '\'') }
        }.singleOrNull() ?: return null
        if (match.second.isBlank() || match.second.length > 160) return null
        return AkkpsAutocompleteRequest(match.first, match.second)
    }

    fun documentFieldLabel(command: String): String? {
        val value = normalize(command)
        val patterns = listOf(
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:field photo|खेत की फोटो)$") to "Field Photos",
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:farmer photo|किसान की फोटो)$") to "Farmer Photo",
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:aadhaar|identity document|पहचान दस्तावेज़|आधार दस्तावेज़)$") to "Identity Document",
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:land deed|land document|भूमि दस्तावेज़)$") to "Land Deed",
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:agreement|सहमति पत्र|समझौता)$") to "Agreement",
            Regex("^(?:attach|add|upload|लगाओ|संलग्न करें|जोड़ें)\\s+(?:signature|हस्ताक्षर)$") to "Signature"
        )
        return patterns.singleOrNull { it.first.matches(value) }?.second
    }
}
