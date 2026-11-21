package com.Lia.assistant

import android.app.Application
import `in`.shraddha.ai.link.DeviceLinkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DeviceLinkManager.initialize(this)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val timestamp = sdf.format(Date())
                val crashFile = File(getExternalFilesDir(null), "crash_$timestamp.txt")
                val assistantName = getSharedPreferences("nova_prefs", MODE_PRIVATE)
                    .getString("assistant_name", "Shraddha AI")?.trim()?.take(40).takeUnless { it.isNullOrBlank() } ?: "Shraddha AI"
                crashFile.writeText(buildString {
                    appendLine("=== $assistantName Crash Report ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable::class.qualifiedName}")
                    appendLine("Message: ${throwable.message}")
                    appendLine("Stacktrace:")
                    appendLine(throwable.stackTraceToString())
                    throwable.cause?.let {
                        appendLine("Caused by: ${it::class.qualifiedName}: ${it.message}")
                        appendLine(it.stackTraceToString())
                    }
                })
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
