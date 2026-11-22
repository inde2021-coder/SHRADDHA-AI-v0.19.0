package `in`.shraddha.ai.future

/**
 * In-memory scheduling core shared by single tasks and task batches.
 *
 * Priority is ascending (1 is highest). A task is claimable only after every dependency
 * succeeds. The caller owns durable persistence and must restore a validated snapshot after
 * process death; this class deliberately does not pretend to provide persistence.
 */
class SharedTaskScheduler(
    private val maxParallel: Int = 1,
    private val onChanged: ((List<Snapshot>) -> Unit)? = null
) {
    enum class State { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    data class Task(
        val id: String,
        val title: String,
        val dependsOn: Set<String> = emptySet(),
        val priority: Int = 3,
        val payload: String = ""
    )

    data class Snapshot(
        val task: Task,
        val state: State,
        val submittedOrder: Long,
        val error: String? = null
    )

    private val entries = linkedMapOf<String, Snapshot>()
    private var sequence = 0L

    init {
        require(maxParallel in 1..64) { "maxParallel must be between 1 and 64" }
    }

    /** Submit one independent task or a complete dependency-linked batch atomically. */
    @Synchronized
    fun submitAll(tasks: List<Task>): List<Snapshot> {
        require(tasks.isNotEmpty() && tasks.size <= MAX_BATCH) { "Batch must contain 1-$MAX_BATCH tasks" }
        val incomingIds = tasks.map { it.id }
        require(incomingIds.distinct().size == incomingIds.size) { "Duplicate task ID in batch" }
        require(incomingIds.none { entries.containsKey(it) }) { "Task ID already exists" }

        tasks.forEach { task ->
            require(task.id.matches(ID_PATTERN)) { "Invalid task ID: ${task.id}" }
            require(task.title.isNotBlank() && task.title.length <= 160) { "Task title must contain 1-160 characters" }
            require(task.priority in 1..5) { "Priority must be between 1 (highest) and 5 (lowest)" }
            require(task.dependsOn.size <= MAX_DEPENDENCIES) { "Too many dependencies" }
            require(task.id !in task.dependsOn) { "A task cannot depend on itself" }
            require(task.payload.length <= MAX_PAYLOAD_CHARS) { "Task payload is too large" }
        }

        val allIds = entries.keys + incomingIds
        tasks.forEach { task ->
            require(task.dependsOn.all { it in allIds }) { "Unknown dependency for task ${task.id}" }
        }
        validateAcyclic(tasks)

        val created = tasks.map { task ->
            Snapshot(task, State.PENDING, ++sequence).also { entries[task.id] = it }
        }
        notifyChanged()
        return created
    }

    /** Claim up to the configured capacity in priority/FIFO order. */
    @Synchronized
    fun claimReady(): List<Snapshot> {
        val running = entries.values.count { it.state == State.RUNNING }
        val slots = (maxParallel - running).coerceAtLeast(0)
        if (slots == 0) return emptyList()

        val ready = entries.values
            .filter { item ->
                item.state == State.PENDING &&
                    item.task.dependsOn.all { dependency -> entries[dependency]?.state == State.SUCCEEDED }
            }
            .sortedWith(compareBy<Snapshot> { it.task.priority }.thenBy { it.submittedOrder })
            .take(slots)

        val claimed = ready.map { item ->
            item.copy(state = State.RUNNING).also { entries[item.task.id] = it }
        }
        if (claimed.isNotEmpty()) notifyChanged()
        return claimed
    }

    /** Claim one exact task when its dependencies and shared capacity permit execution. */
    @Synchronized
    fun claimSpecific(taskId: String): Snapshot? {
        val current = entries[taskId] ?: return null
        if (current.state != State.PENDING) return null
        if (entries.values.count { it.state == State.RUNNING } >= maxParallel) return null
        val nextReady = entries.values.filter { item ->
            item.state == State.PENDING &&
                item.task.dependsOn.all { dependency -> entries[dependency]?.state == State.SUCCEEDED }
        }.minWithOrNull(compareBy<Snapshot> { it.task.priority }.thenBy { it.submittedOrder })
        if (nextReady?.task?.id != taskId) return null
        return current.copy(state = State.RUNNING).also {
            entries[taskId] = it
            notifyChanged()
        }
    }

    /** Claim the next ready task matching an adapter's task type. */
    @Synchronized
    fun claimReadyMatching(predicate: (Snapshot) -> Boolean): List<Snapshot> {
        val running = entries.values.count { it.state == State.RUNNING }
        val slots = (maxParallel - running).coerceAtLeast(0)
        if (slots == 0) return emptyList()
        val nextReady = entries.values
            .filter { item ->
                item.state == State.PENDING &&
                    item.task.dependsOn.all { dependency -> entries[dependency]?.state == State.SUCCEEDED }
            }
            .sortedWith(compareBy<Snapshot> { it.task.priority }.thenBy { it.submittedOrder })
            .firstOrNull()
        if (nextReady == null || !predicate(nextReady)) return emptyList()
        val claimed = listOf(nextReady).take(slots).map { item ->
            item.copy(state = State.RUNNING).also { entries[item.task.id] = it }
        }
        if (claimed.isNotEmpty()) notifyChanged()
        return claimed
    }

    /** Completes a process-interrupted task only after its adapter proves the last action. */
    @Synchronized
    fun completeInterrupted(taskId: String, verified: Boolean): Snapshot {
        require(verified) { "Interrupted tasks require explicit verified completion evidence" }
        val current = requireTask(taskId)
        require(current.state == State.FAILED &&
            current.error?.startsWith("Process stopped during execution; action was not replayed.") == true) {
            "Only an interrupted failed task can be completed through recovery"
        }
        return current.copy(state = State.SUCCEEDED, error = null).also {
            entries[taskId] = it
            notifyChanged()
        }
    }

    @Synchronized
    fun complete(taskId: String): Snapshot = transition(taskId, State.SUCCEEDED, null)

    @Synchronized
    fun fail(taskId: String, reason: String): Snapshot {
        require(reason.isNotBlank()) { "Failure reason is required" }
        return transition(taskId, State.FAILED, reason.take(500))
    }

    /** Fail a task that was persisted before its adapter claimed it; never auto-execute it. */
    @Synchronized
    fun failPending(taskId: String, reason: String): Snapshot {
        require(reason.isNotBlank()) { "Failure reason is required" }
        val current = requireTask(taskId)
        require(current.state == State.PENDING) { "Only pending tasks can be failed before claim" }
        return current.copy(state = State.FAILED, error = reason.take(500)).also {
            entries[taskId] = it
            notifyChanged()
        }
    }

    /** Cancellation is explicit; running work must also be stopped by its execution adapter. */
    @Synchronized
    fun cancel(taskId: String): Snapshot {
        val current = requireTask(taskId)
        require(current.state == State.PENDING || current.state == State.RUNNING) { "Only pending or running tasks can be cancelled" }
        return current.copy(state = State.CANCELLED).also { entries[taskId] = it; notifyChanged() }
    }

    /**
     * Re-queues only an interrupted task explicitly recognized by its adapter as retry-safe.
     * Device actions and unknown task types remain FAILED and are never replayed.
     */
    @Synchronized
    fun requeueInterrupted(taskId: String, isRetrySafe: (Task) -> Boolean): Snapshot? {
        val current = entries[taskId] ?: return null
        val interrupted = current.state == State.FAILED &&
            current.error?.startsWith("Process stopped during execution; action was not replayed.") == true
        if (!interrupted || !isRetrySafe(current.task)) return null
        return current.copy(state = State.PENDING, error = null).also {
            entries[taskId] = it
            notifyChanged()
        }
    }

    @Synchronized
    fun get(taskId: String): Snapshot? = entries[taskId]

    @Synchronized
    fun snapshot(): List<Snapshot> = entries.values.sortedBy { it.submittedOrder }

    @Synchronized
    fun availableSlots(): Int =
        (maxParallel - entries.values.count { it.state == State.RUNNING }).coerceAtLeast(0)

    /** Restores a validated durable snapshot. RUNNING items are made FAILED, never replayed. */
    @Synchronized
    fun restore(saved: List<Snapshot>) {
        require(saved.size <= MAX_BATCH) { "Persisted scheduler snapshot exceeds the batch limit" }
        require(saved.map { it.task.id }.distinct().size == saved.size) { "Persisted scheduler has duplicate task IDs" }
        val validator = SharedTaskScheduler(maxParallel)
        validator.submitAll(saved.sortedBy { it.submittedOrder }.map { it.task })
        entries.clear()
        sequence = 0L
        saved.sortedBy { it.submittedOrder }.forEach { item ->
            val safe = if (item.state == State.RUNNING) item.copy(
                state = State.FAILED,
                error = "Process stopped during execution; action was not replayed. Review and enqueue again."
            ) else item
            entries[safe.task.id] = safe
            sequence = maxOf(sequence, safe.submittedOrder)
        }
        var propagatedFailure: Boolean
        do {
            propagatedFailure = false
            entries.values.toList().forEach { item ->
                if (item.state == State.PENDING && item.task.dependsOn.any { dependency ->
                        entries[dependency]?.state == State.FAILED || entries[dependency]?.state == State.CANCELLED
                    }) {
                    entries[item.task.id] = item.copy(
                        state = State.FAILED,
                        error = "A dependency did not complete; this task was not resumed."
                    )
                    propagatedFailure = true
                }
            }
        } while (propagatedFailure)
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke(entries.values.sortedBy { it.submittedOrder })
    }

    private fun transition(taskId: String, next: State, error: String?): Snapshot {
        val current = requireTask(taskId)
        require(current.state == State.RUNNING) { "Only a running task can finish" }
        return current.copy(state = next, error = error).also { entries[taskId] = it; notifyChanged() }
    }

    private fun requireTask(taskId: String): Snapshot =
        entries[taskId] ?: throw IllegalArgumentException("Unknown task: $taskId")

    private fun validateAcyclic(incoming: List<Task>) {
        val graph = entries.values.associate { it.task.id to it.task.dependsOn }.toMutableMap()
        incoming.forEach { graph[it.id] = it.dependsOn }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Task dependency cycle detected at $id" }
            graph[id].orEmpty().forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        graph.keys.forEach(::visit)
    }

    companion object {
        private const val MAX_BATCH = 10_000
        private const val MAX_DEPENDENCIES = 256
        private const val MAX_PAYLOAD_CHARS = 32_000
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
