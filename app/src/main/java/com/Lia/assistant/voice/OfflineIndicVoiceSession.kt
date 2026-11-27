package com.Lia.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import `in`.shraddha.ai.brain.LocalOfflineLlm
import `in`.shraddha.ai.brain.PersistentChatMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local-only Indic microphone pipeline; no network client or cloud fallback exists here. */
class OfflineIndicVoiceSession(
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onBrainResponse: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MODEL_PREFS = "shraddha_offline_model"
        private const val MODEL_PATH_KEY = "active_model_path"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val decoding = AtomicBoolean(false)
    private val segmenter = OfflineSpeechSegmenter()
    private var selectedLanguage = IndicConformerRuntime.Language.HINDI
    private var appContext: Context? = null
    private var audio: LiveAudioIO? = null

    fun start(context: Context, language: IndicConformerRuntime.Language) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for offline voice."
        }
        if (!running.compareAndSet(false, true)) return
        appContext = context.applicationContext
        selectedLanguage = language
        val status = IndicConformerRuntime.status(context, language)
        if (status.availability != IndicConformerRuntime.Availability.READY) {
            running.set(false)
            onStatus(status.detail)
            return
        }
        audio = LiveAudioIO(
            onMicChunk = ::acceptPcm16,
            onMicAmplitude = { },
            onRecordingError = { message ->
                stop()
                onError(message)
            }
        )
        onStatus("Offline ${selectedLanguage.label} ready • microphone stays on-device")
        runCatching { audio?.startRecording() }.onFailure {
            running.set(false)
            onError(it.localizedMessage ?: "Offline microphone could not start.")
        }
    }

    private fun acceptPcm16(bytes: ByteArray) {
        if (!running.get() || decoding.get() || bytes.size < 2) return
        val completed = segmenter.accept(bytes, LiveAudioIO.calculateRms(bytes))
        if (completed != null && decoding.compareAndSet(false, true)) decodeSegment(completed)
    }

    private fun decodeSegment(pcm: FloatArray) {
        val context = appContext ?: run {
            pcm.fill(0f)
            decoding.set(false)
            return
        }
        scope.launch {
            try {
                val transcript = IndicConformerRuntime.transcribe(context, selectedLanguage, pcm).trim()
                if (transcript.isBlank()) {
                    onStatus("No speech recognized • keep speaking")
                    return@launch
                }
                onTranscript(transcript)
                PersistentChatMemory.record(context, "User", transcript)
                val modelPath = context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
                    .getString(MODEL_PATH_KEY, "").orEmpty()
                if (modelPath.isBlank() || !File(modelPath).isFile) {
                    onStatus("Transcript recognized locally. Import an Offline AI .litertlm model for a Shraddha Brain response.")
                    return@launch
                }
                val answer = LocalOfflineLlm.generate(
                    context,
                    modelPath,
                    "User voice transcript (${selectedLanguage.label}): $transcript\nRespond naturally in the same language. This is local-only; do not claim device actions."
                )
                PersistentChatMemory.record(context, "Assistant", answer)
                onBrainResponse(answer)
                onStatus("Offline ${selectedLanguage.label} • Brain response ready")
            } catch (error: Exception) {
                onError(error.localizedMessage ?: "Offline speech processing failed.")
            } finally {
                pcm.fill(0f)
                decoding.set(false)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audio?.release() }
        audio = null
        segmenter.reset()
        onStatus("Offline microphone stopped")
    }

    fun release() {
        stop()
        scope.cancel()
        appContext = null
    }
}
