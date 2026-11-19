package `in`.shraddha.ai.operator

import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService

/** Local, permission-gated observe -> act -> re-observe engine. It never bypasses Android security surfaces. */
class OperatorEngine(private val emit: (OperatorEvent) -> Unit) {
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
        if (cancelled) return false
        if (step.requiresConfirmation && !confirmed) {
            emit(OperatorEvent(OperatorTaskState.WAITING_CONFIRMATION, "इस चरण के लिए आपकी स्पष्ट अनुमति आवश्यक है।")); return false
        }
        val before = observe() ?: return false
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
            emit(OperatorEvent(OperatorTaskState.FAILED, "कार्रवाई नहीं हो सकी। स्क्रीन/ऐप की अनुमति या UI स्थिति जाँचें।")); return false
        }
        emit(OperatorEvent(OperatorTaskState.VERIFYING, "कार्रवाई के बाद स्क्रीन दोबारा जाँची जा रही है…"))
        Thread.sleep(450)
        val after = observe() ?: return false
        if (step.expectedVisibleText.isNotBlank() && after.elements.none {
                it.text.contains(step.expectedVisibleText, true) || it.description.contains(step.expectedVisibleText, true)
            }) {
            emit(OperatorEvent(OperatorTaskState.FAILED, "कार्रवाई हुई, लेकिन अपेक्षित परिणाम स्क्रीन पर सत्यापित नहीं हुआ।", after)); return false
        }
        emit(OperatorEvent(OperatorTaskState.COMPLETED, "चरण पूरा हुआ और स्क्रीन दोबारा जाँची गई।", after))
        return true
    }
}
