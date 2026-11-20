package `in`.shraddha.ai.operator

import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/** Local, permission-gated observe -> act -> re-observe engine. It never bypasses Android security surfaces. */
class OperatorEngine(\n    private val emit: (OperatorEvent) -> Unit,\n    private val audit: (event: String, outcome: String, detail: String) -> Unit = { _, _, _ -> }\n) {\n    private fun record(event: String, outcome: String, step: OperatorStep, snapshot: ScreenSnapshot? = null) {\n        // Never audit typed text, screen contents or secrets. Record action metadata only.\n        runCatching { audit(event, outcome, "action=${step.action}; target=${step.target.take(80)}; package=${snapshot?.packageName.orEmpty().take(120)}") }\n    }
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; emit(OperatorEvent(OperatorTaskState.CANCELLED, "काम उपयोगकर्ता ने रोक दिया।")) }

    fun observe(): ScreenSnapshot? {
        emit(OperatorEvent(OperatorTaskState.OBSERVING, "स्क्रीन की वर्तमान स्थिति देखी जा रही है…"))
        val snapshot = ShraddhaAccessibilityService.captureVisibleScreen() ?: run {
            emit(OperatorEvent(OperatorTaskState.FAILED, "Accessibility सेवा उपलब्ध नहीं है या स्क्रीन पढ़ने योग्य नहीं है।")); return null
        }
        emit(OperatorEvent(OperatorTaskState.OBSERVING, "स्क्रीन पढ़ ली गई: ${snapshot.elements.size} दृश्य तत्व।", snapshot))
        return snapshot
    }

    /** Execute one explicitly supplied step at a time. Sensitive actions are deliberately excluded. */
    fun execute(step: OperatorStep, confirmed: Boolean = false): Boolean {
        if (cancelled) { record("operator_action", "cancelled", step); return false }
        val safety = OperatorSafetyPolicy.assess(step.action.name, step.target, step.value)
        if (safety.risk == OperatorSafetyPolicy.Risk.BLOCK) {
            emit(OperatorEvent(OperatorTaskState.FAILED, safety.reason)); record("operator_action", "blocked", step); return false
        }
        if ((step.requiresConfirmation || safety.risk == OperatorSafetyPolicy.Risk.CONFIRM) && !confirmed) {
            emit(OperatorEvent(OperatorTaskState.WAITING_CONFIRMATION, safety.reason)); record("operator_action", "awaiting_confirmation", step); return false
        }
        val before = observe() ?: run { record("operator_action", "observe_failed", step); return false }
        emit(OperatorEvent(OperatorTaskState.EXECUTING, "कार्रवाई: ${step.action}", before))
        val ok = when (step.action) {
            OperatorActionType.OPEN_APP -> ShraddhaAccessibilityService.openApp(step.target)
            OperatorActionType.CLICK_TEXT -> {
                val match = before.elements.firstOrNull { e ->
                    e.clickable && (e.text.equals(step.target, true) || e.description.equals(step.target, true))
                } ?: before.elements.firstOrNull { e -> e.text.contains(step.target, true) || e.description.contains(step.target, true) }
                match != null && ShraddhaAccessibilityService.clickVisibleText(step.target)
            }
            OperatorActionType.TYPE_TEXT -> {
                val focused = before.elements.firstOrNull { it.editable && !it.password }
                focused != null && ShraddhaAccessibilityService.fillFocusedField(step.value.take(2000))
            }
            OperatorActionType.BACK -> ShraddhaAccessibilityService.goBack()
            OperatorActionType.HOME -> ShraddhaAccessibilityService.goHome()
            OperatorActionType.SCROLL_FORWARD -> ShraddhaAccessibilityService.scrollForward()
        }
        if (!ok) {
            emit(OperatorEvent(OperatorTaskState.FAILED, "कार्रवाई नहीं हो सकी। स्क्रीन/ऐप की अनुमति या UI स्थिति जाँचें।")); record("operator_action", "dispatch_failed", step, before); return false
        }
        emit(OperatorEvent(OperatorTaskState.VERIFYING, "कार्रवाई के बाद स्क्रीन दोबारा जाँची जा रही है…"))
        Thread.sleep(450)
        val after = observe() ?: run { record("operator_action", "verification_unavailable", step, before); return false }
        if (step.expectedVisibleText.isNotBlank() && after.elements.none {
                it.text.contains(step.expectedVisibleText, true) || it.description.contains(step.expectedVisibleText, true)
            }) {
            emit(OperatorEvent(OperatorTaskState.FAILED, "कार्रवाई हुई, लेकिन अपेक्षित परिणाम स्क्रीन पर सत्यापित नहीं हुआ।", after)); record("operator_action", "verification_failed", step, after); return false
        }
        emit(OperatorEvent(OperatorTaskState.COMPLETED, "चरण पूरा हुआ और स्क्रीन दोबारा जाँची गई।", after))
        return true
    }
}
