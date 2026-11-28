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
        val existing = loadAll().firstOrNull {
            val age = now - it.createdAt
            it.commandFingerprint == fingerprint && age in 0..DEDUP_WINDOW_MS
        }
        if (existing != null) {
            val samePlanShape = existing.steps.map { Triple(it.id, it.capability, it.action) } ==
                steps.map { Triple(it.id, it.capability, it.action) }
            // Never reuse a recent plan for a different set/order of actions.
            return if (samePlanShape) existing else null
        }
        val plan = CommandTaskPlan(UUID.randomUUID().toString(), fingerprint, System.currentTimeMillis(),
            steps.map { it.copy(state = CommandStepState.PENDING, attempt = 0, resultCode = "") })
        return if (persist(plan)) plan else null
    }

    @Synchronized
    fun get(planId: String): CommandTaskPlan? = loadAll().firstOrNull { it.id == planId }

    @Synchronized
    fun recoverablePlans(): List<CommandTaskPlan> = loadAll().filter { plan ->
        plan.steps.any { it.state in setOf(CommandStepState.PENDING, CommandStepState.FAILED, CommandStepState.UNCERTAIN, CommandStepState.RUNNING) }
    }

    /**
     * Call once when the app process starts, before any task adapter can resume work.
     * A persisted RUNNING state means the process may have died after an external side
     * effect began. Mark it UNCERTAIN; never assume it is safe to execute again.
     *
     * Returns false if any durable write fails, so startup recovery can remain blocked.
     */
    @Synchronized
    fun recoverInterruptedSteps(): Boolean = runCatching {
        val plans = loadAll()
        for (plan in plans) {
            val recovered = plan.steps.map { step ->
                if (step.state == CommandStepState.RUNNING) step.copy(
                    state = CommandStepState.UNCERTAIN,
                    resultCode = "process_interrupted",
                    updatedAt = System.currentTimeMillis()
                ) else step
            }
            if (recovered != plan.steps && !persist(plan.copy(steps = recovered))) return@runCatching false
        }
        true
    }.getOrDefault(false)

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
        return persist(updated)
    }

    private fun updateStep(planId: String, stepId: String, transform: (CommandTaskStep) -> CommandTaskStep?): CommandTaskStep? {
        val plan = get(planId) ?: return null
        val index = plan.steps.indexOfFirst { it.id == stepId }
        if (index < 0) return null
        val next = transform(plan.steps[index]) ?: return null
        val steps = plan.steps.toMutableList().also { it[index] = next }
        // Never report a transition as accepted unless the new state is durably committed.
        // Callers use a non-null RUNNING claim as the gate before performing side effects.
        if (!persist(plan.copy(steps = steps))) return null
        return next
    }

    private fun loadAll(): List<CommandTaskPlan> {
        val raw = prefs.getString(KEY_PLANS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Command execution journal is corrupted; refusing to resume actions.", error)
        }
        return (0 until array.length()).map { index ->
            try {
                decode(array.getJSONObject(index))
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Command execution journal entry $index is invalid; refusing to resume actions.",
                    error
                )
            }
        }
    }

    private fun persist(plan: CommandTaskPlan): Boolean {
        val combined = loadAll().filterNot { it.id == plan.id } + plan
        // Never evict a non-terminal plan: a RUNNING/UNCERTAIN/PENDING/FAILED record
        // may represent an external side effect that still needs recovery or reconciliation.
        val protected = combined.filterNot { it.isTerminal }
        if (protected.size > MAX_PLANS) return false
        val terminal = combined.filter { it.isTerminal }.sortedByDescending { it.createdAt }
        val all = (protected + terminal.take(MAX_PLANS - protected.size))
            .sortedByDescending { it.createdAt }.toMutableList()
        if (all.none { it.id == plan.id }) return false
        var encoded = JSONArray().apply { all.forEach { put(encode(it)) } }.toString()
        while (encoded.length > MAX_STORAGE_CHARS) {
            val oldestTerminalIndex = all.indexOfLast { it.isTerminal }
            if (oldestTerminalIndex < 0) return false
            all.removeAt(oldestTerminalIndex)
            encoded = JSONArray().apply { all.forEach { put(encode(it)) } }.toString()
        }
        if (all.none { it.id == plan.id }) return false
        // Synchronous commit makes each state transition durable before the caller proceeds.
        return prefs.edit().putString(KEY_PLANS, encoded).commit()
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
                safeCode(s.optString("resultCode")), s.optLong("updatedAt"))
        }
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS && steps.all { validStep(it) })
        require(steps.map { it.id }.distinct().size == steps.size) { "Stored command journal contains duplicate step IDs." }
        val id = o.getString("id")
        val fingerprint = o.getString("fingerprint")
        val createdAt = o.getLong("createdAt")
        require(id.matches(Regex("^[A-Fa-f0-9-]{36}$")))
        require(fingerprint.matches(Regex("^[a-f0-9]{64}$")) && createdAt > 0L)
        return CommandTaskPlan(id, fingerprint, createdAt, steps)
    }

    private fun validStep(step: CommandTaskStep): Boolean =
        safeIdentifier(step.id, 80) && safeIdentifier(step.capability, 60) &&
            safeIdentifier(step.action, 60) &&
            step.attempt in 0..3 && step.resultCode.length <= 80

    private fun safeIdentifier(value: String, maxLength: Int): Boolean =
        value.length in 1..maxLength &&
            value.matches(Regex("^[A-Za-z0-9_.-]+$")) &&
            !Regex("[0-9]{6,}").containsMatchIn(value)

    private fun safeCode(value: String): String {
        val candidate = value.trim()
        if (candidate.isEmpty()) return ""
        return if (candidate.matches(Regex("^[A-Za-z][A-Za-z_-]{0,79}$"))) {
            candidate.lowercase()
        } else {
            "redacted"
        }
    }

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

/** Process-start recovery gate shared by command adapters. */
object CommandExecutionJournalStartup {
    @Volatile private var recovered = false

    fun recover(context: Context): Boolean {
        val success = runCatching {
            CommandExecutionJournal(context.applicationContext).recoverInterruptedSteps()
        }.getOrDefault(false)
        recovered = success
        return success
    }

    fun isReady(): Boolean = recovered
}
