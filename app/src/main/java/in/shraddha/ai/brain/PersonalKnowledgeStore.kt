package `in`.shraddha.ai.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, encrypted local persistence for explicit user-authored life events, approved skill
 * examples and routine proposals. This store does not ingest sensors, record conversations,
 * execute routines or sync to cloud. Those require separate consented adapters.
 */
class PersonalKnowledgeStore(context: Context) {
    data class TimelineEvent(
        val id: String,
        val title: String,
        val category: String,
        val timestamp: Long,
        val note: String = ""
    )

    data class SkillExample(
        val id: String,
        val skill: String,
        val input: String,
        val approvedOutput: String,
        val approvedAt: Long
    )

    data class Routine(
        val id: String,
        val title: String,
        val trigger: String,
        val actionSummary: String,
        val enabled: Boolean,
        val requiresApproval: Boolean = true,
        val updatedAt: Long = System.currentTimeMillis()
    )

    data class RoutineProposal(val routineId: String, val summary: String, val requiresApproval: Boolean)

    private val store = EncryptedFeatureStore(context.applicationContext)

    @Synchronized
    fun addTimelineEvent(event: TimelineEvent): Result<Unit> = runCatching {
        validateEvent(event)
        val all = readEvents().associateBy { it.id }.toMutableMap()
        all[event.id] = event.copy(
            title = event.title.trim().take(120),
            category = event.category.trim().take(40),
            note = event.note.take(1_000)
        )
        require(all.size <= MAX_EVENTS) { "Timeline limit reached; delete older events before adding more." }
        store.put(EVENTS_KEY, encodeEvents(all.values.sortedByDescending { it.timestamp })).getOrThrow()
    }

