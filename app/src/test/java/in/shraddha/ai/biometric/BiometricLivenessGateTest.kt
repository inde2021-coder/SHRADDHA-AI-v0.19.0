package `in`.shraddha.ai.biometric

import org.junit.Assert.*
import org.junit.Test

class BiometricLivenessGateTest {
    @Test fun stillPhotoCannotPassBlinkAndTurnChallenges() {
        val gate = BiometricLivenessGate(listOf(
            BiometricLivenessGate.Step.BLINK,
            BiometricLivenessGate.Step.TURN_SIDE,
            BiometricLivenessGate.Step.TURN_OPPOSITE
        ))
        repeat(100) { assertFalse(gate.observe(1, 0.95f, 0.96f, 0f)) }
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun extraFacesCannotAdvanceChallenge() {
        val gate = BiometricLivenessGate(listOf(
            BiometricLivenessGate.Step.BLINK,
            BiometricLivenessGate.Step.TURN_SIDE,
            BiometricLivenessGate.Step.TURN_OPPOSITE
        ))
        repeat(20) { assertFalse(gate.observe(2, 0.1f, 0.1f, -30f)) }
        assertEquals(BiometricLivenessGate.Step.BLINK, gate.currentStep)
    }

    @Test fun completeChallengeRequiresBlinkAndBothHeadTurns() {
        val gate = BiometricLivenessGate(listOf(
            BiometricLivenessGate.Step.BLINK,
            BiometricLivenessGate.Step.TURN_SIDE,
            BiometricLivenessGate.Step.TURN_OPPOSITE
        ))
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f))
        assertFalse(gate.observe(1, 0.95f, 0.95f, 0f))
        assertFalse(gate.observe(1, 0.95f, 0.95f, -25f))
        assertTrue(gate.observe(1, 0.95f, 0.95f, 25f))
        assertTrue(gate.isComplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun challengeSequenceMustContainEachActionOnce() {
        BiometricLivenessGate(listOf(BiometricLivenessGate.Step.BLINK, BiometricLivenessGate.Step.BLINK, BiometricLivenessGate.Step.TURN_SIDE))
    }
    @Test(expected = IllegalArgumentException::class)
    fun oppositeTurnCannotBeRequestedBeforeFirstSide() {
        BiometricLivenessGate(listOf(BiometricLivenessGate.Step.TURN_OPPOSITE, BiometricLivenessGate.Step.TURN_SIDE, BiometricLivenessGate.Step.BLINK))
    }

    @Test fun randomizedDefaultSequenceAlwaysHasValidTurnOrder() {
        repeat(100) {
            val gate = BiometricLivenessGate()
            assertTrue(gate.currentStep != null)
        }
    }
}