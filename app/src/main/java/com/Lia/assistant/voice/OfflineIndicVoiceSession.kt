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
import java.util.concurrent.atomic.AtomicLong

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
    private val sessionEpoch = AtomicLong(0)
    private val segmentLock = Any()
    private var pendingSegment: FloatArray? = null
    private val segmenter = OfflineSpeechSegmenter()
    private var selectedLanguage = IndicConformerRuntime.Language.HINDI
    private var appContext: Context? = null
    private var audio: LiveAudioIO? = null

    fun start(context: Context, language: IndicConformerRuntime.Language) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for offline voice."
        }
        if (!running.compareAndSet(false, true)) return
        val epoch = sessionEpoch.incrementAndGet()
        appContext = context.applicationContext
        selectedLanguage = language
        val status = IndicConformerRuntime.status(context, language)
        if (status.availability != IndicConformerRuntime.Availability.READY) {
            running.set(false)
            onStatus(status.detail)
            return
        }
        audio = LiveAudioIO(
            onMicChunk = { bytes -> acceptPcm16(bytes, epoch) },
            onMicAmplitude = { },
            onRecordingError = { message ->
                stop()
                onError(message)
            }
        )
        onStatus("Offline ${selectedLanguage.label} ready • microphone stays on-device")
        runCatching { audio?.startRecording() }.onFailure {
            stop()
            onError(it.localizedMessage ?: "Offline microphone could not start.")
        }
    }

    private fun acceptPcm16(bytes: ByteArray, epoch: Long) {
        if (!running.get() || sessionEpoch.get() != epoch || bytes.size < 2) return
        val completed = segmenter.accept(bytes, LiveAudioIO.calculateRms(bytes)) ?: return
        val startNow = synchronized(segmentLock) {
            when {
                !running.get() || sessionEpoch.get() != epoch -> false
                decoding.compareAndSet(false, true) -> true
                pendingSegment == null -> { pendingSegment = completed; false }
                else -> { completed.fill(0f); false }
            }
        }
        if (startNow) decodeSegment(completed, epoch, selectedLanguage)
    }

    private fun decodeSegment(pcm: FloatArray, epoch: Long, language: IndicConformerRuntime.Language) {
        val context = appContext ?: run {
            pcm.fill(0f)
            synchronized(segmentLock) { if (sessionEpoch.get() == epoch) decoding.set(false) }
            return
        }
        scope.launch {
            try {
                if (!running.get() || sessionEpoch.get() != epoch) return@launch
                val transcript = IndicConformerRuntime.transcribe(context, language, pcm).trim()
                if (!running.get() || sessionEpoch.get() != epoch) return@launch
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
                    "User voice transcript (" + language.label + "): $transcript\nRespond naturally in the same language. This is local-only; do not claim device actions."
                )
                if (!running.get() || sessionEpoch.get() != epoch) return@launch
                PersistentChatMemory.record(context, "Assistant", answer)
                onBrainResponse(answer)
                onStatus("Offline " + language.label + " • Brain response ready")
            } catch (error: Exception) {
                onError(error.localizedMessage ?: "Offline speech processing failed.")
            } finally {
                pcm.fill(0f)
                val next = synchronized(segmentLock) {
                    if (!running.get() || sessionEpoch.get() != epoch) {
                        if (sessionEpoch.get() == epoch) {
                            pendingSegment?.fill(0f)
                            pendingSegment = null
                            decoding.set(false)
                        }
                        null
                    } else {
                        val queued = pendingSegment
                        pendingSegment = null
                        if (queued == null) decoding.set(false)
                        queued
                    }
                }
                if (next != null) decodeSegment(next, epoch, language)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sessionEpoch.incrementAndGet()
        synchronized(segmentLock) {
            pendingSegment?.fill(0f)
            pendingSegment = null
            decoding.set(false)
        }
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
