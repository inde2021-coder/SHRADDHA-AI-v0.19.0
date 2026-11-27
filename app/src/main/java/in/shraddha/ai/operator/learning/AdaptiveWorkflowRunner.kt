package `in`.shraddha.ai.operator.learning

import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.OperatorEvent
import `in`.shraddha.ai.operator.OperatorTaskState
import `in`.shraddha.ai.operator.ScreenSnapshot
import `in`.shraddha.ai.operator.ScreenResultVerifier
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import `in`.shraddha.ai.operator.files.DeviceFileLocator
import `in`.shraddha.ai.future.LocalAuditTrail

/** Re-resolves every semantic target on every screen; never replays stale coordinates. */
class AdaptiveWorkflowRunner(private val context: android.content.Context, private val emit: (OperatorEvent) -> Unit) {
    @Volatile private var stopped = false
    private val audit = LocalAuditTrail(context.applicationContext)
    private val checkpoints = WorkflowCheckpointStore(context.applicationContext)
    @Volatile private var activePackage: String? = null
    @Volatile private var activeWorkflow: String? = null
    @Volatile private var activeIndex: Int = 0

    fun stop() {
        stopped = true
        val pkg = activePackage
        val workflow = activeWorkflow
        if (pkg != null && workflow != null) {
            val existing = checkpoints.get(pkg, workflow)
            if (existing != null && existing.state == "READY") {
                checkpoints.save(existing.copy(state = "PAUSED"))
            }
        }
        emit(OperatorEvent(OperatorTaskState.CANCELLED, "ऑपरेटर रोक दिया गया।"))
    }

    fun resumableCheckpoint(profile: AppTrainingProfile, workflowName: String): WorkflowCheckpoint? {
        val steps = profile.workflows[workflowName] ?: return null
        val saved = checkpoints.getResult(profile.packageName, workflowName).getOrNull() ?: return null
        if (saved.workflowHash != checkpoints.fingerprint(steps) ||
            saved.totalSteps != steps.size ||
            saved.nextStep !in 0..steps.size ||
            saved.state !in setOf("READY", "PAUSED", "WAITING_CONFIRMATION")) return null
        return saved
    }

    fun clearCheckpoint(profile: AppTrainingProfile, workflowName: String): Boolean =
        checkpoints.clear(profile.packageName, workflowName)

