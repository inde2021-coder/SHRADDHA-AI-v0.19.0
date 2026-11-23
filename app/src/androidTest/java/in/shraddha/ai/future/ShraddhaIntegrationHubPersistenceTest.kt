package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShraddhaIntegrationHubPersistenceTest {
    @Test fun activeWorkflowPersistsAndInterruptedActionIsNeverReplayed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = EncryptedFeatureStore(context)
        val workflowId = "hub_test_" + System.currentTimeMillis()
        try {
            val first = ShraddhaIntegrationHub(context = context)
            first.createPlan(workflowId, "Encrypted workflow recovery", listOf(
                ShraddhaIntegrationHub.Action("inspect", "Inspect current app state", risk = Risk.READ_ONLY)
            ))
            first.start()
            first.markActionDispatched()
            assertEquals(WorkflowStatus.VERIFYING, first.current()?.workflow?.status)

            val restored = ShraddhaIntegrationHub(context = context)
            val session = restored.current() ?: error("Persisted workflow was not restored")
            assertEquals(workflowId, session.workflow.id)
            assertEquals(WorkflowStatus.FAILED, session.workflow.status)
            assertTrue(session.workflow.message.contains("No action was replayed"))
            assertTrue(session.audit.any { it.event == "PROCESS_RESTARTED_REQUIRES_REVIEW" })

            val ciphertext = raw.get("integration_hub_active_v1").getOrThrow()
            assertFalse(ciphertext.contains("Encrypted workflow recovery"))
            assertFalse(ciphertext.contains("Inspect current app state"))
        } finally {
            raw.remove("integration_hub_active_v1")
        }
    }
}
