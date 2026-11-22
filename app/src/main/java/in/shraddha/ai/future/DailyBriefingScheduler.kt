package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Opt-in daily research schedule. The worker re-checks consent and the saved topics before every run. */
object DailyBriefingScheduler {
    data class ScheduleResult(val enabled: Boolean, val topics: String, val hour: Int, val minute: Int, val initialDelayMillis: Long)

    fun schedule(context: Context, topicsInput: String, hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Result<ScheduleResult> = runCatching {
        val topics = topicsInput.trim().replace(Regex("\\s+"), " ").take(1000)
        require(topics.isNotBlank()) { "कम से कम एक briefing topic दें।" }
        require(hour in 0..23 && minute in 0..59) { "समय 24-hour HH:MM format में दें।" }
        val delay = initialDelayMillis(now, hour, minute)
        val prefs = context.applicationContext.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean(DailyBriefingResearchWorker.KEY_ENABLED, true)
            .putString(DailyBriefingResearchWorker.KEY_SAVED_TOPICS, topics).commit()) { "Daily briefing settings save नहीं हुईं।" }
        val request = PeriodicWorkRequestBuilder<DailyBriefingResearchWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DailyBriefingResearchWorker.KEY_TOPICS to topics))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(DailyBriefingResearchWorker.UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            DailyBriefingResearchWorker.UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
        ScheduleResult(true, topics, hour, minute, delay)
    }

    fun cancel(context: Context): Result<Unit> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean(DailyBriefingResearchWorker.KEY_ENABLED, false).commit()) { "Daily briefing बंद करने की setting save नहीं हुई।" }
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(DailyBriefingResearchWorker.UNIQUE_WORK)
    }

    fun initialDelayMillis(now: ZonedDateTime, hour: Int, minute: Int): Long {
        require(hour in 0..23 && minute in 0..59)
        var target = now.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return java.time.Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }
}