    /** Resume is allowed only from a verified step boundary; interrupted in-flight actions are never replayed. */
    fun run(
        profile: AppTrainingProfile,
        workflowName: String,
        values: Map<String, String>,
        confirmed: Boolean = false,
        resume: Boolean = false
    ): Boolean {
        stopped = false
        val steps = profile.workflows[workflowName] ?: return fail("इस ऐप प्रोफ़ाइल में '$workflowName' वर्कफ़्लो नहीं मिला।")
        if (steps.isEmpty() || steps.size > 100) return fail("वर्कफ़्लो खाली है या निर्धारित सीमा से बड़ा है।")
        val preflight = WorkflowPlanValidator.validate(profile.packageName, workflowName, steps, values)
        if (!preflight.valid) return fail(preflight.message)
        val fingerprint = checkpoints.fingerprint(steps)
        var saved = checkpoints.getResult(profile.packageName, workflowName).getOrElse {
            return fail("Encrypted workflow checkpoint could not be read. Existing checkpoint data was preserved; no workflow action was started.")
        }
        var reconciledStartIndex: Int? = null
        if (resume && saved?.state == "EXECUTING") {
            // A process may die after the UI side effect but before the verified checkpoint
            // is committed. Resume only when a persisted, non-secret postcondition is visible.
            // Otherwise stop and require a human to inspect the app; never blindly replay.
            if (saved.workflowHash != fingerprint || saved.totalSteps != steps.size ||
                saved.nextStep !in steps.indices) {
                return fail("Interrupted action का checkpoint पुराने workflow से मेल नहीं खाता। कोई action दोबारा नहीं चलाया गया।")
            }
            val current = ShraddhaAccessibilityService.captureVisibleScreen()
                ?: return fail("Interrupted action की स्थिति जाँचने के लिए Accessibility सेवा उपलब्ध नहीं है।")
            if (current.packageName != profile.packageName &&
                current.packageName !in setOf("com.google.android.documentsui", "com.google.android.providers.media.module", "com.android.documentsui")) {
                return fail("Interrupted action के बाद अलग ऐप खुला है। सुरक्षा के लिए automatic resume रोक दिया गया।")
            }
            val visible = current.elements.flatMap { listOf(it.text, it.description) }.filter { it.isNotBlank() }
            when (InterruptedStepRecovery.assess(
                expectedText = saved.expectedText,
                visibleText = visible,
                beforeScreenFingerprint = saved.beforeScreenFingerprint,
                currentScreenFingerprint = recoveryFingerprint(current),
                expectedTextAbsentBefore = saved.expectedTextAbsentBefore
            )) {
                InterruptedStepRecovery.Decision.VERIFIED_COMPLETE -> {
                    val next = saved.nextStep + 1
                    val recoveredStep = steps[saved.nextStep]
                    if (!checkpoints.save(saved.copy(nextStep = next, state = "READY", expectedText = ""))) {
                        return fail("Recovered action सत्यापित हुआ, लेकिन सुरक्षित checkpoint save नहीं हुआ। आगे कोई action नहीं चलेगा।")
                    }
                    saved = checkpoints.getResult(profile.packageName, workflowName).getOrElse {
                        return fail("Recovered step was saved, but the updated checkpoint could not be read. No further action was started.")
                    }
                    reconciledStartIndex = next
                    audit.append("operator_step", "recovered_verified", "${profile.packageName}:${recoveredStep.action}")
                    emit(OperatorEvent(OperatorTaskState.VERIFYING,
                        "फोन restart के बाद पिछला चरण स्क्रीन के प्रमाण से पूरा सत्यापित हुआ; अगला चरण सुरक्षित रूप से शुरू होगा।", current))
                }
                InterruptedStepRecovery.Decision.AMBIGUOUS -> {
                    return fail("फोन बंद होने के समय वाला चरण निश्चित रूप से सत्यापित नहीं हो सका। कोई action दोबारा नहीं चलाया गया। ऐप में स्थिति जाँचें; फिर workflow को स्पष्ट रूप से resume/restart करें।")
                }
            }
        }
        val startIndex = if (reconciledStartIndex != null) {
            reconciledStartIndex!!
        } else if (resume) {
            val valid = resumableCheckpoint(profile, workflowName)
                ?: return fail("सुरक्षित resume checkpoint नहीं मिला। वर्कफ़्लो को शुरू से चलाएँ।")
            if (valid.state == "WAITING_CONFIRMATION" && !confirmed) {
                emit(OperatorEvent(OperatorTaskState.WAITING_CONFIRMATION, "आगे बढ़ने के लिए इस चरण की स्पष्ट पुष्टि आवश्यक है।"))
                return false
            }
            valid.nextStep
        } else {
            0
        }
        // A confirmation acknowledges only the exact checkpointed step. It must never
        // pre-authorize later destructive steps in the same workflow.
        var checkpointConfirmationAvailable =
            resume && saved?.state == "WAITING_CONFIRMATION" && confirmed
        if (!resume && !checkpoints.clear(profile.packageName, workflowName)) {
            return fail("Old workflow checkpoint could not be cleared safely. No workflow action was started.")
        }
        if (startIndex >= steps.size) {
            checkpoints.clear(profile.packageName, workflowName)
            emit(OperatorEvent(OperatorTaskState.COMPLETED, "इस वर्कफ़्लो के सभी चरण पहले ही सत्यापित हो चुके हैं।"))
            return true
        }
        activePackage = profile.packageName
        activeWorkflow = workflowName
        val stepsHash = fingerprint
        for ((index, step) in steps.withIndex()) {
            if (index < startIndex) continue
            activeIndex = index
            if (stopped) return false
            val resolvedInput = values[step.inputTemplate] ?: step.inputTemplate
            val risk = OperatorSafetyPolicy.assess(step.action, step.targetLabel, resolvedInput, step.expectedText)
            if (risk.risk == OperatorSafetyPolicy.Risk.BLOCK) {
                checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "FAILED"))
                return fail(risk.reason)
            }
            if (risk.risk == OperatorSafetyPolicy.Risk.CONFIRM || step.destructive) {
                if (!checkpointConfirmationAvailable) {
                    checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "WAITING_CONFIRMATION"))
                    val reason = if (risk.risk == OperatorSafetyPolicy.Risk.CONFIRM) risk.reason
                        else "यह प्रशिक्षित चरण बदलाव कर सकता है; स्पष्ट पुष्टि आवश्यक है।"
                    emit(OperatorEvent(OperatorTaskState.WAITING_CONFIRMATION, "चरण ${index + 1}: $reason"))
                    return false
                }
                checkpointConfirmationAvailable = false
            }
            val before = ShraddhaAccessibilityService.captureVisibleScreen() ?: return fail("स्क्रीन पढ़ने के लिए Accessibility सेवा चालू करें।")
            if (before.packageName != profile.packageName && before.packageName !in setOf("com.google.android.documentsui", "com.google.android.providers.media.module", "com.android.documentsui")) return fail("खुला हुआ ऐप ${before.packageName} है, जबकि प्रशिक्षित ऐप ${profile.packageName} है।")
            // Mark in-flight before the side effect. A process death here is intentionally not auto-resumable.
            val inFlightSaved = checkpoints.save(WorkflowCheckpoint(
                packageName = profile.packageName,
                workflowName = workflowName,
                workflowHash = stepsHash,
                nextStep = index,
                totalSteps = steps.size,
                state = "EXECUTING",
                // Checkpoints are encrypted at rest. Store only the postcondition, never
                // the text/value entered into a field.
                expectedText = step.expectedText.take(300),
                beforeScreenFingerprint = recoveryFingerprint(before),
                expectedTextAbsentBefore = step.expectedText.isNotBlank() &&
                    before.elements.none { element ->
                        element.text.contains(step.expectedText, true) ||
                            element.description.contains(step.expectedText, true)
                    }
            ))
            if (!inFlightSaved) return fail("इस चरण का encrypted recovery checkpoint save नहीं हुआ; सुरक्षा के लिए action execute नहीं किया गया।")
            audit.append("operator_step", "started", "${profile.packageName}:${step.action}")
            emit(OperatorEvent(OperatorTaskState.EXECUTING, "${index + 1}/${steps.size}: ${step.action}", before))
            val anchor = profile.anchors.firstOrNull { it.role.equals(step.targetRole, true) && (step.targetLabel.isBlank() || it.label.equals(step.targetLabel, true)) }
            val match = anchor?.let { SemanticMatcher.best(before.elements, it) }
            val ok = when (step.action.uppercase()) {
                "CLICK", "TAP" -> if (match != null && match.score >= .50f) ShraddhaAccessibilityService.clickByResourceId(match.element.id).let { clicked -> clicked || ShraddhaAccessibilityService.clickVisibleText(match.element.text.ifBlank { match.element.description }) } else false
                "TYPE" -> {
                    val value = resolvedInput
                    // Never fall back to whichever field happens to have focus.
                    // A workflow must identify one trained, visible, non-sensitive field.
                    if (match != null && match.score >= .50f && match.element.id.isNotBlank()) {
                        ShraddhaAccessibilityService.fillByResourceId(match.element.id, value)
                    } else {
                        false
                    }
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
            if (!ok) {
                checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "FAILED"))
                audit.append("operator_step", "failed", "${profile.packageName}:${step.action}")
                return fail("चरण ${index + 1} का लक्ष्य भरोसेमंद तरीके से नहीं मिला या ऐप ने कार्रवाई रोकी। AI ने अनुमान से टैप नहीं किया।")
            }
            Thread.sleep(500)
            val after: ScreenSnapshot = ShraddhaAccessibilityService.captureVisibleScreen() ?: run {
                checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "FAILED"))
                return fail("कार्रवाई के बाद स्क्रीन सत्यापित नहीं हो सकी।")
            }
            if (after.packageName != profile.packageName &&
                after.packageName !in setOf("com.google.android.documentsui", "com.google.android.providers.media.module", "com.android.documentsui")) {
                checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "FAILED"))
                return fail("कार्रवाई के बाद foreground ऐप बदल गया। सुरक्षा के लिए workflow रोक दिया गया।")
            }
            if (step.expectedText.isNotBlank() && after.elements.none { it.text.contains(step.expectedText, true) || it.description.contains(step.expectedText, true) }) {
                checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, index, steps.size, "FAILED"))
                return fail("चरण ${index + 1} के बाद अपेक्षित परिणाम नहीं मिला; वर्कफ़्लो रोक दिया गया।")
            }
            val next = index + 1
            if (!checkpoints.save(WorkflowCheckpoint(profile.packageName, workflowName, stepsHash, next, steps.size, "READY"))) {
                return fail("चरण UI पर सत्यापित हुआ, लेकिन अगला सुरक्षित checkpoint save नहीं हुआ। Workflow रोक दिया गया है।")
            }
            audit.append("operator_step", "verified", "${profile.packageName}:${step.action}")
            emit(OperatorEvent(OperatorTaskState.VERIFYING, "चरण ${index + 1} पूरा; स्क्रीन दोबारा पढ़ी गई।", after))
        }
        checkpoints.clear(profile.packageName, workflowName)
        activePackage = null
        activeWorkflow = null
        audit.append("operator_workflow", "completed", "${profile.packageName}:$workflowName")
        emit(OperatorEvent(OperatorTaskState.COMPLETED, "सीखे गए वर्कफ़्लो के सभी चरण पूरे और सत्यापित हुए।", ShraddhaAccessibilityService.captureVisibleScreen()))
        return true
    }
    private fun recoveryFingerprint(snapshot: ScreenSnapshot): String {
        val canonical = buildString {
            append(snapshot.packageName).append('|').append(snapshot.activityName)
            snapshot.elements.forEach { element ->
                append('|').append(element.id).append(':').append(element.text).append(':')
                    .append(element.description).append(':').append(element.bounds.flattenToString())
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun fail(message: String): Boolean { emit(OperatorEvent(OperatorTaskState.FAILED, message)); return false }
}
