package `in`.shraddha.ai.operator.learning

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import java.security.MessageDigest

/** Encrypted, device-local checkpoints. Only verified step boundaries are resumable. */
data class WorkflowCheckpoint(
    val packageName: String,
    val workflowName: String,
    val workflowHash: String,
    val nextStep: Int,
    val totalSteps: Int,
    val state: String,
    val expectedText: String = "",
    val beforeScreenFingerprint: String = "",
    val expectedTextAbsentBefore: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

class WorkflowCheckpointStore(context: Context) {
    private val encrypted = EncryptedFeatureStore(context.applicationContext)

    fun fingerprint(steps: List<LearnedStep>): String {
        val canonical = steps.joinToString("|") {
            listOf(it.id, it.action, it.targetRole, it.targetLabel, it.inputTemplate, it.expectedText, it.destructive).joinToString("¦")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun get(packageName: String, workflowName: String): WorkflowCheckpoint? =
        encrypted.get(key(packageName, workflowName)).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching {
                val o = JSONObject(raw)
                WorkflowCheckpoint(
                    packageName = o.getString("packageName"),
                    workflowName = o.getString("workflowName"),
                    workflowHash = o.getString("workflowHash"),
                    nextStep = o.getInt("nextStep"),
                    totalSteps = o.getInt("totalSteps"),
                    state = o.getString("state"),
                    expectedText = o.optString("expectedText"),
                    beforeScreenFingerprint = o.optString("beforeScreenFingerprint"),
                    expectedTextAbsentBefore = o.optBoolean("expectedTextAbsentBefore", false),
                    updatedAt = o.optLong("updatedAt")
                )
            }.getOrNull() }

    fun save(checkpoint: WorkflowCheckpoint): Boolean {
        if (checkpoint.packageName.isBlank() || checkpoint.workflowName.isBlank()) return false
        if (checkpoint.nextStep !in 0..checkpoint.totalSteps || checkpoint.totalSteps !in 1..100) return false
        val json = JSONObject()
            .put("packageName", checkpoint.packageName)
            .put("workflowName", checkpoint.workflowName)
            .put("workflowHash", checkpoint.workflowHash)
            .put("nextStep", checkpoint.nextStep)
            .put("totalSteps", checkpoint.totalSteps)
            .put("state", checkpoint.state)
            .put("expectedText", checkpoint.expectedText.take(300))
            .put("beforeScreenFingerprint", checkpoint.beforeScreenFingerprint.take(128))
            .put("expectedTextAbsentBefore", checkpoint.expectedTextAbsentBefore)
            .put("updatedAt", checkpoint.updatedAt)
        return encrypted.put(key(checkpoint.packageName, checkpoint.workflowName), json.toString()).isSuccess
    }

    fun clear(packageName: String, workflowName: String): Boolean =
        encrypted.remove(key(packageName, workflowName))

    private fun key(packageName: String, workflowName: String): String {
        val raw = "$packageName|$workflowName".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            .take(16).joinToString("") { "%02x".format(it) }
        return "workflow_checkpoint_$digest"
    }
}


/** Deterministic, side-effect-free decision for reconciling a process-death checkpoint. */
internal object InterruptedStepRecovery {
    enum class Decision { VERIFIED_COMPLETE, AMBIGUOUS }

    fun assess(expectedText: String, visibleText: Collection<String>, beforeScreenFingerprint: String, currentScreenFingerprint: String, expectedTextAbsentBefore: Boolean): Decision {
        val expected = expectedText.trim()
        if (expected.isBlank() || beforeScreenFingerprint.isBlank() ||
            beforeScreenFingerprint == currentScreenFingerprint || !expectedTextAbsentBefore) return Decision.AMBIGUOUS
        val matched = visibleText.any { visible ->
            visible.contains(expected, ignoreCase = true)
        }
        return if (matched) Decision.VERIFIED_COMPLETE else Decision.AMBIGUOUS
    }
}
