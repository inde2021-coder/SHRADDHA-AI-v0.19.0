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

    fun get(context: Context): SharedTaskScheduler {
        scheduler?.let { return it }
        return synchronized(this) {
            scheduler ?: run {
                val store = MainBrainSchedulerStore(context.applicationContext)
                SharedTaskScheduler(1) { snapshots -> store.save(snapshots) }.also { created ->
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
}
