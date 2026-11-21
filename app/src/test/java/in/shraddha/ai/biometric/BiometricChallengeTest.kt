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

    @Test fun livenessGateRequiresOpenBlinkTurnAndReturnToCamera() {
        val gate = BiometricLivenessGate()
        assertFalse(gate.observe(1, 0.9f, 0.9f, 0f))
        assertFalse(gate.observe(1, 0.1f, 0.1f, 0f))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 0f))
        assertFalse(gate.observe(1, 0.9f, 0.9f, 18f))
        assertTrue(gate.observe(1, 0.9f, 0.9f, 0f))
        assertTrue(gate.observe(1, 0.9f, 0.9f, 0f))
    }

    @Test fun livenessGateRejectsMultipleFacesExtremeYawAndNonFiniteSignals() {
        val gate = BiometricLivenessGate()
        assertFalse(gate.observe(2, 0.9f, 0.9f, 0f))
        gate.observe(1, 0.9f, 0.9f, 0f)
        gate.observe(1, 0.1f, 0.1f, 0f)
        assertFalse(gate.observe(1, 0.9f, 0.9f, 80f))
        assertFalse(gate.observe(1, Float.NaN, 0.9f, 0f))
    }
}
