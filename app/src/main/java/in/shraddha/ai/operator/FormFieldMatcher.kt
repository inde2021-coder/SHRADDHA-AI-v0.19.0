package `in`.shraddha.ai.operator

import com.Lia.assistant.agent.core.ScreenObservation
import com.Lia.assistant.agent.core.UiElement

data class ExtractedFormValue(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float,
    val status: String,
    val sourceText: String = ""
)

data class FormFillSuggestion(
    val fieldKey: String,
    val fieldLabel: String,
    val value: String,
    val target: UiElement,
    val confidence: Float,
    val requiresReview: Boolean
)

/**
 * Matches extracted document values to visible editable controls.
 * It never types, clicks, submits, or persists data; callers must show a review
 * and obtain explicit approval before executing any suggestion.
 */
object FormFieldMatcher {
    private val sensitiveKeys = setOf("aadhaarNumber", "panNumber", "phoneNumber", "address", "bankAccount", "consentSigner")

    private val aliases = mapOf(
        "farmerName" to listOf("farmer name", "name of farmer", "किसान का नाम", "कृषक का नाम"),
        "fatherName" to listOf("father's name", "father name", "पिता का नाम"),
        "motherName" to listOf("mother's name", "mother name", "माता का नाम"),
        "phoneNumber" to listOf("phone number", "mobile number", "मोबाइल नंबर", "फोन नंबर"),
        "gender" to listOf("farmer gender", "gender", "लिंग"),
        "identityType" to listOf("identity type", "पहचान प्रकार"),
        "aadhaarNumber" to listOf("aadhaar number", "आधार नंबर"),
        "address" to listOf("full address", "address", "पूरा पता"),
        "surveyNumber" to listOf("survey number", "सर्वे नंबर"),
        "plotNumber" to listOf("plot number", "प्लॉट नंबर"),
        "villageBlock" to listOf("village / block number", "village", "गांव", "ब्लॉक"),
        "continuousFlooding" to listOf("baseline practice continuous flooding", "continuous flooding"),
        "previousIrrigationMethod" to listOf("previous irrigation method", "irrigation method"),
        "preSeasonWaterCondition" to listOf("pre-season water condition", "water condition"),
        "baselineEmissionFactorSource" to listOf("baseline emission factor source", "emission factor source"),
        "typicalWaterDepthCm" to listOf("typical water depth", "water depth"),
        "irrigationsPerSeason" to listOf("irrigations / season", "irrigations per season"),
        "cropDurationDays" to listOf("crop duration", "crop duration (days)"),
        "riceVariety" to listOf("rice variety", "variety"),
        "season" to listOf("season"),
        "historicalYieldYear" to listOf("year"),
        "historicalYieldQtlPerAcre" to listOf("yield (qtl/acre)", "yield qtl/acre"),
        "consentSignedDate" to listOf("signed date", "consent date"),
        "consentWitness" to listOf("witness", "witness name")
    )

    fun suggest(values: List<ExtractedFormValue>, screen: ScreenObservation): List<FormFillSuggestion> {
        val editable = screen.elements.filter { it.isEditable }
        val labels = screen.elements.filter { !it.isEditable && (it.text.isNotBlank() || it.contentDescription.isNotBlank()) }
        return values.mapNotNull { item ->
            val cleanValue = item.value.trim()
            if (cleanValue.isEmpty() || item.status != "ready" || item.confidence < 0.90f) return@mapNotNull null
            val terms = (aliases[item.key].orEmpty() + item.label.lowercase()).map { it.lowercase().trim() }.filter { it.isNotEmpty() }
            val label = labels.firstOrNull { element ->
                val text = (element.text + " " + element.contentDescription).lowercase()
                terms.any { term -> text.contains(term) }
            } ?: return@mapNotNull null
            val target = editable
                .filter { it.bounds.top >= label.bounds.top && it.bounds.top - label.bounds.bottom in 0..220 }
                .minByOrNull { kotlin.math.abs(it.bounds.centerY() - label.bounds.centerY()) }
                ?: return@mapNotNull null
            FormFillSuggestion(
                fieldKey = item.key,
                fieldLabel = item.label,
                value = cleanValue,
                target = target,
                confidence = item.confidence,
                requiresReview = item.key in sensitiveKeys || item.confidence < 0.98f
            )
        }
    }
}
