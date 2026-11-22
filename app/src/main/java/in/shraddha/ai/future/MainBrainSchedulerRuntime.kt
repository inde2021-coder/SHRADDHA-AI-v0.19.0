package `in`.shraddha.ai.future

import android.content.Context

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
                    scheduler = created
                }
            }
        }
    }
}
