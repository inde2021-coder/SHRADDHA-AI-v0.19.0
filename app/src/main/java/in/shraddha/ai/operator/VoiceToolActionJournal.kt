package `in`.shraddha.ai.operator

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject

/**
 * Write-ahead journal for voice-triggered device actions which do not pass through
 * the typed Operator bridge (calls, messaging, downloads and file-manager handoffs).
 * Arguments and message bodies are deliberately never persisted.
 */
class VoiceToolActionJournal(context: Context) {
    private val store = EncryptedFeatureStore(context.applicationContext)
    private val key = "voice_tool_action_journal_latest"

    data class Entry(
        val taskId: String,
        val tool: String,
        val state: String,
        val action: String,
        val resultStatus: String,
        val updatedAt: Long
    )

    @Synchronized
    fun begin(taskId: String, tool: String): Boolean {
        if (!taskId.matches(Regex("voice_[A-Fa-f0-9]{32}"))) return false
        if (!tool.matches(Regex("[a-zA-Z0-9_]{1,80}"))) return false
        // Never overwrite an unresolved action from a previous process/session.
        val previous = readResult().getOrElse { return false }
        if (previous?.state == "EXECUTING") return false
        val payload = JSONObject()
            .put("task_id", taskId)
            .put("tool", tool)
            .put("state", "EXECUTING")
            .put("action", "")
            .put("result_status", "")
            .put("updated_at", System.currentTimeMillis())
        return store.put(key, payload.toString()).isSuccess
    }

    @Synchronized
    fun finish(taskId: String, result: JSONObject): Boolean {
        val current = readResult().getOrNull() ?: return false
        if (current.taskId != taskId || current.state != "EXECUTING") return false
        val status = result.optString("status", "unknown").take(40)
        val action = result.optString("action", "").take(80)
        val completed = JSONObject()
            .put("task_id", current.taskId)
            .put("tool", current.tool)
            .put("state", if (status in setOf("success", "queued", "needs_user_action", "confirmation_required", "needs_clarification")) "RESULT_RECORDED" else "FAILED")
            .put("action", action)
            .put("result_status", status)
            .put("updated_at", System.currentTimeMillis())
        return store.put(key, completed.toString()).isSuccess
    }

    /** Explicit user attestation after inspecting the target app; this never replays the action. */
    @Synchronized
    fun markManuallyVerified(taskId: String): Boolean {
        val current = read() ?: return false
        if (current.taskId != taskId || current.state != "EXECUTING") return false
        val verified = JSONObject()
            .put("task_id", current.taskId)
            .put("tool", current.tool)
            .put("state", "MANUALLY_VERIFIED")
            .put("action", current.action)
            .put("result_status", "user_checked_target_app")
            .put("updated_at", System.currentTimeMillis())
        return store.put(key, verified.toString()).isSuccess
    }

    @Synchronized
    fun latest(): Entry? = readResult().getOrNull()

    private fun readResult(): Result<Entry?> = runCatching {
        val raw = store.get(key).getOrThrow().orEmpty()
        if (raw.isBlank()) return@runCatching null
        val json = JSONObject(raw)
        val taskId = json.getString("task_id")
        val tool = json.getString("tool")
        val state = json.getString("state")
        require(taskId.matches(Regex("voice_[A-Fa-f0-9]{32}"))) { "Stored voice action journal task ID is invalid." }
        require(tool.matches(Regex("[a-zA-Z0-9_]{1,80}"))) { "Stored voice action journal tool is invalid." }
        require(state in setOf("EXECUTING", "RESULT_RECORDED", "FAILED", "MANUALLY_VERIFIED")) {
            "Stored voice action journal state is invalid."
        }
        Entry(
            taskId = taskId,
            tool = tool,
            state = state,
            action = json.optString("action"),
            resultStatus = json.optString("result_status"),
            updatedAt = json.optLong("updated_at")
        )
    }
}
