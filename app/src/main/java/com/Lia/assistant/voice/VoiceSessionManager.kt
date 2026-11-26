package com.Lia.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.Lia.assistant.action.InstalledAppLabelCache
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.GatewayRequestException
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.PersonalityRepository
import `in`.shraddha.ai.brain.PersistentChatMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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
    private val clientLock = Any()
    private val toolJobsLock = Any()
    private val toolJobs = mutableSetOf<Job>()
    private val sessionEpoch = AtomicLong(0)
    private val audioEpoch = AtomicLong(0)

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

    @Volatile private var client: GeminiLiveClient? = null
    @Volatile private var audioIO: LiveAudioIO? = null
    private var appContext: Context? = null

    private var reconnectJob: Job? = null
    private var silenceWatchdogJob: Job? = null
    private var isTurnActive = false
    @Volatile private var bargeInSpeechChunks = 0
    @Volatile private var bargeInOpen = false
    private val pendingAssistantText = StringBuilder()
    private val pendingTextLock = Any()

    private const val BARGE_IN_RMS_THRESHOLD = 0.105f
    private const val BARGE_IN_REQUIRED_CHUNKS = 4

    fun isCurrentSession(epoch: Long): Boolean = sessionEpoch.get() == epoch
    fun isActiveNow(): Boolean = _isActive.value

    fun start(context: Context) {
        if (_isActive.value) return
        val application = context.applicationContext
        if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(application)) {
            appContext = application
            _lastError.value = "Online AI is disabled. Enable Allow Online AI in Settings > Connections before starting cloud voice."
            _statusText.value = "Enable Online AI in Settings"
            _state.value = NovaOrbState.ERROR
            _isActive.value = false
            _muted.value = true
            return
        }
        if (ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appContext = application
            _lastError.value = "Microphone permission is required before starting a voice session."
            _statusText.value = "Microphone permission required"
            _state.value = NovaOrbState.ERROR
            _isActive.value = false
            _muted.value = true
            return
        }
        appContext = application

        _isActive.value = true
        _muted.value = false
        isTurnActive = false
        _lastError.value = null
        _statusText.value = "Connecting..."
        _state.value = NovaOrbState.CONNECTING
        val currentAudioEpoch = audioEpoch.incrementAndGet()
        val startEpoch = sessionEpoch.incrementAndGet()

        ConversationMemory.initialize(appContext ?: context.applicationContext)
        VoiceLatencyTracker.reset()
        InstalledAppLabelCache.prewarm(context)

        audioIO = LiveAudioIO(
            onMicChunk = { bytes ->
                if (_isActive.value && audioEpoch.get() == currentAudioEpoch && !_muted.value &&
                    (!EchoGuard.isMicMuted() || bargeInOpen)) {
                    client?.sendAudio(bytes)
                }
            },
            onMicAmplitude = { amp ->
                if (_isActive.value && audioEpoch.get() == currentAudioEpoch && !_muted.value) {
                    _amplitude.value = amp
                    _vadActive.value = amp > 0.05f
                    if (EchoGuard.isMicMuted()) {
                        // AEC/NS/AGC are enabled by LiveAudioIO. Require sustained foreground
                        // speech energy before opening the mic during assistant playback.
                        bargeInSpeechChunks = if (amp >= BARGE_IN_RMS_THRESHOLD) bargeInSpeechChunks + 1 else 0
                        if (bargeInSpeechChunks >= BARGE_IN_REQUIRED_CHUNKS) {
                            bargeInOpen = true
                        }
                    } else {
                        bargeInSpeechChunks = 0
                        bargeInOpen = false
                    }
                }
            },
            onRecordingError = { message ->
                scope.launch {
                    if (_isActive.value && audioEpoch.get() == currentAudioEpoch) {
                        _lastError.value = message
                        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
                            appContext ?: context.applicationContext,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasMicrophonePermission) {
                            // Permission revocation is a user decision, not a transient
                            // audio failure. Do not retry or reopen the microphone.
                            _state.value = NovaOrbState.ERROR
                            _statusText.value = "Microphone permission was revoked. Re-enable it to continue."
                            // Also remove the foreground notification: it must not claim
                            // that a live microphone session is active after permission loss.
                            VoiceForegroundService.stop(appContext ?: context.applicationContext)
                        } else {
                            // AudioRecord can fail after a successful Live API connection
                            // (device contention, route changes or a transient read error).
                            // Reuse the bounded reconnect path so stopRecording() releases
                            // the failed AudioRecord before a fresh session is attempted.
                            _state.value = NovaOrbState.CONNECTING
                            _statusText.value = "Microphone interrupted; reconnecting..."
                            scheduleReconnect()
                        }
                    }
                }
            }
        )

        scope.launch {
            sessionMutex.withLock {
                connectSession(isResuming = false, expectedEpoch = startEpoch)
            }
        }
    }

    private suspend fun connectSession(isResuming: Boolean, expectedEpoch: Long? = null) {
        val currentContext = appContext ?: return
        val currentEpoch = expectedEpoch ?: sessionEpoch.incrementAndGet()
        if (!_isActive.value || sessionEpoch.get() != currentEpoch) return
        if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(currentContext)) {
            _lastError.value = "Online AI consent is off. The cloud voice session was stopped."
            _state.value = NovaOrbState.ERROR
            _statusText.value = "Online AI disabled"
            stop()
            return
        }
        cancelToolJobs()

        synchronized(clientLock) {
            client?.disconnect()
            client = null
        }
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = if (isResuming) "Reconnecting..." else "Connecting..."

        val ephemeralToken = try {
            `in`.shraddha.ai.future.MainBrainProviderGateway.fetchLiveVoiceToken(currentContext)
        } catch (error: Exception) {
            if (!_isActive.value || sessionEpoch.get() != currentEpoch) return
            val message = error.localizedMessage ?: "Secure Gemini Live connection unavailable"
            _lastError.value = message
            audioIO?.stopRecording()
            val permanentGatewayFailure =
                (error is GatewayRequestException && error.statusCode in 400..499 && error.statusCode != 429) ||
                    message.contains("not configured", ignoreCase = true) ||
                    message.contains("gateway authentication is required", ignoreCase = true) ||
                    message.contains("Set the secure HTTPS backend URL", ignoreCase = true) ||
                    message.contains("could not be read", ignoreCase = true)
            if (permanentGatewayFailure) {
                // Invalid credentials/configuration cannot be repaired by retrying every few seconds.
                // Preserve the visible error, but stop the foreground service: no microphone
                // session is running while setup is blocked, and Start must be available after
                // the user corrects Settings.
                _state.value = NovaOrbState.ERROR
                _statusText.value = "Voice setup failed • check Secure Backend settings"
                VoiceForegroundService.stop(currentContext, currentEpoch)
            } else {
                _state.value = NovaOrbState.CONNECTING
                _statusText.value = "Voice connection unavailable; retrying automatically"
                retryAfterCurrentAttempt()
            }
            return
        }

        // Recheck consent after the network token request. If the user switched
        // Online AI off while it was in flight, do not create a WebSocket or reopen mic.
        if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(currentContext)) {
            _lastError.value = "Online AI consent was revoked during connection. Cloud voice was stopped."
            _state.value = NovaOrbState.ERROR
            _statusText.value = "Online AI disabled"
            stop()
            return
        }

        // The user may stop while the ephemeral token request is in flight. Never
        // create a WebSocket client or restart the microphone for a stale session.
        if (!_isActive.value || sessionEpoch.get() != currentEpoch) return

        val personality = PersonalityRepository.getPersonality(currentContext)
        val language = PersonalityRepository.getLanguage(currentContext)
        val assistantName = PersonalityRepository.getAssistantName(currentContext)
        synchronized(pendingTextLock) { pendingAssistantText.clear() }

        _currentPersonality.value = personality
        _currentLanguage.value = language

        val recap = ConversationMemory.recap()
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
                synchronized(clientLock) {
                    if (_isActive.value && sessionEpoch.get() == currentEpoch) audioIO?.startRecording()
                }
            }

            override fun onAudioChunk(bytes: ByteArray) {
                if (sessionEpoch.get() != currentEpoch) return
                VoiceLatencyTracker.markFirstAudioChunk()

                if (!isTurnActive) {
                    isTurnActive = true
                    bargeInSpeechChunks = 0
                    bargeInOpen = false
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
                synchronized(pendingTextLock) {
                    if (pendingAssistantText.length < 6000) pendingAssistantText.append(text).append(' ')
                }
            }

            override fun onToolCall(id: String, name: String, args: JSONObject) {
                if (sessionEpoch.get() != currentEpoch) return
                _state.value = NovaOrbState.THINKING
                _statusText.value = "Working on it..."

                if (name == "plan_multi_agent_task") {
                    runCatching {
                        val goal = args.optString("goal").trim()
                        require(goal.isNotBlank() && goal.length <= 1000) { "Goal must contain 1-1000 characters" }
                        val (council, session) = `in`.shraddha.ai.future.MainBrain.createCouncilPlan(
                            goal = goal,
                            workflowId = "voice-council-" + java.util.UUID.randomUUID().toString().take(8),
                            title = goal.take(120),
                            context = currentContext
                        )
                        val roles = org.json.JSONArray()
                        council.roles.forEach { role ->
                            roles.put(JSONObject().put("name", role.name).put("responsibility", role.responsibility).put("output", role.output))
                        }
                        val ordered = org.json.JSONArray()
                        session.plan.ordered.forEachIndexed { index, task ->
                            ordered.put(JSONObject().put("step", index + 1).put("id", task.id).put("title", task.title).put("estimatedMinutes", task.estimatedMinutes))
                        }
                        val guardrails = org.json.JSONArray()
                        council.guardrails.forEach { guardrails.put(it) }
                        val response = JSONObject()
                            .put("status", "plan_created")
                            .put("goal", goal)
                            .put("roles", roles)
                            .put("orderedTasks", ordered)
                            .put("guardrails", guardrails)
                            .put("execution", "not_started")
                        val saved = `in`.shraddha.ai.brain.EncryptedFeatureStore(currentContext)
                            .put("voice_agent_council_latest", response.toString())
                        response.put("encryptedLocalSave", saved.isSuccess)
                        if (saved.isFailure) response.put("storageWarning", saved.exceptionOrNull()?.localizedMessage ?: "Encrypted local save failed")
                        response
                    }.getOrElse { error ->
                        JSONObject().put("status", "error").put("message", error.localizedMessage ?: "Multi-agent planning failed")
                    }.also { response ->
                        client?.sendToolResponse(id, name, response)
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                    }
                    return
                }


                val toolJob = scope.launch(start = CoroutineStart.LAZY) {
                    val result = try {
                        `in`.shraddha.ai.future.MainBrain.executeVoiceTool(currentContext, name, args)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        JSONObject().put("status", "error").put("message", error.localizedMessage ?: "Tool execution failed")
                    }
                    if (sessionEpoch.get() == currentEpoch && _isActive.value) {
                        client?.sendToolResponse(id, name, result)
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                    }
                }
                synchronized(toolJobsLock) {
                    if (_isActive.value && sessionEpoch.get() == currentEpoch) {
                        toolJobs.add(toolJob)
                        toolJob.invokeOnCompletion { synchronized(toolJobsLock) { toolJobs.remove(toolJob) } }
                        toolJob.start()
                    } else {
                        toolJob.cancel()
                    }
                }
            }

            override fun onTurnComplete() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                val response = synchronized(pendingTextLock) {
                    pendingAssistantText.toString().trim().also { pendingAssistantText.clear() }
                }
                if (response.isNotBlank()) {
                    ConversationMemory.recordTurn(MemoryRole.ASSISTANT, response)
                    VoiceLatencyTracker.markTurnComplete()
                    speakWithShraddha(currentContext, currentEpoch, assistantName, response)
                } else {
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
            }

            override fun onInterrupted() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                if (isTurnActive) {
                    isTurnActive = false
                    EchoGuard.notifyPlaybackStopped()
                }
                bargeInSpeechChunks = 0
                bargeInOpen = false
                synchronized(pendingTextLock) { pendingAssistantText.clear() }
                audioIO?.flushPlayback()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                _amplitude.value = 0f
            }

            override fun onError(message: String) {
                if (sessionEpoch.get() != currentEpoch) return
                _lastError.value = message
                if (_isActive.value) {
                    audioIO?.stopRecording()
                    scheduleReconnect()
                }
            }

            override fun onClosed() {
                if (sessionEpoch.get() != currentEpoch) return
                if (_isActive.value) {
                    audioIO?.stopRecording()
                    scheduleReconnect()
                }
            }
        }

        val nextClient = GeminiLiveClient(liveListener, assistantName)
        synchronized(clientLock) {
            if (!_isActive.value || sessionEpoch.get() != currentEpoch ||
                !`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(currentContext)) {
                nextClient.disconnect()
                if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(currentContext)) {
                    _lastError.value = "Online AI consent was revoked before voice connection."
                    _state.value = NovaOrbState.ERROR
                    _statusText.value = "Online AI disabled"
                    stop()
                }
                return
            }
            client = nextClient
            nextClient.connect(ephemeralToken, prompt)
        }
    }

    /** Adds the latest camera observation to the active Gemini Live conversation for the next spoken question. */
    fun updateLiveVisualContext(text: String): Boolean {
        val clean = text.trim().take(1200)
        if (clean.isBlank() || !_isActive.value || _state.value != NovaOrbState.LISTENING) return false
        val activeClient = client ?: return false
        if (!activeClient.isLive()) return false
        activeClient.sendText(
            "[LIVE CAMERA CONTEXT UPDATE — NOT A USER COMMAND] The current camera view shows: $clean. " +
                "Use this as the latest visual context when the user asks about what you can see. " +
                "Do not narrate or answer this update; wait for the user's next spoken question."
        )
        return true
    }

    /** Speaks a camera observation through the configured assistant voice when the voice session is active. */
    fun announceVisualObservation(context: Context, text: String) {
        val clean = text.trim().take(1200)
        if (clean.isBlank() || !_isActive.value || _state.value != NovaOrbState.LISTENING) return
        val epoch = sessionEpoch.get()
        scope.launch {
            val assistantName = runCatching {
                PersonalityRepository.getAssistantName(context.applicationContext)
            }.getOrDefault(AssistantBrand.FULL_NAME)
            if (sessionEpoch.get() == epoch && _isActive.value && _state.value == NovaOrbState.LISTENING) {
                speakWithShraddha(context.applicationContext, epoch, assistantName, clean)
            }
        }
    }

    private fun speakWithShraddha(context: Context, epoch: Long, assistantName: String, text: String) {
        _state.value = NovaOrbState.THINKING
        _statusText.value = "Preparing $assistantName's voice..."
        ShraddhaSpeechClient.synthesize(context, text, _detectedLanguage.value) { result ->
            if (sessionEpoch.get() != epoch || !_isActive.value) return@synthesize
            result.onSuccess { pcm ->
                scope.launch {
                    if (sessionEpoch.get() != epoch || !_isActive.value) return@launch
                    isTurnActive = true
                    EchoGuard.notifyPlaybackStarted()
                    _state.value = NovaOrbState.SPEAKING
                    _statusText.value = assistantName + " is speaking..."
                    _amplitude.value = audioIO?.playChunk(pcm) ?: 0f
                    val durationMs = (pcm.size / 48.0).toLong().coerceIn(100L, 60000L)
                    delay(durationMs + 180L)
                    if (sessionEpoch.get() == epoch && isTurnActive) {
                        isTurnActive = false
                        EchoGuard.notifyPlaybackStopped()
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                        _amplitude.value = 0f
                    }
                }
            }.onFailure { error ->
                _lastError.value = error.localizedMessage ?: "Custom voice engine unavailable"
                _state.value = NovaOrbState.ERROR
                _statusText.value = "$assistantName voice engine setup required — no Google voice fallback"
            }
        }
    }

    private fun retryAfterCurrentAttempt() {
        scope.launch {
            delay(1_200)
            if (_isActive.value && reconnectJob?.isActive != true) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!_isActive.value || reconnectJob?.isActive == true) return

        val count = VoiceLatencyTracker.reconnectCount.value
        val backoffMs = (1_200L * (1L shl count.coerceAtMost(5))).coerceAtMost(30_000L)
        VoiceLatencyTracker.incrementReconnect()
        // Never collect microphone audio while disconnected. Keep the user-started
        // session alive and reconnect automatically with bounded exponential backoff.
        audioIO?.stopRecording()
        _amplitude.value = 0f
        _vadActive.value = false
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = "Reconnecting (attempt ${count + 1})..."

        // Keep the user-started voice session alive through transient network/API failures.
        // Backoff is capped at 30 seconds and the session stops only on explicit user stop.
        reconnectJob = scope.launch {
            delay(backoffMs)
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

    private fun cancelToolJobs() {
        synchronized(toolJobsLock) {
            toolJobs.forEach { it.cancel() }
            toolJobs.clear()
        }
    }

    fun stop() {
        val preserveError = _state.value == NovaOrbState.ERROR
        val previousError = _lastError.value
        val previousStatus = _statusText.value
        _isActive.value = false
        sessionEpoch.incrementAndGet()
        audioEpoch.incrementAndGet()
        cancelToolJobs()
        reconnectJob?.cancel()
        reconnectJob = null
        VoiceLatencyTracker.resetReconnect()
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

        synchronized(clientLock) {
            client?.disconnect()
            client = null
            audioIO?.release()
            audioIO = null
        }

        EchoGuard.reset()
        ConversationMemory.clear()
        synchronized(pendingTextLock) { pendingAssistantText.clear() }

        isTurnActive = false
        _state.value = if (preserveError) NovaOrbState.ERROR else NovaOrbState.IDLE
        _statusText.value = if (preserveError) previousStatus else "Ready to talk"
        _amplitude.value = 0f
        _vadActive.value = false
        _muted.value = false
        _lastError.value = if (preserveError) previousError else null
    }
}
