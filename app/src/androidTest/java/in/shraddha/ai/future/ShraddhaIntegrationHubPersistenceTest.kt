package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShraddhaIntegrationHubPersistenceTest {
    @Test fun activeWorkflowPersistsAndInterruptedActionIsNeverReplayed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
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

            val ciphertext = prefs.getString("integration_hub_active_v1", null) ?: error("No encrypted workflow snapshot")
            assertFalse(ciphertext.contains("Encrypted workflow recovery"))
            assertFalse(ciphertext.contains("Inspect current app state"))
            val persisted = JSONObject(EncryptedFeatureStore(context).get("integration_hub_active_v1").getOrThrow())
            assertEquals("FAILED", persisted.getJSONObject("workflow").getString("status"))
        } finally {
            prefs.edit().remove("integration_hub_active_v1").commit()
        }
    }
}
