package `in`.shraddha.ai.operator

import com.Lia.assistant.agent.core.CapturedScreen
import com.Lia.assistant.agent.core.DriverResult
import com.Lia.assistant.agent.core.ScreenDriver
import com.Lia.assistant.agent.core.ScreenObservation

data class FormFieldFillResult(
    val fieldKey: String,
    val success: Boolean,
    val message: String
)

/**
 * Executes only fields explicitly approved by the user. Every write is bound
 * to a fresh accessibility snapshot and verified from a second snapshot.
 * This executor never presses Submit, Save, Agree, or any other final action.
 */
class FormAutofillExecutor(private val driver: ScreenDriver) {
    suspend fun applyApproved(
        extractedValues: List<ExtractedFormValue>,
        approvedFieldKeys: Set<String>
    ): List<FormFieldFillResult> {
        if (approvedFieldKeys.isEmpty()) return emptyList()
        val results = mutableListOf<FormFieldFillResult>()
        val approvedValues = extractedValues.filter { it.key in approvedFieldKeys }
        for (value in approvedValues) {
            val captured = driver.capture()
            if (captured == null) {
                results += FormFieldFillResult(value.key, false, "Target app screen is unavailable")
                break
            }
            val observation = captured.toObservation()
            val suggestion = FormFieldMatcher.suggest(listOf(value), observation).firstOrNull()
            if (suggestion == null) {
                results += FormFieldFillResult(value.key, false, "Matching field is not visible or confidence is too low")
                continue
            }
            val action = driver.setText(suggestion.target, suggestion.value)
            if (action != DriverResult.OK) {
                results += FormFieldFillResult(value.key, false, "The selected field could not be filled")
                continue
            }
            val verification = driver.capture()
            val verified = verification?.rawElements?.any { element ->
                element.isEditable && element.bounds == suggestion.target.bounds &&
                    element.text.trim() == suggestion.value
            } == true
            results += FormFieldFillResult(
                value.key,
                verified,
                if (verified) "Filled and verified" else "Could not verify the entered value"
            )
        }
        return results
    }

    private fun CapturedScreen.toObservation() = ScreenObservation(
        observationId = "form-autofill-$timestamp",
        packageName = packageName,
        windowClass = windowClass,
        elements = rawElements,
        screenWidth = width,
        screenHeight = height,
        windowStamp = timestamp
    )
}
