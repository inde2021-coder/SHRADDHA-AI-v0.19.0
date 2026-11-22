package `in`.shraddha.ai.future

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.Lia.assistant.data.ProviderKeyStore
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.MultiProviderChatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** User-opted-in daily web research. Runs only for saved topics and a configured Perplexity key. */
class DailyBriefingResearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val topics = inputData.getString(KEY_TOPICS).orEmpty().trim().take(1000)
        if (topics.isBlank()) return Result.failure()
        val prefs = applicationContext.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false) || prefs.getString(KEY_SAVED_TOPICS, "").orEmpty() != topics) {
            return Result.failure()
        }
        if (isStopped || !isStillEnabled(prefs, topics)) return Result.failure()
        if (!ProviderKeyStore.has(applicationContext, "PERPLEXITY")) {
            if (isStillEnabled(prefs, topics)) postNotification("Daily research needs setup", "Add a Perplexity API key in AI Provider Settings to enable source-linked morning research.")
            return Result.failure()
        }
        val prompt = """
            Prepare a concise morning research briefing for ${LocalDate.now()}.
            Research only these user-selected topics: $topics
            Use current web research. Give 3–5 useful updates, each with its date and a direct source URL.
            Separate confirmed facts from uncertain reports. Prefer primary/official sources and include source links.
            Do not invent citations, do not infer sensitive personal traits, and do not recommend or execute purchases, messages or other actions.
            Respond in the user's configured language when possible. Keep the briefing under 3000 characters.
        """.trimIndent()
        val response = withContext(Dispatchers.IO) {
            MultiProviderChatClient.reply(
                applicationContext,
                "PERPLEXITY",
                "You are Shraddha AI's source-aware research assistant. Use web search, cite direct URLs, and clearly state uncertainty.",
                listOf(ChatTurn(true, prompt))
            )
        }
        if (isStopped) return Result.failure()
        val answer = when (response) {
            is ChatReplyResult.Success -> response.text.trim()
            is ChatReplyResult.Error -> {
                if (isStillEnabled(prefs, topics)) postNotification("Morning research unavailable", response.message.take(1000))
                return Result.failure()
            }
        }
        if (answer.isBlank()) {
            postNotification("Morning research unavailable", "The research provider returned no answer.")
            return Result.failure()
        }
        if (!prefs.getBoolean(KEY_ENABLED, false) || prefs.getString(KEY_SAVED_TOPICS, "").orEmpty() != topics) {
            return Result.failure()
        }
        postNotification("Shraddha AI • Morning research", answer.take(3500))
        return Result.success()
    }

    private fun isStillEnabled(prefs: android.content.SharedPreferences, topics: String): Boolean =
        prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_SAVED_TOPICS, "").orEmpty() == topics

    private fun postNotification(title: String, body: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily research briefing", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val assistantName = applicationContext.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
            .getString("assistant_name", "Shraddha AI")?.trim()?.take(60)?.ifBlank { "Shraddha AI" } ?: "Shraddha AI"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle(title.replace("Shraddha AI", assistantName))
            .setContentText(body.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_TOPICS = "topics"
        const val KEY_ENABLED = "daily_web_research_enabled"
        const val KEY_SAVED_TOPICS = "daily_research_topics"
        private const val CHANNEL = "shraddha_daily_research"
        private const val NOTIFICATION_ID = 81009
        const val UNIQUE_WORK = "shraddha-daily-web-research"
    }
}
