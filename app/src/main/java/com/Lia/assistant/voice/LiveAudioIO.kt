package com.Lia.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class LiveAudioIO(
    private val onMicChunk: (ByteArray) -> Unit,
    private val onMicAmplitude: (Float) -> Unit,
    private val onRecordingError: (String) -> Unit = {}
) {
    companion object {
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val MIC_CHUNK_SIZE = 2048

        fun calculateRms(pcmBytes: ByteArray): Float {
            if (pcmBytes.size < 2) return 0f
            var sumSquares = 0.0
            val sampleCount = pcmBytes.size / 2
            for (i in 0 until sampleCount) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low
                sumSquares += (sample * sample).toDouble()
            }
            val mean = sumSquares / sampleCount
            val rawRms = sqrt(mean) / 32768.0
            return max(0f, min(1f, rawRms.toFloat()))
        }
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var audioTrack: AudioTrack? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val recordingLock = Any()
    private val speakerLock = Any()
    private var currentVolume = 1.0f

    /** Idempotent: repeated Live API setup callbacks never open a second microphone. */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        synchronized(recordingLock) {
            if (!isRecording.compareAndSet(false, true)) return
            var createdRecord: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                require(minBuf > 0) { "Android returned an invalid microphone buffer size ($minBuf)." }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, 4096)
                )
                createdRecord = record
                check(record.state == AudioRecord.STATE_INITIALIZED) {
                    "Microphone could not be initialized. Check microphone permission and device availability."
                }

                val sessionId = record.audioSessionId
                if (sessionId != 0) {
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {}
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {}
                    try {
                        if (AutomaticGainControl.isAvailable()) {
                            gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {}
                }

                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Android did not start microphone recording."
                }
                audioRecord = record
                recordThread = Thread({
                    val buffer = ByteArray(MIC_CHUNK_SIZE)
                    try {
                        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                            val read = record.read(buffer, 0, MIC_CHUNK_SIZE)
                            if (read > 0) {
                                val chunk = if (read == MIC_CHUNK_SIZE) buffer.clone() else buffer.copyOf(read)
                                try {
                                    onMicAmplitude(calculateRms(chunk))
                                    onMicChunk(chunk)
                                } finally {
                                    chunk.fill(0)
                                }
                            } else if (read < 0 && isRecording.get()) {
                                throw IllegalStateException("Microphone read failed (AudioRecord code $read).")
                            }
                        }
                    } catch (error: Exception) {
                        if (isRecording.compareAndSet(true, false)) {
                            onRecordingError(error.localizedMessage ?: "Microphone recording stopped unexpectedly.")
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }, "LiveAudioIO-MicThread").apply { start() }
            } catch (error: Exception) {
                isRecording.set(false)
                audioRecord = null
                recordThread = null
                releaseEffects()
                runCatching { createdRecord?.stop() }
                runCatching { createdRecord?.release() }
                onRecordingError(error.localizedMessage ?: "Unable to start microphone recording.")
            }
        }
    }

    fun stopRecording() {
        synchronized(recordingLock) {
            isRecording.set(false)
            val threadToStop = recordThread
            val recordToStop = audioRecord
            recordThread = null
            audioRecord = null
            runCatching { recordToStop?.stop() }
            threadToStop?.interrupt()
            if (threadToStop != null && threadToStop !== Thread.currentThread()) {
                try {
                    threadToStop.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            runCatching { recordToStop?.release() }
            releaseEffects()
        }
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    private fun getOrCreateSpeakerLocked(): AudioTrack {
        audioTrack?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuf > 0) { "Android returned an invalid speaker buffer size ($minBuf)." }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SPEAKER_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack(
            attributes,
            format,
            max(minBuf * 4, minBuf),
            AudioTrack.MODE_STREAM,
            0
        )
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Speaker output could not be initialized." }
            track.setVolume(currentVolume)
            track.play()
            audioTrack = track
            return track
        } catch (error: Exception) {
            runCatching { track.release() }
            throw error
        }
    }

    fun playChunk(pcmBytes: ByteArray): Float {
        if (pcmBytes.isEmpty()) return 0f
        return runCatching {
            synchronized(speakerLock) {
                val track = getOrCreateSpeakerLocked()
                var offset = 0
                while (offset < pcmBytes.size) {
                    val written = track.write(pcmBytes, offset, pcmBytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "Speaker playback failed (AudioTrack code $written)." }
                    offset += written
                }
                calculateRms(pcmBytes)
            }
        }.getOrDefault(0f)
    }

    fun flushPlayback() {
        synchronized(speakerLock) {
            audioTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        val clamped = max(0f, min(1f, volume))
        currentVolume = clamped
        synchronized(speakerLock) {
            runCatching { audioTrack?.setVolume(clamped) }
        }
    }

    fun release() {
        stopRecording()
        synchronized(speakerLock) {
            audioTrack?.let { track ->
                runCatching {
                    track.stop()
                    track.release()
                }
            }
            audioTrack = null
        }
    }
}
