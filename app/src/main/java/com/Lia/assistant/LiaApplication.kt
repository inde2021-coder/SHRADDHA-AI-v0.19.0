package com.Lia.assistant

import android.app.Application
import in.shraddha.ai.operator.CommandExecutionJournalStartup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Resolve interrupted external actions before voice/tool adapters become available.
        // A corrupt or unwritable journal leaves the shared execution gate closed.
        CommandExecutionJournalStartup.recover(this)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val timestamp = sdf.format(Date())
                val crashDirectory = File(filesDir, "crash-reports")
                if (!crashDirectory.exists()) check(crashDirectory.mkdirs())
                val crashFile = File(crashDirectory, "crash_$timestamp.txt")
                crashFile.writeText(buildString {
                    appendLine("=== Shraddha AI Crash Report ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception type: ${throwable::class.qualifiedName}")
                    // Exception messages can contain URLs, request payloads or credentials.
                    // Store stack frames only; do not persist arbitrary exception text.
                    appendLine("Stack frames:")
                    throwable.stackTrace.take(120).forEach { appendLine("    at $it") }
                    throwable.cause?.let { cause ->
                        appendLine("Caused by type: ${cause::class.qualifiedName}")
                        cause.stackTrace.take(120).forEach { appendLine("    at $it") }
                    }
                })
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
