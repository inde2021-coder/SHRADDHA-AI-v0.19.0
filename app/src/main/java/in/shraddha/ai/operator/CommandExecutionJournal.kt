package `in`.shraddha.ai.operator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class CommandStepState { PENDING, RUNNING, SUCCEEDED, FAILED, UNCERTAIN, BLOCKED, SKIPPED }

data class CommandTaskStep(
    val id: String,
    val capability: String,
    val action: String,
    val state: CommandStepState = CommandStepState.PENDING,
    val attempt: Int = 0,
    val resultCode: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class CommandTaskPlan(
    val id: String,
    val commandFingerprint: String,
    val createdAt: Long,
    val steps: List<CommandTaskStep>
) {
    val isTerminal: Boolean
        get() = steps.all { it.state in setOf(
            CommandStepState.SUCCEEDED, CommandStepState.BLOCKED, CommandStepState.SKIPPED
        ) }
}

/**
 * Local durable task journal. It records bounded execution metadata, never raw commands,
 * field values, message bodies, credentials, or screen contents.
 *
 * UNCERTAIN is deliberately non-runnable until an adapter independently reconciles the
 * side effect. This prevents blind duplicate sends/submits after a timeout or process death.
 */
class CommandExecutionJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun createPlan(command: String, steps: List<CommandTaskStep>): CommandTaskPlan? {
        if (command.isBlank() || command.length > MAX_COMMAND_CHARS || steps.isEmpty() || steps.size > MAX_STEPS) return null
        if (steps.any { !validStep(it) } || steps.map { it.id }.distinct().size != steps.size) return null
        val fingerprint = sha256(command.trim())
        val now = System.currentTimeMillis()
        val existing = loadAll().firstOrNull { it.commandFingerprint == fingerprint && now - it.createdAt <= DEDUP_WINDOW_MS }
        if (existing != null) return existing
        val plan = CommandTaskPlan(UUID.randomUUID().toString(), fingerprint, System.currentTimeMillis(),
            steps.map { it.copy(state = CommandStepState.PENDING, attempt = 0, resultCode = "") })
        persist(plan)
        return plan
    }

    @Synchronized
    fun get(planId: String): CommandTaskPlan? = loadAll().firstOrNull { it.id == planId }

    @Synchronized
    fun recoverablePlans(): List<CommandTaskPlan> = loadAll().filter { plan ->
        plan.steps.any { it.state in setOf(CommandStepState.PENDING, CommandStepState.FAILED, CommandStepState.UNCERTAIN, CommandStepState.RUNNING) }
    }

    @Synchronized
    fun markRunning(planId: String, stepId: String, maxAttempts: Int = 2): CommandTaskStep? =
        updateStep(planId, stepId) { step ->
            when (step.state) {
                CommandStepState.PENDING, CommandStepState.FAILED ->
                    if (step.attempt < maxAttempts.coerceIn(1, 3)) step.copy(
                        state = CommandStepState.RUNNING, attempt = step.attempt + 1,
                        resultCode = "", updatedAt = System.currentTimeMillis()
                    ) else null
                // RUNNING/UNCERTAIN must never be replayed automatically.
                else -> null
            }
        }

    @Synchronized
    fun finish(planId: String, stepId: String, state: CommandStepState, resultCode: String): CommandTaskStep? {
        if (state !in setOf(CommandStepState.SUCCEEDED, CommandStepState.FAILED, CommandStepState.UNCERTAIN, CommandStepState.BLOCKED, CommandStepState.SKIPPED)) return null
        return updateStep(planId, stepId) { step ->
            if (step.state != CommandStepState.RUNNING) null
            else step.copy(state = state, resultCode = safeCode(resultCode), updatedAt = System.currentTimeMillis())
        }
    }

    /** Reconcile an uncertain side effect using independent adapter evidence; never replays it. */
    @Synchronized
    fun reconcile(planId: String, stepId: String, verifiedState: CommandStepState, evidenceCode: String): CommandTaskStep? {
        if (verifiedState !in setOf(CommandStepState.SUCCEEDED, CommandStepState.FAILED, CommandStepState.BLOCKED)) return null
        return updateStep(planId, stepId) { step ->
            if (step.state != CommandStepState.UNCERTAIN) null
            else step.copy(state = verifiedState, resultCode = safeCode(evidenceCode), updatedAt = System.currentTimeMillis())
        }
    }

    @Synchronized
    fun cancelPending(planId: String): Boolean {
        val plan = get(planId) ?: return false
        val updated = plan.copy(steps = plan.steps.map { step ->
            if (step.state == CommandStepState.PENDING) step.copy(
                state = CommandStepState.SKIPPED, resultCode = "cancelled", updatedAt = System.currentTimeMillis()
            ) else step
        })
        persist(updated)
        return true
    }

    private fun updateStep(planId: String, stepId: String, transform: (CommandTaskStep) -> CommandTaskStep?): CommandTaskStep? {
        val plan = get(planId) ?: return null
        val index = plan.steps.indexOfFirst { it.id == stepId }
        if (index < 0) return null
        val next = transform(plan.steps[index]) ?: return null
        val steps = plan.steps.toMutableList().also { it[index] = next }
        persist(plan.copy(steps = steps))
        return next
    }

    private fun loadAll(): List<CommandTaskPlan> {
        val raw = prefs.getString(KEY_PLANS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { decode(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun persist(plan: CommandTaskPlan) {
        val all = loadAll().filterNot { it.id == plan.id }.toMutableList().apply { add(plan) }
            .sortedByDescending { it.createdAt }.take(MAX_PLANS).toMutableList()
        var encoded = JSONArray().apply { all.forEach { put(encode(it)) } }.toString()
        while (all.size > 1 && encoded.length > MAX_STORAGE_CHARS) {
            all.removeAt(all.lastIndex)
            encoded = JSONArray().apply { all.forEach { put(encode(it)) } }.toString()
        }
        // Synchronous commit makes each state transition durable before the caller proceeds.
        prefs.edit().putString(KEY_PLANS, encoded).commit()
    }

    private fun encode(plan: CommandTaskPlan) = JSONObject()
        .put("id", plan.id).put("fingerprint", plan.commandFingerprint).put("createdAt", plan.createdAt)
        .put("steps", JSONArray().apply { plan.steps.forEach { s ->
            put(JSONObject().put("id", s.id).put("capability", s.capability).put("action", s.action)
                .put("state", s.state.name).put("attempt", s.attempt)
                .put("resultCode", s.resultCode).put("updatedAt", s.updatedAt))
        } })

    private fun decode(o: JSONObject): CommandTaskPlan {
        val array = o.getJSONArray("steps")
        val steps = (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            CommandTaskStep(s.getString("id"), s.getString("capability"), s.getString("action"),
                CommandStepState.valueOf(s.getString("state")), s.optInt("attempt"),
                s.optString("resultCode"), s.optLong("updatedAt"))
        }
        require(steps.size <= MAX_STEPS && steps.all { validStep(it) })
        return CommandTaskPlan(o.getString("id"), o.getString("fingerprint"), o.getLong("createdAt"), steps)
    }

    private fun validStep(step: CommandTaskStep): Boolean =
        step.id.length in 1..80 && step.capability.length in 1..60 &&
            step.action.length in 1..60 && step.attempt in 0..3 && step.resultCode.length <= 80

    private fun safeCode(value: String): String =
        value.filter { it.isLetterOrDigit() || it in "_-." }.take(80)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS = "shraddha_command_execution_journal"
        private const val KEY_PLANS = "plans_v1"
        private const val MAX_COMMAND_CHARS = 4_000
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_STEPS = 50
        private const val MAX_PLANS = 100
        private const val MAX_STORAGE_CHARS = 256_000
    }
}
