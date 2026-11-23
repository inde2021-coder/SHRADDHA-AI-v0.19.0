package `in`.shraddha.ai.future

import android.content.Context
import org.json.JSONObject

/**
 * Process-wide scheduler shared by the foreground Operator and WorkManager adapters.
 * The encrypted store restores pending work after process death; interrupted RUNNING work
 * is failed closed by the store and must be reviewed before a new attempt.
 */
object MainBrainSchedulerRuntime {
    @Volatile private var scheduler: SharedTaskScheduler? = null
    @Volatile private var persistenceFailure: Throwable? = null

    fun get(context: Context): SharedTaskScheduler = synchronized(this) {
        persistenceFailure?.let { error ->
            throw IllegalStateException(
                "Main Brain scheduler persistence failed; this process is locked against further task execution until restart and recovery.",
                error
            )
        }
        scheduler ?: run {
                val store = MainBrainSchedulerStore(context.applicationContext)
                SharedTaskScheduler(1) { snapshots ->
                    try {
                        store.save(snapshots)
                    } catch (error: Exception) {
                        persistenceFailure = error
                        throw error
                    }
                }.also { created ->
                    store.load().takeIf { it.isNotEmpty() }?.let(created::restore)
                    // These adapters are synchronous/foreground-only. If the process died
                    // before claim, there is no WorkManager worker to recover them.
                    created.snapshot().filter { it.state == SharedTaskScheduler.State.PENDING }.forEach { item ->
                        val kind = runCatching { JSONObject(item.task.payload).optString("kind") }.getOrDefault("")
                        if (kind in setOf("voice_tool", "form_extraction", "website_forge", "learned_workflow")) {
                            created.failPending(
                                item.task.id,
                                "Process stopped before adapter claim; task was not replayed. Start it again explicitly."
                            )
                        }
                    }
                    scheduler = created
                }
            }
        }
}
