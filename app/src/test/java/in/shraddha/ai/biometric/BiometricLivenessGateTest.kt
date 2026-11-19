package `in`.shraddha.ai.biometric

import org.junit.Assert.*
import org.junit.Test

class BiometricLivenessGateTest {
    private val sequence = listOf(BiometricLivenessGate.Step.BLINK, BiometricLivenessGate.Step.TURN_SIDE, BiometricLivenessGate.Step.TURN_OPPOSITE)

    @Test fun stillPhotoCannotPassBlinkAndTurnChallenges() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        repeat(100) { assertFalse(gate.observe(1, 0.95f, 0.96f, 0f, 1_100L + it)) }
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun oneEyeWinkCannotSatisfyBlinkChallenge() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        repeat(10) { index ->
            val time = 1_010L + index * 20L
            assertFalse(gate.observe(1, 0.1f, 0.95f, 0f, time))
            assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, time + 10L))
        }
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun extraFacesCannotAdvanceChallenge() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        repeat(20) { assertFalse(gate.observe(2, 0.1f, 0.1f, -30f, 1_100L + it)) }
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun completeChallengeRequiresBlinkAndHeldOppositeHeadTurns() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_100L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_200L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_240L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f, 1_300L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f, 1_340L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_400L))
        assertTrue(gate.observe(1, 0.95f, 0.95f, 25f, 1_440L))
        assertTrue(gate.isComplete)
    }

    @Test fun expiredChallengeFailsClosed() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 10_000L)
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 55_001L))
        assertTrue(gate.isExpired)
        assertFalse(gate.observe(1, 0.99f, 0.99f, 0f, 55_010L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun challengeSequenceMustContainEachActionOnce() {
        BiometricLivenessGate(listOf(BiometricLivenessGate.Step.BLINK, BiometricLivenessGate.Step.BLINK, BiometricLivenessGate.Step.TURN_SIDE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oppositeTurnCannotBeRequestedBeforeFirstSide() {
        BiometricLivenessGate(listOf(BiometricLivenessGate.Step.TURN_OPPOSITE, BiometricLivenessGate.Step.TURN_SIDE, BiometricLivenessGate.Step.BLINK))
    }

    @Test fun multiFaceFrameResetsConsecutiveBlinkStability() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_010L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_020L))
        assertFalse(gate.observe(2, 0.95f, 0.95f, 0f, 1_030L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_040L))
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_050L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_060L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f, 1_070L))
        assertEquals(BiometricLivenessGate.Step.TURN_SIDE, gate.currentStep)
    }

    @Test fun invalidFaceCountResetsHeldTurnStability() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        gate.observe(1, 0.1f, 0.1f, 0f, 1_010L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_020L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_030L)
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_040L))
        assertFalse(gate.observe(2, 0.95f, 0.95f, 25f, 1_050L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_060L))
        assertEquals(BiometricLivenessGate.Step.TURN_SIDE, gate.currentStep)
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_070L))
        assertEquals(BiometricLivenessGate.Step.TURN_OPPOSITE, gate.currentStep)
    }

    @Test fun nonFiniteFrameCannotAdvanceChallenge() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        assertFalse(gate.observe(1, Float.NaN, 0.95f, 0f, 1_010L))
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun alternatingHeadDirectionsCannotSatisfySideTurn() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        gate.observe(1, 0.1f, 0.1f, 0f, 1_010L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_020L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_030L)
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_040L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f, 1_050L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_060L))
        assertEquals(BiometricLivenessGate.Step.TURN_SIDE, gate.currentStep)
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_070L))
        assertEquals(BiometricLivenessGate.Step.TURN_OPPOSITE, gate.currentStep)
    }

    @Test fun alternatingHeadDirectionsCannotSatisfyOppositeTurn() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        gate.observe(1, 0.1f, 0.1f, 0f, 1_010L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_020L)
        gate.observe(1, 0.95f, 0.95f, 0f, 1_030L)
        gate.observe(1, 0.95f, 0.95f, 25f, 1_040L)
        gate.observe(1, 0.95f, 0.95f, 25f, 1_050L)
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f, 1_060L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 25f, 1_070L))
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f, 1_080L))
        assertEquals(BiometricLivenessGate.Step.TURN_OPPOSITE, gate.currentStep)
        assertTrue(gate.observe(1, 0.95f, 0.95f, -25f, 1_090L))
    }

    @Test fun nonMonotonicFrameTimestampsExpireChallenge() {
        val gate = BiometricLivenessGate(sequence, startedAtMs = 1_000L)
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_020L))
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_010L))
        assertTrue(gate.isExpired)
    }

    @Test fun randomizedDefaultSequenceAlwaysHasValidTurnOrder() {
        repeat(100) { assertNotNull(BiometricLivenessGate().currentStep) }
    }
}
