package `in`.shraddha.ai.operator.website

/**
 * Conservative local-only OCR field extractor for farmer cards.
 * It accepts only explicit label/value pairs; it never infers IDs or personal details.
 */
object AkkpsFarmerCardOcrParser {
    data class Result(val assignments: Map<String, String>, val missing: List<String>) {
        val readyForUserReview: Boolean get() = assignments.isNotEmpty()
        fun toRunnerCommand(): String = assignments.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private val labels = linkedMapOf(
        "name" to listOf("farmer name", "name", "किसान का नाम", "कृषक का नाम", "नाम"),
        "farmer_id" to listOf("farmer id", "farmer code", "registration no", "registration number", "किसान आईडी", "पंजीयन क्रमांक", "पंजीयन संख्या"),
        "village" to listOf("village", "gram", "ग्राम", "गांव", "गाँव"),
        "block" to listOf("block", "विकासखंड", "तहसील"),
        "district" to listOf("district", "जिला"),
        "state" to listOf("state", "राज्य")
    )

    private val required = listOf("name", "farmer_id", "village", "district")

    fun parse(ocrText: String): Result {
        val found = linkedMapOf<String, String>()
        for (raw in ocrText.lineSequence().take(120)) {
            val line = raw.replace(Regex("\\s+"), " ").trim().take(300)
            if (line.isBlank()) continue
            for ((key, aliases) in labels) {
                if (key in found) continue
                val alias = aliases.firstOrNull { label ->
                    line.equals(label, ignoreCase = true) ||
                        line.startsWith("$label:", ignoreCase = true) ||
                        line.startsWith("$label ", ignoreCase = true) ||
                        line.startsWith("$label-", ignoreCase = true)
                } ?: continue
                val value = line.substring(alias.length).trim().trimStart(':', '-', '—', '|').trim()
                    .replace(Regex("[;=\\r\\n\\u0000-\\u001F]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (value.length in 2..100 && !value.contains(Regex("(?i)aadhaar|aadhar|आधार|otp|पासवर्ड|password"))) {
                    found[key] = value
                }
            }
        }
        return Result(found, required.filterNot { found[it].orEmpty().isNotBlank() })
    }
}
