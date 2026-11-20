package `in`.shraddha.ai.operator.learning

import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.OperatorEvent
import `in`.shraddha.ai.operator.OperatorTaskState
import `in`.shraddha.ai.operator.ScreenSnapshot
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import `in`.shraddha.ai.operator.files.DeviceFileLocator
import `in`.shraddha.ai.future.LocalAuditTrail

/** Re-resolves every semantic target on every screen; never replays stale coordinates. */
class AdaptiveWorkflowRunner(private val context: android.content.Context, private val emit: (OperatorEvent) -> Unit) {
    @Volatile private var stopped = false
    private val audit = LocalAuditTrail(context.applicationContext)
    fun stop() { stopped = true; emit(OperatorEvent(OperatorTaskState.CANCELLED, "ऑपरेटर रोक दिया गया।")) }

    fun run(profile: AppTrainingProfile, workflowName: String, values: Map<String, String>, startAt: Int = 0, confirmedStepIndex: Int? = null): Boolean {
        val steps = profile.workflows[workflowName] ?: return fail("इस ऐप प्रोफ़ाइल में '$workflowName' वर्कफ़्लो नहीं मिला।")
        if (steps.isEmpty()) return fail("वर्कफ़्लो खाली है।")
        if (startAt !in steps.indices) return fail("Workflow resume position is invalid.")
        for (index in startAt until steps.size) {
            val step = steps[index]
            if (stopped) return false
            // Re-check the resolved value, not only the training placeholder. A saved
            // template such as "customer_phone" must not become a route for injecting
            // an OTP, password, PIN or payment security code at runtime.
            val resolvedInput = values[step.inputTemplate] ?: step.inputTemplate
            val risk = OperatorSafetyPolicy.assess(step.action, step.targetLabel, resolvedInput)
            if (risk.risk == OperatorSafetyPolicy.Risk.BLOCK) return fail(risk.reason)
            if ((risk.risk == OperatorSafetyPolicy.Risk.CONFIRM || step.destructive) && confirmedStepIndex != index) {
                emit(OperatorEvent(OperatorTaskState.WAITING_CONFIRMATION, "चरण ${index + 1}: ${risk.reason} इस चरण को चलाने से पहले अलग अनुमति चाहिए।", stepIndex = index))
                return false
            }
            val before = ShraddhaAccessibilityService.captureVisibleScreen() ?: return fail("स्क्रीन पढ़ने के लिए Accessibility सेवा चालू करें।")
            if (before.packageName != profile.packageName && before.packageName !in setOf("com.google.android.documentsui", "com.google.android.providers.media.module", "com.android.documentsui")) return fail("खुला हुआ ऐप ${before.packageName} है, जबकि प्रशिक्षित ऐप ${profile.packageName} है।")
            audit.append("operator_step", "started", "${profile.packageName}:${step.action}")
            emit(OperatorEvent(OperatorTaskState.EXECUTING, "${index + 1}/${steps.size}: ${step.action}", before))
            val anchor = profile.anchors.firstOrNull { it.role.equals(step.targetRole, true) && (step.targetLabel.isBlank() || it.label.equals(step.targetLabel, true)) }
            val match = anchor?.let { SemanticMatcher.best(before.elements, it) }
            val ok = when (step.action.uppercase()) {
                "CLICK", "TAP" -> if (match != null && match.score >= .50f) ShraddhaAccessibilityService.clickByResourceId(match.element.id).let { clicked -> clicked || ShraddhaAccessibilityService.clickVisibleText(match.element.text.ifBlank { match.element.description }) } else false
                "TYPE" -> {
                    val value = resolvedInput
                    if (match != null && match.element.id.isNotBlank()) ShraddhaAccessibilityService.fillByResourceId(match.element.id, value)
                    else ShraddhaAccessibilityService.fillFocusedField(value)
                }
                "BACK" -> ShraddhaAccessibilityService.goBack()
                "SCROLL" -> ShraddhaAccessibilityService.scrollForward()
                "SELECT_FILE" -> {
                    val requested = resolvedInput
                    val search = DeviceFileLocator(context).search(requested, values["__file_folder"].orEmpty())
                    if (search.matches.size == 1) ShraddhaAccessibilityService.selectFileInPicker(search.matches.first().displayName) else false
                }
                else -> false
            }
            if (!ok) { audit.append("operator_step", "failed", "${profile.packageName}:${step.action}"); return fail("चरण ${index + 1} का लक्ष्य भरोसेमंद तरीके से नहीं मिला या ऐप ने कार्रवाई रोकी। AI ने अनुमान से टैप नहीं किया।") }
            Thread.sleep(500)
            val after: ScreenSnapshot = ShraddhaAccessibilityService.captureVisibleScreen() ?: return fail("कार्रवाई के बाद स्क्रीन सत्यापित नहीं हो सकी।")
            if (step.expectedText.isNotBlank() && after.elements.none { it.text.contains(step.expectedText, true) || it.description.contains(step.expectedText, true) }) return fail("चरण ${index + 1} के बाद अपेक्षित परिणाम नहीं मिला; वर्कफ़्लो रोक दिया गया।")
            audit.append("operator_step", "verified", "${profile.packageName}:${step.action}")
            emit(OperatorEvent(OperatorTaskState.VERIFYING, "चरण ${index + 1} पूरा; स्क्रीन दोबारा पढ़ी गई।", after))
        }
        audit.append("operator_workflow", "completed", "${profile.packageName}:$workflowName")
        emit(OperatorEvent(OperatorTaskState.COMPLETED, "सीखे गए वर्कफ़्लो के सभी चरण पूरे और सत्यापित हुए।", ShraddhaAccessibilityService.captureVisibleScreen()))
        return true
    }
    private fun fail(message: String): Boolean { emit(OperatorEvent(OperatorTaskState.FAILED, message)); return false }
}
