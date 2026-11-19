package `in`.shraddha.ai.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.brain.SecureMemoryVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DeviceRecoveryBundleInstrumentedTest {
    @Test fun selectedDataSnapshotRestoresProfilesWorkflowsPreferencesAndMemory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedFeatureStore(context)
        val memory = SecureMemoryVault(context)
        val projectPrefs = context.getSharedPreferences("shraddha_projects", Context.MODE_PRIVATE)
        val twinPrefs = context.getSharedPreferences("shraddha_digital_twin", Context.MODE_PRIVATE)
        val mirrorPrefs = context.getSharedPreferences("shraddha_local_memory", Context.MODE_PRIVATE)
        val graphPrefs = context.getSharedPreferences("shraddha_knowledge_graph", Context.MODE_PRIVATE)
        try {
            listOf("person_directory", "kids_profile", "kids_care_log", "task_inbox", "workflow_checkpoint_", "app_profile_")
                .forEach { store.removeAllWithPrefix(it) }
            store.put("person_directory", "[]").getOrThrow()
            store.put("workflow_checkpoint_0123456789abcdef", JSONObject().put("state", "READY").toString()).getOrThrow()
            store.put("app_profile_0123456789abcdef", JSONObject().put("packageName", "com.example.app").toString()).getOrThrow()
            memory.save("private recovery note").getOrThrow()
            projectPrefs.edit().putString("active_project", "{\"title\":\"Test project\"}").commit()
            twinPrefs.edit().putString("name", "Shraddha").putString("language", "Hindi").putString("style", "step-by-step").commit()
            graphPrefs.edit().putString("nodes", "[{\"id\":\"node-1\"}]").commit()

            val bundle = DeviceRecoveryBundle.capture(context).getOrThrow()
            val preview = DeviceRecoveryBundle.preview(bundle).getOrThrow()
            assertEquals(3, preview.recordCount)
            assertEquals(1, preview.appProfileCount)
            assertEquals(1, preview.workflowCheckpointCount)
            assertTrue(preview.hasMemory)
            assertTrue(preview.hasActiveProject)
            assertTrue(preview.hasKnowledgeGraph)

            store.put("person_directory", "[{\"id\":\"person_0001\"}]").getOrThrow()
            store.put("task_inbox", "[{\"id\":\"new\"}]").getOrThrow()
            memory.save("changed local note").getOrThrow()
            projectPrefs.edit().putString("active_project", "changed").commit()
            twinPrefs.edit().putString("name", "Changed").commit()
            graphPrefs.edit().putString("nodes", "[]").commit()

            DeviceRecoveryBundle.restore(context, bundle).getOrThrow()
            assertEquals("[]", store.get("person_directory").getOrThrow())
            assertEquals("", store.get("task_inbox").getOrThrow())
            assertEquals("private recovery note", memory.load().getOrThrow())
            assertEquals("{\"title\":\"Test project\"}", projectPrefs.getString("active_project", ""))
            assertEquals("Shraddha", twinPrefs.getString("name", ""))
            assertEquals("[{\"id\":\"node-1\"}]", graphPrefs.getString("nodes", "[]"))
            assertEquals("private recovery note", mirrorPrefs.getString("notes", ""))
        } finally {
            listOf("person_directory", "kids_profile", "kids_care_log", "task_inbox", "workflow_checkpoint_", "app_profile_")
                .forEach { store.removeAllWithPrefix(it) }
            memory.clear()
            projectPrefs.edit().clear().commit()
            twinPrefs.edit().clear().commit()
            mirrorPrefs.edit().clear().commit()
            graphPrefs.edit().clear().commit()
        }
    }

    @Test fun recoveryRejectsUnapprovedKeysAndMalformedPersonDirectory() {
        val unknown = JSONObject()
            .put("version", 2)
            .put("schema", "shraddha-device-recovery-v2")
            .put("items", JSONObject().put("api_key", "secret"))
            .put("memory", "")
            .put("preferences", JSONObject().put("activeProject", "").put("digitalTwin", JSONObject()))
            .toString()
        assertTrue(DeviceRecoveryBundle.preview(unknown).isFailure)

        val invalidPerson = JSONObject()
            .put("version", 2)
            .put("schema", "shraddha-device-recovery-v2")
            .put("items", JSONObject().put("person_directory", "[{\"id\":\"bad\"}]"))
            .put("memory", "")
            .put("preferences", JSONObject().put("activeProject", "").put("digitalTwin", JSONObject()))
            .toString()
        assertTrue(DeviceRecoveryBundle.preview(invalidPerson).isFailure)
    }
}
