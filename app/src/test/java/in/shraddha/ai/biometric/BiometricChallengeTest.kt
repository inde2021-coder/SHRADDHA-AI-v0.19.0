package `in`.shraddha.ai.biometric

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricChallengeTest {
    @Test fun voiceChallengeNormalizesWhitespaceAndPunctuation() {
        assertTrue(BiometricVoiceChallenge.matches("Blue River Seven", "blue, river   seven"))
        assertTrue(BiometricVoiceChallenge.matches("नीला आसमान सात", "नीला, आसमान सात"))
        assertFalse(BiometricVoiceChallenge.matches("blue river seven", "blue river eight"))
        assertFalse(BiometricVoiceChallenge.matches("", ""))
    }

    @Test fun livenessGateRequiresBlinkAndOppositeHeldHeadTurns() {
        val gate = BiometricLivenessGate(
            sequence = listOf(
                BiometricLivenessGate.Step.BLINK,
                BiometricLivenessGate.Step.TURN_SIDE,
                BiometricLivenessGate.Step.TURN_OPPOSITE
            ),
            startedAtMs = 1_000L
        )
        assertFalse(gate.observe(1, 0.9f, 0.9f, 0f, 1_001L))
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f, 1_002L))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 0f, 1_003L))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 0f, 1_004L))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 20f, 1_005L))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 20f, 1_006L))
        assertFalse(gate.observe(1, 0.9f, 0.9f, -20f, 1_007L))
        assertTrue(gate.observe(1, 0.9f, 0.9f, -20f, 1_008L))
    }

    @Test fun livenessGateRejectsMultipleFacesExtremeYawAndNonFiniteSignals() {
        val gate = BiometricLivenessGate(
            sequence = listOf(
                BiometricLivenessGate.Step.BLINK,
                BiometricLivenessGate.Step.TURN_SIDE,
                BiometricLivenessGate.Step.TURN_OPPOSITE
            ),
            startedAtMs = 2_000L
        )
        assertFalse(gate.observe(2, 0.9f, 0.9f, 0f, 2_001L))
        gate.observe(1, 0.9f, 0.9f, 0f, 2_002L)
        gate.observe(1, 0.1f, 0.1f, 0f, 2_003L)
        assertFalse(gate.observe(1, 0.9f, 0.9f, 80f, 2_004L))
        assertFalse(gate.observe(1, Float.NaN, 0.9f, 0f, 2_005L))
    }
}
