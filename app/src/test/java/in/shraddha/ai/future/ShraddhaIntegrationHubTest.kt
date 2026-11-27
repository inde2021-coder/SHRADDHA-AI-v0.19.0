package `in`.shraddha.ai.future

import org.junit.Assert.*
import org.junit.Test

class ShraddhaIntegrationHubTest {
    @Test fun planFlowsThroughApprovalExecutionVerificationAndAudit() {
        val hub = ShraddhaIntegrationHub()
        val planned = hub.createPlan("voice-task-1", "Send a prepared message", listOf(
            ShraddhaIntegrationHub.Action("draft", "Prepare message", priority = 1),
            ShraddhaIntegrationHub.Action("send", "Send message", dependsOn = setOf("draft"), risk = Risk.SENSITIVE)
        ), now = 10)
        assertEquals(listOf("draft", "send"), planned.workflow.steps.map { it.id })
        assertEquals(WorkflowStatus.DRAFT, planned.workflow.status)

        var session = hub.start(now = 20)
        assertEquals(WorkflowStatus.RUNNING, session.workflow.status)
        session = hub.markActionDispatched(now = 30)
        assertEquals(WorkflowStatus.VERIFYING, session.workflow.status)
        session = hub.verifyCurrentStep(true, now = 40)
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, session.workflow.status)
        assertEquals("send", session.workflow.steps[session.workflow.currentStep].id)

        session = hub.approveCurrentStep(true, now = 50)
        assertEquals(WorkflowStatus.RUNNING, session.workflow.status)
        session = hub.markActionDispatched(now = 60)
        session = hub.verifyCurrentStep(true, now = 70)
        assertEquals(WorkflowStatus.COMPLETED, session.workflow.status)
        session = hub.reviewCompletion(listOf(SelfReviewEngine.Check("result", "Expected result observed", true)), now = 80)
        assertEquals(WorkflowStatus.COMPLETED, session.workflow.status)
        assertEquals("FINAL_REVIEW_PASSED", session.audit.last().event)
        assertEquals(8, session.audit.size)
    }

    @Test fun decliningSensitiveActionPausesWithoutDispatch() {
        val hub = ShraddhaIntegrationHub()
        hub.createPlan("approval-1", "Delete request", listOf(
            ShraddhaIntegrationHub.Action("delete", "Delete selected item", risk = Risk.IRREVERSIBLE)
        ))
        val waiting = hub.start()
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, waiting.workflow.status)
        val declined = hub.approveCurrentStep(false)
        assertEquals(WorkflowStatus.PAUSED, declined.workflow.status)
        assertTrue(declined.audit.any { it.event == "STEP_DECLINED" })
        try {
            hub.markActionDispatched()
            fail("Declined action must never dispatch")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun failedVerificationStopsRemainingSteps() {
        val hub = ShraddhaIntegrationHub()
        hub.createPlan("verify-1", "Two step task", listOf(
            ShraddhaIntegrationHub.Action("one", "First"),
            ShraddhaIntegrationHub.Action("two", "Second", dependsOn = setOf("one"))
        ))
        hub.start()
        hub.markActionDispatched()
        val failed = hub.verifyCurrentStep(false)
        assertEquals(WorkflowStatus.FAILED, failed.workflow.status)
        assertEquals(0, failed.workflow.currentStep)
        assertFalse(failed.audit.any { it.actionId == "two" && it.event == "ACTION_DISPATCHED" })
    }

    @Test fun finalReviewCanRejectNominallyCompletedWorkflow() {
        val hub = ShraddhaIntegrationHub()
        hub.createPlan("review-1", "Read-only task", listOf(ShraddhaIntegrationHub.Action("read", "Read status")))
        hub.start()
        hub.markActionDispatched()
        hub.verifyCurrentStep(true)
        val rejected = hub.reviewCompletion(listOf(
            SelfReviewEngine.Check("result", "Result verified", true),
            SelfReviewEngine.Check("privacy", "No sensitive data exposed", false)
        ))
        assertEquals(WorkflowStatus.FAILED, rejected.workflow.status)
        assertEquals("FINAL_REVIEW_FAILED", rejected.audit.last().event)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOrInvalidTaskGraphCannotEnterCoordinator() {
        ShraddhaIntegrationHub().createPlan("bad", "Bad graph", listOf(
            ShraddhaIntegrationHub.Action("x", "First"),
            ShraddhaIntegrationHub.Action("x", "Duplicate")
        ))
    }

    @Test fun recoveredWorkflowRejectsMismatchedPlanAndStepIds() {
        try {
            ShraddhaIntegrationHub.validateRecoveredIdentity("wf-1", listOf("a", "b"), listOf("a", "other"), listOf("wf-1"))
            fail("Mismatched recovered step identities must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun recoveredWorkflowRejectsAuditFromAnotherWorkflow() {
        try {
            ShraddhaIntegrationHub.validateRecoveredIdentity("wf-1", listOf("a"), listOf("a"), listOf("wf-1", "wf-2"))
            fail("Foreign audit entries must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun recoveredWorkflowRejectsDuplicatePlannedStepIds() {
        try {
            ShraddhaIntegrationHub.validateRecoveredIdentity("wf-1", listOf("a", "a"), listOf("a", "a"), listOf("wf-1"))
            fail("Duplicate planned step identities must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun recoveredWorkflowAcceptsMatchingPlanStepAndAuditIdentity() {
        ShraddhaIntegrationHub.validateRecoveredIdentity("wf-1", listOf("a", "b"), listOf("a", "b"), listOf("wf-1", "wf-1"))
    }
}
