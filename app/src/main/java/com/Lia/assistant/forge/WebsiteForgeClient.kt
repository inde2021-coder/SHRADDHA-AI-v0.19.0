package com.Lia.assistant.forge


import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.Lia.assistant.MainActivity
import `in`.shraddha.ai.R
import `in`.shraddha.ai.future.MainBrainProviderGateway
import kotlinx.coroutines.delay
import java.io.File

object WebsiteForgeClient {

    private const val NOTIFICATION_CHANNEL_ID = "forge_channel"
    private const val NOTIFICATION_ID = 2002

    suspend fun forgeWebsite(
        context: Context,
        prompt: String,
        isEdit: Boolean,
        initialHtml: String?
    ) {
        val systemPrompt = if (isEdit && initialHtml != null) {
            ForgeSystemPrompt.forEdit(initialHtml, prompt)
        } else {
            ForgeSystemPrompt.forNewSite(prompt)
        }

        var attempt = 0
        var success = false
        var currentPromptToSend = systemPrompt

        while (attempt < 2 && !success) {
            attempt++
            try {
                val ok = executeStream(
                    context = context,
                    systemPrompt = currentPromptToSend
                )

                if (ok) {
                    success = true
                } else if (attempt < 2) {
                    delay(4000)
                    currentPromptToSend = "$systemPrompt\n\nNote: Write completely original markup, copy and code; finish the whole page completely from <!DOCTYPE html> to </html>."
                }
            } catch (e: Exception) {
                if (attempt >= 2) {
                    ForgeController.updateState {
                        it.copy(
                            phase = ForgePhase.FAILED,
                            error = "Forge build failed: ${e.localizedMessage ?: "Unknown error"}"
                        )
                    }
                } else {
                    delay(4000)
                }
            }
        }
    }

    private suspend fun executeStream(
        context: Context,
        systemPrompt: String
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var lastStateEmission = 0L
        val accumulatedText = StringBuilder()
        var finalFinishReason: String? = null

        val stream = MainBrainProviderGateway.streamGemini(context, systemPrompt) { chunk ->
            accumulatedText.append(chunk)
            ForgeController.appendChunk(chunk)
            val now = System.currentTimeMillis()
            if (now - lastStateEmission > 120) {
                lastStateEmission = now
                val currentText = accumulatedText.toString()
                val currentStage = ForgeStageDetector.detect(currentText)
                val sections = ForgeStageDetector.extractSectionNames(currentText)
                val elapsed = ((now - startTime) / 1000).toInt()
                val tokensPerSec = if (elapsed > 0) (currentText.length / 4f) / elapsed else 0f
                ForgeController.updateState {
                    it.copy(
                        stage = currentStage,
                        progress = currentStage.progressWeight,
                        metrics = ForgeMetrics(
                            chars = currentText.length,
                            sections = sections.size,
                            elapsedSec = elapsed,
                            tokensPerSec = tokensPerSec
                        ),
                        sections = sections
                    )
                }
            }
        }
        if (stream.httpCode == 503) return false
        if (stream.httpCode !in 200..299) {
            ForgeController.updateState {
                it.copy(phase = ForgePhase.FAILED, error = "Gemini service returned HTTP ${stream.httpCode}")
            }
            return true
        }
        finalFinishReason = stream.finishReason

        var fullHtml = ForgeHtml.stripFences(accumulatedText.toString())

        // Check completeness
        if (fullHtml.length < 1500 || (!fullHtml.contains("</html>", ignoreCase = true) && finalFinishReason == "RECITATION")) {
            return false // trigger retry
        }

        if (!fullHtml.contains("</html>", ignoreCase = true)) {
            if (fullHtml.length >= 14000) {
                fullHtml = ForgeHtml.repairTruncated(fullHtml)
            } else {
                ForgeController.updateState {
                    it.copy(
                        phase = ForgePhase.FAILED,
                        error = "Gemini stopped before finishing the website generation."
                    )
                }
                return true
            }
        }

        // Apply safety net
        fullHtml = ForgeHtml.withSafetyNet(fullHtml)

        // Save generated website
        val dir = File(context.filesDir, "Websites")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "website_${System.currentTimeMillis()}.html")
        file.writeText(fullHtml)

        val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        val sections = ForgeStageDetector.extractSectionNames(fullHtml)

        ForgeController.updateState {
            it.copy(
                phase = ForgePhase.DONE,
                stage = ForgeStage.FINALIZING,
                progress = 1.0f,
                file = file,
                sections = sections,
                metrics = ForgeMetrics(
                    chars = fullHtml.length,
                    sections = sections.size,
                    elapsedSec = totalTime
                ),
                log = it.log + "Website successfully forged!"
            )
        }

        sendNotificationIfBackground(context, file.name)
        return true
    }

    private fun sendNotificationIfBackground(context: Context, filename: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Website Forge",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("EXTRA_OPEN_FORGE", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            200,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Your website is ready!")
            .setContentText("Tap to view and interact with your newly forged website.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notif)
    }
}
