package com.Lia.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechSegmenterTest {
    private fun pcm(samples: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        for (i in 0 until samples) {
            val value = 8_000
            bytes[i * 2] = (value and 0xff).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
    }

    @Test fun ambientSilenceDoesNotAccumulateSamples() {
        val segmenter = OfflineSpeechSegmenter()
        repeat(200) { assertNull(segmenter.accept(pcm(320), 0.0f)) }
        assertEquals(0, segmenter.bufferedSampleCount())
    }

    @Test fun speechAndNineSilenceChunksProduceOneBoundedSegment() {
        val segmenter = OfflineSpeechSegmenter()
        assertNull(segmenter.accept(pcm(4_000), 0.1f))
        repeat(8) {
            assertNull(segmenter.accept(pcm(320), 0.0f))
        }
        val completed = segmenter.accept(pcm(320), 0.0f)
        assertNotNull(completed)
        assertEquals(6_880, completed!!.size)
        assertEquals(0, segmenter.bufferedSampleCount())
    }

    @Test fun maximumDurationClipsAndClosesTheSegment() {
        val segmenter = OfflineSpeechSegmenter(maxSamples = 8_000, minSpeechSamples = 1)
        val completed = segmenter.accept(pcm(10_000), 0.1f)
        assertNotNull(completed)
        assertEquals(8_000, completed!!.size)
        assertEquals(0, segmenter.bufferedSampleCount())
    }

    @Test fun shortNoiseSegmentIsDiscardedAfterSilence() {
        val segmenter = OfflineSpeechSegmenter(minSpeechSamples = 1_000)
        assertNull(segmenter.accept(pcm(100), 0.1f))
        repeat(9) { assertNull(segmenter.accept(pcm(20), 0.0f)) }
        assertEquals(0, segmenter.bufferedSampleCount())
    }

    @Test fun resetDiscardsPartialSpeech() {
        val segmenter = OfflineSpeechSegmenter()
        segmenter.accept(pcm(2_000), 0.1f)
        assertTrue(segmenter.bufferedSampleCount() > 0)
        segmenter.reset()
        assertEquals(0, segmenter.bufferedSampleCount())
    }
}