    @Synchronized
    fun searchTimeline(query: String, from: Long? = null, to: Long? = null): Result<List<TimelineEvent>> = runCatching {
        require(query.length <= 200 && (from == null || to == null || from <= to))
        readEvents().asSequence()
            .filter { from == null || it.timestamp >= from }
            .filter { to == null || it.timestamp <= to }
            .filter { query.isBlank() || it.title.contains(query, true) ||
                it.category.contains(query, true) || it.note.contains(query, true) }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    @Synchronized
    fun deleteTimelineEvent(id: String): Result<Boolean> = runCatching {
        require(validId(id))
        val all = readEvents().filterNot { it.id == id }
        if (all.size == readEvents().size) return@runCatching false
        store.put(EVENTS_KEY, encodeEvents(all)).getOrThrow()
        true
    }

    @Synchronized
    fun addApprovedSkillExample(
        consent: Boolean,
        example: SkillExample
    ): Result<Unit> = runCatching {
        require(consent) { "Explicit learning consent is required before saving an example." }
        validateSkill(example)
        val all = readSkills().associateBy { it.id }.toMutableMap()
        all[example.id] = example.copy(
            skill = example.skill.trim().take(60),
            input = example.input.take(2_000),
            approvedOutput = example.approvedOutput.take(4_000)
        )
        require(all.size <= MAX_SKILL_EXAMPLES) { "Personal skill example limit reached." }
        store.put(SKILLS_KEY, encodeSkills(all.values.sortedByDescending { it.approvedAt })).getOrThrow()
    }

    @Synchronized
    fun skillExamples(skill: String): Result<List<SkillExample>> = runCatching {
        require(skill.isNotBlank() && skill.length <= 60)
        readSkills().filter { it.skill.equals(skill.trim(), true) }.sortedByDescending { it.approvedAt }
    }

    @Synchronized
    fun forgetSkill(skill: String): Result<Int> = runCatching {
        require(skill.isNotBlank() && skill.length <= 60)
        val all = readSkills()
        val remaining = all.filterNot { it.skill.equals(skill.trim(), true) }
        val removed = all.size - remaining.size
        store.put(SKILLS_KEY, encodeSkills(remaining)).getOrThrow()
        removed
    }

    @Synchronized
    fun saveRoutine(routine: Routine): Result<Unit> = runCatching {
        validateRoutine(routine)
        val all = readRoutines().associateBy { it.id }.toMutableMap()
        all[routine.id] = routine.copy(
            title = routine.title.trim().take(120),
            trigger = routine.trigger.trim().take(200),
            actionSummary = routine.actionSummary.trim().take(1_000)
        )
        require(all.size <= MAX_ROUTINES) { "Routine limit reached." }
        store.put(ROUTINES_KEY, encodeRoutines(all.values.sortedBy { it.id })).getOrThrow()
    }

    @Synchronized
    fun routines(): Result<List<Routine>> = runCatching { readRoutines().sortedBy { it.title.lowercase() } }

    /** Returns a proposal only. It never executes the routine or bypasses fresh action approval. */
    @Synchronized
    fun proposeRoutine(routineId: String, observedTrigger: String): Result<RoutineProposal?> = runCatching {
        require(validId(routineId) && observedTrigger.length <= 200)
        val routine = readRoutines().firstOrNull { it.id == routineId } ?: return@runCatching null
        if (!routine.enabled || !routine.trigger.equals(observedTrigger.trim(), true)) return@runCatching null
        RoutineProposal(routine.id, routine.actionSummary, routine.requiresApproval)
    }

    @Synchronized
    fun deleteRoutine(id: String): Result<Boolean> = runCatching {
        require(validId(id))
        val all = readRoutines()
        val remaining = all.filterNot { it.id == id }
        if (remaining.size == all.size) return@runCatching false
        store.put(ROUTINES_KEY, encodeRoutines(remaining)).getOrThrow()
        true
    }

    @Synchronized
    fun clearAll(): Result<Unit> = runCatching {
        check(store.putAll(mapOf(EVENTS_KEY to "[]", SKILLS_KEY to "[]", ROUTINES_KEY to "[]")).isSuccess) {
            "Could not clear encrypted personal knowledge records."
        }
    }

    private fun readEvents(): List<TimelineEvent> = parseArray(EVENTS_KEY).map { o ->
        TimelineEvent(o.getString("id"), o.getString("title"), o.getString("category"),
            o.getLong("timestamp"), o.optString("note"))
    }

    private fun readSkills(): List<SkillExample> = parseArray(SKILLS_KEY).map { o ->
        SkillExample(o.getString("id"), o.getString("skill"), o.getString("input"),
            o.getString("approvedOutput"), o.getLong("approvedAt"))
    }

    private fun readRoutines(): List<Routine> = parseArray(ROUTINES_KEY).map { o ->
        Routine(o.getString("id"), o.getString("title"), o.getString("trigger"),
            o.getString("actionSummary"), o.getBoolean("enabled"),
            o.optBoolean("requiresApproval", true), o.optLong("updatedAt"))
    }

    private fun parseArray(key: String): List<JSONObject> {
        val raw = store.get(key).getOrThrow()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        require(array.length() <= MAX_RECORDS) { "Personal knowledge record limit exceeded." }
        return (0 until array.length()).map { index ->
            array.getJSONObject(index) ?: error("Malformed personal knowledge record.")
        }
    }

    private fun encodeEvents(items: Collection<TimelineEvent>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("title", it.title)
            .put("category", it.category).put("timestamp", it.timestamp).put("note", it.note)) }
    }.toString()

    private fun encodeSkills(items: Collection<SkillExample>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("skill", it.skill).put("input", it.input)
            .put("approvedOutput", it.approvedOutput).put("approvedAt", it.approvedAt)) }
    }.toString()

    private fun encodeRoutines(items: Collection<Routine>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("trigger", it.trigger)
            .put("actionSummary", it.actionSummary).put("enabled", it.enabled)
            .put("requiresApproval", it.requiresApproval).put("updatedAt", it.updatedAt)) }
    }.toString()

    private fun validateEvent(e: TimelineEvent) {
        require(validId(e.id) && e.title.isNotBlank() && e.title.length <= 120)
        require(e.category.isNotBlank() && e.category.length <= 40 && e.note.length <= 1_000)
    }

    private fun validateSkill(e: SkillExample) {
        require(validId(e.id) && e.skill.isNotBlank() && e.skill.length <= 60)
        require(e.input.isNotBlank() && e.input.length <= 2_000)
        require(e.approvedOutput.isNotBlank() && e.approvedOutput.length <= 4_000)
    }

    private fun validateRoutine(r: Routine) {
        require(validId(r.id) && r.title.isNotBlank() && r.title.length <= 120)
        require(r.trigger.isNotBlank() && r.trigger.length <= 200)
        require(r.actionSummary.isNotBlank() && r.actionSummary.length <= 1_000)
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))

    companion object {
        private const val EVENTS_KEY = "personal_timeline_v1"
        private const val SKILLS_KEY = "personal_skills_v1"
        private const val ROUTINES_KEY = "personal_routines_v1"
        private const val MAX_EVENTS = 1_000
        private const val MAX_SKILL_EXAMPLES = 5_000
        private const val MAX_ROUTINES = 500
        private const val MAX_RECORDS = 5_000
    }
}
