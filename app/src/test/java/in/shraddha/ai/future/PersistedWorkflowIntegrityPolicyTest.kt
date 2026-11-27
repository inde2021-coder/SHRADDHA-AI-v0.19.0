package `in`.shraddha.ai.future

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedWorkflowIntegrityPolicyTest {
    private fun valid(
        taskIds: List<String> = listOf("read", "send"),
        stepIds: List<String> = taskIds,
        auditWorkflowIds: List<String> = listOf("wf-1"),
        auditActionIds: List<String> = listOf("read"),
        events: List<String> = listOf("PLAN_CREATED"),
        details: List<String> = listOf("2 steps"),
        timestamps: List<Long> = listOf(100L)
    ) = PersistedWorkflowIntegrityPolicy.isValid(
        "wf-1", taskIds, stepIds, auditWorkflowIds, auditActionIds, events, details, timestamps
    )

    @Test fun acceptsConsistentPersistedWorkflow() {
        assertTrue(valid())
    }

    @Test fun rejectsPlanStepMismatchAndDuplicateIds() {
        assertFalse(valid(stepIds = listOf("send", "read")))
        assertFalse(valid(taskIds = listOf("read", "read"), stepIds = listOf("read", "read")))
    }

    @Test fun rejectsAuditEntriesFromAnotherWorkflowOrUnknownAction() {
        assertFalse(valid(auditWorkflowIds = listOf("other")))
        assertFalse(valid(auditActionIds = listOf("missing")))
    }

    @Test fun rejectsMismatchedAuditColumnsAndInvalidTimestamps() {
        assertFalse(valid(events = emptyList()))
        assertFalse(valid(timestamps = listOf(-1L)))
    }

    @Test fun rejectsMalformedWorkflowIdentifiers() {
        assertFalse(PersistedWorkflowIntegrityPolicy.isValid(
            "bad/id", listOf("read"), listOf("read"), listOf("bad/id"),
            emptyList(), listOf("PLAN"), listOf(""), listOf(1L)
        ))
    }
}
