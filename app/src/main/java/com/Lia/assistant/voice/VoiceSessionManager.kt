package com.Lia.assistant.voice

import android.content.Context
import com.Lia.assistant.action.ActionExecutor
import com.Lia.assistant.action.InstalledAppLabelCache
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.PersonalityRepository
import com.Lia.assistant.forge.ForgeController
import `in`.shraddha.ai.brain.PersistentChatMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object VoiceSessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val sessionEpoch = AtomicLong(0)

    private val _state = MutableStateFlow(NovaOrbState.IDLE)
    val state: StateFlow<NovaOrbState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _statusText = MutableStateFlow("Ready to talk")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _currentPersonality = MutableStateFlow(Personality.NORMAL)
    val currentPersonality: StateFlow<Personality> = _currentPersonality.asStateFlow()

    private val _currentLanguage = MutableStateFlow(LanguagePreference.AUTO)
    val currentLanguage: StateFlow<LanguagePreference> = _currentLanguage.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("UNKNOWN")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val _vadActive = MutableStateFlow(false)
    val vadActive: StateFlow<Boolean> = _vadActive.asStateFlow()

    private var client: GeminiLiveClient? = null
    private var audioIO: LiveAudioIO? = null
    private var appContext: Context? = null

    private var reconnectJob: Job? = null
    private var silenceWatchdogJob: Job? = null
    private var isTurnActive = false

    fun start(context: Context) {
        if (_isActive.value) return
        appContext = context.applicationContext

        _isActive.value = true
        _muted.value = false
        isTurnActive = false
        _lastError.value = null
        _statusText.value = "Connecting..."
        _state.value = NovaOrbState.CONNECTING

        ConversationMemory.clear()
        VoiceLatencyTracker.reset()
        InstalledAppLabelCache.prewarm(context)

        audioIO = LiveAudioIO(
            onMicChunk = { bytes ->
                if (_isActive.value && !_muted.value && !EchoGuard.isMicMuted()) {
                    client?.sendAudio(bytes)
                }
            },
            onMicAmplitude = { amp ->
                if (!_muted.value && !EchoGuard.isMicMuted()) {
                    _amplitude.value = amp
                    _vadActive.value = amp > 0.05f
                }
            },
            onRecordingError = { message ->
                _lastError.value = message
                _vadActive.value = false
                _amplitude.value = 0f
                _isActive.value = false
                sessionEpoch.incrementAndGet()
                reconnectJob?.cancel()
                reconnectJob = null
                client?.disconnect()
                client = null
                _state.value = NovaOrbState.ERROR
                _statusText.value = "Microphone unavailable • tap Start to retry"
                // Stop capture immediately after a fatal AudioRecord error; never leave a
                // dead microphone thread running while the UI still reports an active session.
                audioIO?.release()
                audioIO = null
            }
        )

        scope.launch {
            sessionMutex.withLock {
                connectSession(isResuming = false)
            }
        }
    }

    private suspend fun connectSession(isResuming: Boolean) {
        val currentContext = appContext ?: return
        val currentEpoch = sessionEpoch.incrementAndGet()

        client?.disconnect()
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = if (isResuming) "Reconnecting..." else "Connecting..."

        val apiKey = ApiKeyStore.getKey(currentContext)
        if (apiKey.isEmpty()) {
            _isActive.value = false
            _state.value = NovaOrbState.ERROR
            _statusText.value = "API key missing. Enter key in Settings."
            _lastError.value = "Missing Gemini API Key"
            audioIO?.release()
            audioIO = null
            return
        }

        val personality = PersonalityRepository.getPersonality(currentContext)
        val language = PersonalityRepository.getLanguage(currentContext)
        val assistantName = PersonalityRepository.getAssistantName(currentContext)
        val voiceName = PersonalityRepository.getVoiceName(currentContext)

        _currentPersonality.value = personality
        _currentLanguage.value = language

        val recap = PersistentChatMemory.recap(currentContext) ?: if (isResuming) ConversationMemory.recap() else null
        val prompt = PersonalityPromptBuilder.build(
            personality = personality,
            language = language,
            detectedLanguage = _detectedLanguage.value,
            recap = recap,
            assistantName = assistantName
        )

        val liveListener = object : GeminiLiveClient.Listener {
            override fun onSetupComplete() {
                if (sessionEpoch.get() != currentEpoch) return
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                VoiceLatencyTracker.resetReconnect()
                audioIO?.startRecording()
            }

            override fun onAudioChunk(bytes: ByteArray) {
                if (sessionEpoch.get() != currentEpoch) return
                VoiceLatencyTracker.markFirstAudioChunk()

                if (!isTurnActive) {
                    isTurnActive = true
                    EchoGuard.notifyPlaybackStarted()
                }

                _state.value = NovaOrbState.SPEAKING
                _statusText.value = "$assistantName is speaking..."
                val amp = try {
                    audioIO?.playChunk(bytes) ?: 0f
                } catch (playbackError: Exception) {
                    _lastError.value = playbackError.localizedMessage ?: "Speaker playback failed."
                    _state.value = NovaOrbState.ERROR
                    _statusText.value = "Speaker unavailable • reconnecting"
                    scheduleReconnect()
                    return
                }
                _amplitude.value = amp

                // Re-arm 300 ms silence watchdog
                silenceWatchdogJob?.cancel()
                silenceWatchdogJob = scope.launch {
                    delay(300)
                    if (sessionEpoch.get() == currentEpoch && isTurnActive) {
                        isTurnActive = false
                        EchoGuard.notifyPlaybackStopped()
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                        _amplitude.value = 0f
                    }
                }
            }

            override fun onInputTranscript(text: String) {
                if (sessionEpoch.get() != currentEpoch) return
                VoiceLatencyTracker.markUserTurnStart()
                ConversationMemory.recordTurn(MemoryRole.USER, text)
                scope.launch { PersistentChatMemory.record(currentContext, "User", text) }
                val detected = LanguageDetector.detect(text)
                if (detected != "UNKNOWN") {
                    _detectedLanguage.value = detected
                }
            }

            override fun onOutputTranscript(text: String) {
                if (sessionEpoch.get() != currentEpoch) return
                ConversationMemory.recordTurn(MemoryRole.ASSISTANT, text)
                scope.launch { PersistentChatMemory.record(currentContext, "Assistant", text) }
            }

            override fun onToolCall(id: String, name: String, args: JSONObject) {
                if (sessionEpoch.get() != currentEpoch) return
                _state.value = NovaOrbState.THINKING
                _statusText.value = "Working on it..."

                if (name == "build_website") {
                    val response = runCatching {
                        ForgeController.start(currentContext, args.optString("prompt", ""))
                        JSONObject().put("result", "forge_started").put("success", true)
                    }.getOrElse { error ->
                        JSONObject().put("success", false).put("error", (error.localizedMessage ?: "Website build could not start").take(300))
                    }
                    client?.sendToolResponse(id, name, response)
                    _state.value = NovaOrbState.LISTENING
                    _statusText.value = "Listening..."
                    return
                }

                scope.launch {
                    val result = runCatching { ActionExecutor.execute(currentContext, name, args) }
                        .getOrElse { error ->
                            JSONObject().put("success", false)
                                .put("error", (error.localizedMessage ?: "Action failed").take(300))
                        }
                    if (sessionEpoch.get() == currentEpoch) {
                        client?.sendToolResponse(id, name, result)
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                    }
                }
            }

            override fun onTurnComplete() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                if (isTurnActive) {
                    isTurnActive = false
                    EchoGuard.notifyPlaybackStopped()
                }
                audioIO?.flushPlayback()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                _amplitude.value = 0f
                VoiceLatencyTracker.markTurnComplete()
            }

            override fun onInterrupted() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                if (isTurnActive) {
                    isTurnActive = false
                    EchoGuard.notifyPlaybackStopped()
                }
                audioIO?.flushPlayback()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                _amplitude.value = 0f
            }

            override fun onError(message: String) {
                if (sessionEpoch.get() != currentEpoch) return
                _lastError.value = message
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }

            override fun onClosed() {
                if (sessionEpoch.get() != currentEpoch) return
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }
        }

        client = GeminiLiveClient(liveListener)
        try {
            client?.connect(apiKey, prompt, voiceName)
        } catch (error: Exception) {
            _lastError.value = error.localizedMessage ?: "Voice connection could not start."
            _statusText.value = "Voice connection failed • retrying"
            if (_isActive.value) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!_isActive.value || reconnectJob?.isActive == true) return

        val count = VoiceLatencyTracker.reconnectCount.value
        VoiceLatencyTracker.incrementReconnect()
        // Never collect microphone audio while disconnected. Keep the user-started
        // session alive and reconnect automatically with bounded exponential backoff.
        audioIO?.stopRecording()
        _amplitude.value = 0f
        _vadActive.value = false
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = "Connection lost • microphone paused • reconnecting (attempt ${count + 1})…"

        reconnectJob = scope.launch {
            delay(VoiceLatencyTracker.reconnectDelayMs(count))
            sessionMutex.withLock {
                if (_isActive.value) connectSession(isResuming = true)
            }
        }
    }

    /** Manual recovery for missing credentials or a fatal local microphone error. */
    fun retry(context: Context) {
        appContext = context.applicationContext
        if (!_isActive.value) {
            start(context)
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        audioIO?.stopRecording()
        VoiceLatencyTracker.resetReconnect()
        _lastError.value = null
        _amplitude.value = 0f
        _vadActive.value = false
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = "Retrying voice connection…"
        scope.launch {
            sessionMutex.withLock {
                if (_isActive.value) connectSession(isResuming = true)
            }
        }
    }

    fun refreshInstructions(context: Context) {
        if (!_isActive.value) return
        appContext = context.applicationContext
        scope.launch {
            sessionMutex.withLock {
                if (_isActive.value) {
                    connectSession(isResuming = true)
                }
            }
        }
    }

    fun clearConversationMemory(context: Context): Boolean {
        ConversationMemory.clear()
        val cleared = PersistentChatMemory.clear(context)
        if (_isActive.value) refreshInstructions(context)
        return cleared
    }

    fun toggleMute() {
        val next = !_muted.value
        _muted.value = next
        _statusText.value = if (next) "Microphone muted" else "Listening..."
    }

    fun stop() {
        val preserveError = _state.value == NovaOrbState.ERROR
        val previousError = _lastError.value
        val previousStatus = _statusText.value
        _isActive.value = false
        sessionEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        VoiceLatencyTracker.resetReconnect()
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

        client?.disconnect()
        client = null

        audioIO?.release()
        audioIO = null

        EchoGuard.reset()
        ConversationMemory.clear()

        isTurnActive = false
        _state.value = if (preserveError) NovaOrbState.ERROR else NovaOrbState.IDLE
        _statusText.value = if (preserveError) previousStatus else "Ready to talk"
        _amplitude.value = 0f
        _vadActive.value = false
        _muted.value = false
        _lastError.value = if (preserveError) previousError else null
    }
}
