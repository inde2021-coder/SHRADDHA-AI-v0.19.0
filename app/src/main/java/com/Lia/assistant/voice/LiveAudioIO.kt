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

/**
 * Owns the microphone and speaker streams for one foreground voice session.
 * Recording errors are reported once and all partially-created Android audio
 * resources are released on every failure path.
 */
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
    private val recordingGeneration = RecordingGenerationGate()
    private val recordingErrorReported = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val recordingLock = Any()
    private val speakerLock = Any()
    @Volatile private var currentVolume = 1.0f

    @SuppressLint("MissingPermission")
    fun startRecording() {
        synchronized(recordingLock) {
            if (isRecording.get()) return
            recordingErrorReported.set(false)
            val generation = recordingGeneration.begin()

            val minBuf = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                reportRecordingError("This device does not support the required 16 kHz microphone format.")
                return
            }

            var createdRecord: AudioRecord? = null
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, MIC_CHUNK_SIZE * 2)
                )
                createdRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Android could not initialize the microphone.")
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
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Android did not start microphone recording.")
                }

                audioRecord = record
                recordingErrorReported.set(false)
                isRecording.set(true)
                recordThread = Thread({
                    val buffer = ByteArray(MIC_CHUNK_SIZE)
                    try {
                        while (isRecording.get() && recordingGeneration.isCurrent(generation)) {
                            val read = record.read(buffer, 0, buffer.size)
                            if (read > 0 && isRecording.get() && recordingGeneration.isCurrent(generation)) {
                                val chunk = if (read == buffer.size) buffer.clone() else buffer.copyOf(read)
                                try {
                                    onMicAmplitude(calculateRms(chunk))
                                    onMicChunk(chunk)
                                } catch (callbackError: Exception) {
                                    reportRecordingError(
                                        callbackError.localizedMessage ?: "Voice audio processing failed."
                                    )
                                    break
                                }
                            } else if (
                                read == AudioRecord.ERROR_DEAD_OBJECT ||
                                read == AudioRecord.ERROR_INVALID_OPERATION ||
                                read == AudioRecord.ERROR_BAD_VALUE
                            ) {
                                if (isRecording.get() && recordingGeneration.isCurrent(generation)) {
                                    reportRecordingError("Microphone recording stopped unexpectedly (AudioRecord error $read).")
                                }
                                break
                            }
                        }
                    } catch (readError: Exception) {
                        if (isRecording.get() && recordingGeneration.isCurrent(generation)) {
                            reportRecordingError(readError.localizedMessage ?: "Microphone read failed.")
                        }
                    } finally {
                        if (recordingGeneration.finish(generation)) isRecording.set(false)
                    }
                }, "LiveAudioIO-MicThread").apply { start() }
            } catch (error: Exception) {
                recordingGeneration.finish(generation)
                isRecording.set(false)
                if (audioRecord === createdRecord) audioRecord = null
                try { createdRecord?.stop() } catch (_: Exception) {}
                try { createdRecord?.release() } catch (_: Exception) {}
                releaseEffects()
                reportRecordingError(error.localizedMessage ?: "Unable to open the microphone.")
            }
        }
    }

    private fun reportRecordingError(message: String) {
        if (recordingErrorReported.compareAndSet(false, true)) {
            runCatching { onRecordingError(message) }
        }
    }

    private fun releaseEffects() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    fun stopRecording() {
        synchronized(recordingLock) {
            recordingGeneration.invalidate()
            isRecording.set(false)
            val thread = recordThread
            recordThread = null
            thread?.interrupt()

            val record = audioRecord
            audioRecord = null
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            releaseEffects()
        }
    }

    private fun getOrCreateSpeaker(): AudioTrack {
        synchronized(speakerLock) {
            audioTrack?.let { return it }

            val minBuf = AudioTrack.getMinBufferSize(
                SPEAKER_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBuf > 0) { "This device does not support 24 kHz speaker playback." }

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
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                track.release()
                "Android could not initialize speaker playback."
            }
            try {
                track.setVolume(currentVolume)
                track.play()
                audioTrack = track
                return track
            } catch (error: Exception) {
                runCatching { track.release() }
                throw error
            }
        }
    }

    fun playChunk(pcmBytes: ByteArray): Float {
        if (pcmBytes.isEmpty()) return 0f
        val track = getOrCreateSpeaker()
        try {
            var offset = 0
            while (offset < pcmBytes.size) {
                val written = track.write(
                    pcmBytes,
                    offset,
                    pcmBytes.size - offset,
                    AudioTrack.WRITE_BLOCKING
                )
                if (written < 0) {
                    throw IllegalStateException("Speaker playback failed (AudioTrack error $written).")
                }
                if (written == 0) {
                    throw IllegalStateException("Speaker playback stopped before the audio chunk was fully written.")
                }
                offset += written
            }
        } catch (error: Exception) {
            synchronized(speakerLock) {
                if (audioTrack === track) audioTrack = null
                runCatching { track.release() }
            }
            throw error
        }
        return calculateRms(pcmBytes)
    }

    fun flushPlayback() {
        synchronized(speakerLock) {
            audioTrack?.let { track ->
                try {
                    track.pause()
                    track.flush()
                    track.play()
                } catch (_: Exception) {}
            }
        }
    }

    fun setVolume(volume: Float) {
        val clamped = max(0f, min(1f, volume))
        currentVolume = clamped
        synchronized(speakerLock) {
            audioTrack?.setVolume(clamped)
        }
    }

    fun release() {
        stopRecording()
        synchronized(speakerLock) {
            audioTrack?.let { track ->
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
            audioTrack = null
        }
    }
}
