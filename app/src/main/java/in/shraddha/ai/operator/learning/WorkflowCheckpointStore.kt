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
        getResult(packageName, workflowName).getOrNull()

    fun getResult(packageName: String, workflowName: String): Result<WorkflowCheckpoint?> = runCatching {
        val raw = encrypted.get(key(packageName, workflowName)).getOrThrow()
        if (raw.isBlank()) return@runCatching null
        val o = JSONObject(raw)
        val checkpoint = WorkflowCheckpoint(
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
        require(checkpoint.packageName == packageName && checkpoint.workflowName == workflowName) {
            "Workflow checkpoint identity mismatch; saved data was preserved."
        }
        require(WorkflowCheckpointPolicy.isValid(checkpoint)) {
            "Workflow checkpoint fields are invalid; saved data was preserved."
        }
        checkpoint
    }

    fun save(checkpoint: WorkflowCheckpoint): Boolean {
        if (!WorkflowCheckpointPolicy.isValid(checkpoint)) return false
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


/** Shared validation for encrypted checkpoint reads and writes. */
internal object WorkflowCheckpointPolicy {
    private val allowedStates = setOf("READY", "PAUSED", "WAITING_CONFIRMATION", "EXECUTING", "FAILED")

    fun isValid(checkpoint: WorkflowCheckpoint): Boolean {
        return checkpoint.packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) &&
            checkpoint.packageName.length <= 255 &&
            checkpoint.workflowName.isNotBlank() && checkpoint.workflowName.length <= 120 &&
            checkpoint.workflowHash.matches(Regex("[a-fA-F0-9]{64}")) &&
            checkpoint.totalSteps in 1..100 &&
            checkpoint.nextStep in 0..checkpoint.totalSteps &&
            checkpoint.state in allowedStates &&
            checkpoint.expectedText.length <= 300 &&
            checkpoint.beforeScreenFingerprint.length <= 128 &&
            checkpoint.updatedAt > 0L
    }
}
