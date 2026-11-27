package com.Lia.assistant.voice

/**
 * Bounded PCM segmenter for offline speech recognition.
 * Silence before speech is discarded; a short silence tail is retained to close utterances.
 */
internal class OfflineSpeechSegmenter(
    private val maxSamples: Int = 16_000 * 18,
    private val silenceChunksToEnd: Int = 9,
    private val speechRmsThreshold: Float = 0.018f,
    private val minSpeechSamples: Int = 4_000
) {
    private val samples = ArrayList<Float>(minOf(maxSamples, 16_000 * 5))
    private var speechStarted = false
    private var silentChunks = 0

    init {
        require(maxSamples > 0)
        require(silenceChunksToEnd > 0)
        require(speechRmsThreshold >= 0f)
        require(minSpeechSamples in 1..maxSamples)
    }

    @Synchronized
    fun accept(pcm16: ByteArray, rms: Float): FloatArray? {
        if (pcm16.size < 2 || !rms.isFinite()) return null
        if (rms >= speechRmsThreshold) {
            if (!speechStarted) {
                samples.clear()
                speechStarted = true
            }
            silentChunks = 0
        } else if (speechStarted) {
            silentChunks++
        } else {
            // Do not retain ambient silence while waiting for the first speech frame.
            return null
        }

        var index = 0
        while (index + 1 < pcm16.size && samples.size < maxSamples) {
            val sample = ((pcm16[index + 1].toInt() shl 8) or (pcm16[index].toInt() and 0xff)).toShort()
            samples.add(sample / 32768.0f)
            index += 2
        }

        if (silentChunks >= silenceChunksToEnd || samples.size >= maxSamples) {
            val completed = if (samples.size >= minSpeechSamples) samples.toFloatArray() else null
            resetInternal()
            return completed
        }
        return null
    }

    @Synchronized
    fun reset() = resetInternal()

    @Synchronized
    internal fun bufferedSampleCount(): Int = samples.size

    private fun resetInternal() {
        for (index in samples.indices) samples[index] = 0f
        samples.clear()
        speechStarted = false
        silentChunks = 0
    }
}
