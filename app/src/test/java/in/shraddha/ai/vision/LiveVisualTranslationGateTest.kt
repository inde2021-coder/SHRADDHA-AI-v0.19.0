package `in`.shraddha.ai.vision

import org.junit.Assert.*
import org.junit.Test

class LiveVisualTranslationGateTest {
    @Test fun onlyOneFrameCanBeInFlightAndOldFramesAreThrottled() {
        val gate = LiveVisualTranslationGate(1500)
        gate.start()
        assertTrue(gate.tryBegin(1000))
        assertFalse(gate.tryBegin(3000))
        gate.complete()
        assertFalse(gate.tryBegin(2499))
        assertTrue(gate.tryBegin(2500))
        gate.complete()
    }

    @Test fun stopRejectsFramesAndRestartResetsThrottle() {
        val gate = LiveVisualTranslationGate(1500)
        gate.start()
        assertTrue(gate.tryBegin(100))
        gate.stop()
        assertFalse(gate.isActive())
        assertFalse(gate.tryBegin(5000))
        gate.start()
        assertTrue(gate.tryBegin(5000))
    }

    @Test fun invalidIntervalIsRejected() {
        try {
            LiveVisualTranslationGate(0)
            fail("zero interval must not be accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
