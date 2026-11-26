package com.Lia.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shraddha's dedicated speech output client.
 * The gateway proxies to a self-hosted, licensed bilingual voice model and returns
 * mono PCM signed-16 little-endian audio at 24 kHz. There is deliberately no Google
 * or Android system-TTS fallback.
 */
object ShraddhaSpeechClient {
    @Volatile private var playbackTrack: AudioTrack? = null
    @Volatile private var activeSynthesisCall: Call? = null

    /** Plays the configured custom Shraddha PCM voice. No Android or Google TTS is used. */
    fun speak(context: Context, text: String, language: String, done: (Result<Unit>) -> Unit = {}) {
        synthesize(context, text, language) { result ->
            result.fold(
                onSuccess = { pcm ->
                    val onlineAiAllowed = `in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)
                    if (!onlineAiAllowed) {
                        done(Result.failure(IllegalStateException("Online AI was disabled while speech was being prepared. Audio was not played.")))
                        return@fold
                    }
                    runCatching {
                        stopPlayback()
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(24_000)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(pcm.size)
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build()
                        check(track.state == AudioTrack.STATE_INITIALIZED) { "Shraddha audio output could not be initialized" }
                        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        check(written == pcm.size) { "Shraddha audio output was incomplete" }
                        playbackTrack = track
                        track.setNotificationMarkerPosition(pcm.size / 2)
                        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                            override fun onMarkerReached(audioTrack: AudioTrack) {
                                if (playbackTrack === audioTrack) playbackTrack = null
                                runCatching { audioTrack.stop() }
                                audioTrack.release()
                            }
                            override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
                        })
                        track.play()
                    }.fold(
                        onSuccess = { done(Result.success(Unit)) },
                        onFailure = { error ->
                            stopPlayback()
                            done(Result.failure(error))
                        }
                    )
                },
                onFailure = { done(Result.failure(it)) }
            )
        }
    }

    fun stopPlayback() {
        // Also cancel any cloud synthesis still in flight; revoking consent must
        // stop pending audio generation, not only silence an already-playing track.
        val pending = activeSynthesisCall
        activeSynthesisCall = null
        pending?.cancel()
        val current = playbackTrack
        playbackTrack = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun synthesize(
        context: Context,
        text: String,
        language: String,
        done: (Result<ByteArray>) -> Unit
    ) {
        val clean = text.trim().take(6000)
        if (clean.isBlank()) {
            done(Result.failure(IllegalArgumentException("Speech text is empty")))
            return
        }
        val onlineAiAllowed = `in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)
        if (!onlineAiAllowed) {
            done(Result.failure(IllegalStateException("Online AI is disabled. Enable Allow Online AI in Settings > Connections to use cloud speech.")))
            return
        }
        runCatching {
            `in`.shraddha.ai.future.CouncilCostBudget.requireUsageCountersAvailable(
                context.applicationContext,
                "Custom Shraddha text-to-speech"
            )
        }.onFailure {
            done(Result.failure(it))
            return
        }
        val config = com.Lia.assistant.data.VoiceGatewaySettings.get(context)
        if (config.readError != null) {
            done(Result.failure(IllegalStateException(config.readError)))
            return
        }
        if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) {
            done(Result.failure(IllegalStateException("Secure voice backend is not configured")))
            return
        }
        val body = JSONObject()
            .put("text", clean)
            .put("language", LanguageDetector.toVoiceModelLanguage(language))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/voice/synthesize")
            .header("Authorization", "Bearer ${config.gatewayToken}")
            .post(body)
            .build()
        // Final synchronous consent check after configuration and request construction.
        if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)) {
            done(Result.failure(IllegalStateException("Online AI consent was revoked before speech dispatch.")))
            return
        }
        val call = http.newCall(request)
        activeSynthesisCall?.cancel()
        activeSynthesisCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (activeSynthesisCall === call) activeSynthesisCall = null
                done(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (activeSynthesisCall === call) activeSynthesisCall = null
                response.use {
                    if (!it.isSuccessful) {
                        val message = it.body?.string().orEmpty().take(300)
                        done(Result.failure(IllegalStateException("Shraddha voice engine unavailable (HTTP ${it.code}): ${message.ifBlank { "Check the configured custom TTS service." }}")))
                        return
                    }
                    val type = it.header("Content-Type").orEmpty().lowercase()
                    if (!type.contains("audio/pcm") && !type.contains("application/octet-stream")) {
                        done(Result.failure(IllegalStateException("Voice server returned an unsupported audio format")))
                        return
                    }
                    val bytes = it.body?.bytes() ?: ByteArray(0)
                    val onlineAiStillAllowed = `in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)
                    if (!onlineAiStillAllowed) {
                        done(Result.failure(IllegalStateException("Online AI was disabled while speech was in progress. The returned audio was discarded.")))
                        return
                    }
                    if (bytes.size !in 2..3_000_000 || bytes.size % 2 != 0) {
                        done(Result.failure(IllegalStateException("Voice server returned invalid PCM audio")))
                        return
                    }
                    done(Result.success(bytes))
                }
            }
        })
    }
}
