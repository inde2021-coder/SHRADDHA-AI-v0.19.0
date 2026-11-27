package `in`.shraddha.ai.operator.learning

import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/**
 * Side-effect-free whole-plan validation. A later unsafe step must not be discovered
 * only after earlier steps have already changed the user's app.
 */
internal object WorkflowPlanValidator {
    data class Result(val valid: Boolean, val message: String = "")

    private val supportedActions = setOf("CLICK", "TAP", "TYPE", "BACK", "SCROLL", "SELECT_FILE")
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun validate(
        packageName: String,
        workflowName: String,
        steps: List<LearnedStep>,
        values: Map<String, String>
    ): Result {
        if (!packagePattern.matches(packageName)) {
            return Result(false, "ऐप प्रोफ़ाइल का package पहचान मान्य नहीं है।")
        }
        if (workflowName.isBlank() || workflowName.length > 100) {
            return Result(false, "Workflow का नाम खाली या बहुत लंबा है।")
        }
        if (steps.isEmpty() || steps.size > 100) {
            return Result(false, "Workflow खाली है या निर्धारित 100 चरणों की सीमा से बड़ा है।")
        }
        if (steps.any { it.id.isBlank() || it.id.length > 80 } ||
            steps.map { it.id }.toSet().size != steps.size) {
            return Result(false, "Workflow में चरण ID खाली, बहुत लंबी या दोहराई गई है।")
        }

        for ((index, step) in steps.withIndex()) {
            val action = step.action.trim().uppercase()
            if (action !in supportedActions) {
                return Result(false, "चरण ${index + 1} में unsupported action है; कोई कार्रवाई शुरू नहीं की गई।")
            }
            if (step.targetRole.length > 80 || step.targetLabel.length > 160 ||
                step.inputTemplate.length > 500 || step.expectedText.length > 300) {
                return Result(false, "चरण ${index + 1} में target या input सीमा से बड़ा है।")
            }
            if (action in setOf("CLICK", "TAP", "TYPE") &&
                step.targetRole.isBlank() && step.targetLabel.isBlank()) {
                return Result(false, "चरण ${index + 1} में UI target नहीं दिया गया है।")
            }
            if (action == "TYPE" && step.inputTemplate.isBlank()) {
                return Result(false, "चरण ${index + 1} में भरने के लिए कोई स्पष्ट input नहीं है।")
            }
            if (action == "SELECT_FILE" && step.inputTemplate.isBlank()) {
                return Result(false, "चरण ${index + 1} में चुनने के लिए फ़ाइल का नाम नहीं दिया गया है।")
            }

            val resolvedInput = values[step.inputTemplate] ?: step.inputTemplate
            if (resolvedInput.length > 1_000) {
                return Result(false, "चरण ${index + 1} का input बहुत लंबा है।")
            }
            val safety = OperatorSafetyPolicy.assess(
                action, step.targetLabel, resolvedInput, step.expectedText
            )
            if (safety.risk == OperatorSafetyPolicy.Risk.BLOCK) {
                return Result(false, "चरण ${index + 1}: ${safety.reason} पूरे Workflow को शुरू होने से पहले रोक दिया गया।")
            }
        }
        return Result(true)
    }
}
