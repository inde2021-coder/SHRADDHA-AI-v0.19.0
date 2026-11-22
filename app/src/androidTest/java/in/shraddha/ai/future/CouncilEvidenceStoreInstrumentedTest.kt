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
                providerUsage = "GEMINI:1",
                elapsedMs = 1234L
            ).isSuccess)

            val records = evidence.readTask(taskId).getOrThrow()
            val stage = records.first { it.has("output") }
            val status = records.first { it.has("status") && !it.has("output") }
            assertEquals(sensitiveOutput, stage.getString("output"))
            assertEquals("GEMINI", stage.getString("provider"))
            assertEquals("GEMINI:1", status.getString("providerUsage"))
            assertEquals(1234L, status.getLong("elapsedMs"))

            val prefix = "council_${taskId}_"
            val rawValues = rawStore.getAllWithPrefixes(setOf(prefix)).getOrThrow().values
            assertTrue(rawValues.isNotEmpty())
            assertFalse(rawValues.any { it.contains(sensitiveOutput) })

            assertTrue(evidence.removeTask(taskId))
            assertTrue(evidence.readTask(taskId).getOrThrow().isEmpty())
        } finally {
            evidence.removeTask(taskId)
        }
    }
}
