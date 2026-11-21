package com.Lia.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VoiceLatencyTrackerTest {

    @Before
    fun setup() {
        VoiceLatencyTracker.reset()
    }

    @Test
    fun testInOrderMarksProduceCorrectDelta() {
        VoiceLatencyTracker.markUserTurnStart(1000L)
        val delta = VoiceLatencyTracker.markFirstAudioChunk(1350L)
        assertEquals(350L, delta)
        assertEquals(350L, VoiceLatencyTracker.lastTurnLatencyMs.value)
    }

    @Test
    fun testOutOfOrderMarksIgnored() {
        VoiceLatencyTracker.markUserTurnStart(2000L)
        // Timestamp before start time should be ignored
        val delta = VoiceLatencyTracker.markFirstAudioChunk(1500L)
        assertEquals(0L, delta)
        assertEquals(0L, VoiceLatencyTracker.lastTurnLatencyMs.value)
    }

    @Test
    fun reconnectBackoffGrowsAndCapsAtThirtySeconds() {
        assertEquals(1_200L, VoiceLatencyTracker.reconnectDelayMs(0))
        assertEquals(2_400L, VoiceLatencyTracker.reconnectDelayMs(1))
        assertEquals(4_800L, VoiceLatencyTracker.reconnectDelayMs(2))
        assertEquals(9_600L, VoiceLatencyTracker.reconnectDelayMs(3))
        assertEquals(19_200L, VoiceLatencyTracker.reconnectDelayMs(4))
        assertEquals(30_000L, VoiceLatencyTracker.reconnectDelayMs(5))
        assertEquals(30_000L, VoiceLatencyTracker.reconnectDelayMs(99))
    }

    @Test
    fun testReconnectCounter() {
        assertEquals(0, VoiceLatencyTracker.reconnectCount.value)
        VoiceLatencyTracker.incrementReconnect()
        VoiceLatencyTracker.incrementReconnect()
        assertEquals(2, VoiceLatencyTracker.reconnectCount.value)
        VoiceLatencyTracker.resetReconnect()
        assertEquals(0, VoiceLatencyTracker.reconnectCount.value)
    }
}
