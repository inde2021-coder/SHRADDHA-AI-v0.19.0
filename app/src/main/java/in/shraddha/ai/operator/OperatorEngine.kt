package `in`.shraddha.ai.operator

import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/** Local, permission-gated observe -> act -> re-observe engine. It never bypasses Android security surfaces. */
class OperatorEngine(
    private val emit: (OperatorEvent) -> Unit,
    private val audit: (event: String, outcome: String, detail: String) -> Unit = { _, _, _ -> }
) {
    private fun record(event: String, outcome: String, step: OperatorStep, snapshot: ScreenSnapshot? = null) {
        // Never audit typed text, screen contents or secrets. Record action metadata only.
        runCatching { audit(event, outcome, "action=${step.action}; target=${step.target.take(80)}; package=${snapshot?.packageName.orEmpty().take(120)}") }
    }
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

    private fun screenFingerprint(snapshot: ScreenSnapshot): String =
        buildString {
            append(snapshot.packageName).append('|').append(snapshot.activityName)
            snapshot.elements.forEach { element ->
                append('|').append(element.id).append(':').append(element.text).append(':')
                    .append(element.description).append(':').append(element.bounds.flattenToString())
            }
        }

    /** Execute one explicitly supplied step at a time. Sensitive actions are deliberately excluded. */
    fun execute(step: OperatorStep, confirmed: Boolean = false): Boolean {
        if (cancelled) { record("operator_action", "cancelled", step); return false }
        val safety = OperatorSafetyPolicy.assess(
            step.action.name, step.target, step.value, step.expectedVisibleText,
            step.explicitlyAuthorizedByUser
        )
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
                val match = OperatorTargetResolutionPolicy.selectUnique(
                    step.target,
                    before.elements.filter { it.clickable && !it.password }
                ) { element -> listOf(element.text, element.description) }
                // The accessibility service independently rechecks visibility, sensitivity and uniqueness.
                match != null && ShraddhaAccessibilityService.clickVisibleText(step.target)
            }
            OperatorActionType.TYPE_TEXT -> {
                // Never type into whichever field happens to have focus. The caller
                // must identify one visible, non-sensitive field by its exact label,
                // hint or resource ID; the service independently checks uniqueness.
                val wanted = step.target.trim()
                val matches = before.elements.filter { element ->
                    element.editable && !element.password && wanted.isNotBlank() &&
                        listOf(element.id, element.text, element.description, element.hintText)
                            .any { it.isNotBlank() && it.trim().equals(wanted, ignoreCase = true) }
                }
                matches.size == 1 && ShraddhaAccessibilityService.fillEditableField(
                    wanted, step.value.take(2000)
                )
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
        val expectedVisible = if (step.explicitlyAuthorizedByUser) {
            // Verify the exact dictated message as a conversation item, not merely in the composer.
            val sentItemVisible = after.elements.any { element ->
                !element.editable && !element.password &&
                    (element.text.contains(step.expectedVisibleText, true) ||
                        element.description.contains(step.expectedVisibleText, true))
            }
            val composerStillContains = after.elements.any { element ->
                element.editable && !element.password &&
                    (element.text.contains(step.expectedVisibleText, true) ||
                        element.description.contains(step.expectedVisibleText, true))
            }
            step.expectedVisibleText.isNotBlank() && sentItemVisible && !composerStillContains
        } else step.expectedVisibleText.isNotBlank() && after.elements.any {
            it.text.contains(step.expectedVisibleText, true) || it.description.contains(step.expectedVisibleText, true)
        }
        val screenChanged = screenFingerprint(before) != screenFingerprint(after)
        val actionVerified = if (step.expectedVisibleText.isNotBlank()) expectedVisible else when (step.action) {
            OperatorActionType.TYPE_TEXT -> step.value.isNotBlank() && after.elements.any {
                !it.password && (it.text.contains(step.value, true) || it.description.contains(step.value, true))
            }
            OperatorActionType.OPEN_APP -> screenChanged ||
                after.packageName.contains(step.target, true) || after.activityName.contains(step.target, true)
            OperatorActionType.CLICK_TEXT, OperatorActionType.BACK, OperatorActionType.HOME, OperatorActionType.SCROLL_FORWARD -> screenChanged
        }
        if (!actionVerified) {
            emit(OperatorEvent(OperatorTaskState.FAILED, "कार्रवाई हुई, लेकिन अपेक्षित UI बदलाव/परिणाम सत्यापित नहीं हुआ।", after)); record("operator_action", "verification_failed", step, after); return false
        }
        emit(OperatorEvent(OperatorTaskState.COMPLETED, "चरण पूरा हुआ और स्क्रीन दोबारा जाँची गई।", after))
        record("operator_action", "verified", step, after)
        return true
    }
}
