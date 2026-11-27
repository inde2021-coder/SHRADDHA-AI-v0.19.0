package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAuditPolicyTest {
    private fun event(index: Int, detail: String = "detail") =
        ShraddhaIntegrationHub.AuditEvent("workflow-1", "step-$index", "EVENT_$index", detail, index.toLong())

    @Test fun keepsOnlyNewestEventsAtRecoveryLimit() {
        val result = (0..WorkflowAuditPolicy.MAX_EVENTS).fold(emptyList<ShraddhaIntegrationHub.AuditEvent>()) { acc, i ->
            WorkflowAuditPolicy.append(acc, event(i))
        }
        assertEquals(WorkflowAuditPolicy.MAX_EVENTS, result.size)
        assertEquals("step-1", result.first().actionId)
        assertEquals("step-2000", result.last().actionId)
    }

    @Test fun truncatesEventAndDetailToPersistedValidationBounds() {
        val result = WorkflowAuditPolicy.append(emptyList(), event(1, "x".repeat(700)).copy(event = "E".repeat(120)))
        assertEquals(WorkflowAuditPolicy.MAX_EVENT_LENGTH, result.single().event.length)
        assertEquals(WorkflowAuditPolicy.MAX_DETAIL_LENGTH, result.single().detail.length)
    }

    @Test fun rejectsInvalidCrossReferencesAndNegativeTime() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowAuditPolicy.append(emptyList(), event(1).copy(workflowId = "bad/id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowAuditPolicy.append(emptyList(), event(1).copy(actionId = "bad/id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowAuditPolicy.append(emptyList(), event(1).copy(timestamp = -1L))
        }
    }

    @Test fun doesNotMutatePriorAuditList() {
        val initial = listOf(event(1))
        val result = WorkflowAuditPolicy.append(initial, event(2))
        assertEquals(1, initial.size)
        assertTrue(result.size == 2)
        assertFalse(result === initial)
    }
}
