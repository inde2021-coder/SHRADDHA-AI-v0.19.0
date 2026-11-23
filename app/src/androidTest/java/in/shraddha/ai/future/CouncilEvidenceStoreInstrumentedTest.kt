package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CouncilEvidenceStoreInstrumentedTest {
    @Test fun stageOutputsAndProviderUsageArePersistedEncryptedAndCanBeDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val taskId = "bg_" + UUID.randomUUID().toString().replace("-", "")
        val evidence = CouncilEvidenceStore(context)
        val rawStore = EncryptedFeatureStore(context)
        val sensitiveOutput = "private council output for encrypted evidence test"
        try {
            assertTrue(evidence.saveStage(
                taskId = taskId,
                goal = "Verify durable stage evidence",
                role = "Planner",
                provider = "GEMINI",
                output = sensitiveOutput,
                attempt = 1
            ).isSuccess)
            assertTrue(evidence.saveStatus(
                taskId = taskId,
                status = "RUNNING",
                message = "Provider request recorded",
                attempt = 1,
                providerUsage = """{"calls":"GEMINI:1","tokens":[{"provider":"GEMINI","model":"gemini-test","inputTokens":120,"outputTokens":45}]}""",
                elapsedMs = 1234L
            ).isSuccess)

            // A later progress update must not erase token evidence already recorded.
            assertTrue(evidence.saveStatus(
                taskId = taskId,
                status = "RUNNING",
                message = "Next stage started",
                attempt = 1
            ).isSuccess)
            val records = evidence.readTask(taskId).getOrThrow()
            val stage = records.first { it.has("output") }
            val status = records.first { it.has("status") && !it.has("output") }
            assertEquals(sensitiveOutput, stage.getString("output"))
            assertEquals("GEMINI", stage.getString("provider"))
            val usage = org.json.JSONObject(status.getString("providerUsage"))
            assertEquals("GEMINI:1", usage.getString("calls"))
            val token = usage.getJSONArray("tokens").getJSONObject(0)
            assertEquals("gemini-test", token.getString("model"))
            assertEquals(120, token.getInt("inputTokens"))
            assertEquals(45, token.getInt("outputTokens"))
            assertEquals(1234L, status.getLong("elapsedMs"))

            // getAllWithPrefixes() intentionally decrypts values. Inspect the underlying
            // SharedPreferences payload to verify ciphertext-at-rest instead.
            val prefix = "council_${taskId}_"
            val encryptedValues = context.getSharedPreferences(
                "shraddha_feature_store", Context.MODE_PRIVATE
            ).all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { it as? String }
            assertTrue(encryptedValues.isNotEmpty())
            assertFalse(encryptedValues.any { it.contains(sensitiveOutput) })
            assertTrue(encryptedValues.all { it.contains(".") })

            assertTrue(evidence.removeTask(taskId))
            assertTrue(evidence.readTask(taskId).getOrThrow().isEmpty())
        } finally {
            evidence.removeTask(taskId)
        }
    }
}
