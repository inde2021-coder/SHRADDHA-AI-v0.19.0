package `in`.shraddha.ai.biometric

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricVoiceChallengeTest {
    @Test fun exactPhraseMatchesAfterCaseAndPunctuationNormalization() {
        assertTrue(BiometricVoiceChallenge.matches("Blue sky is clear", "BLUE sky is clear!"))
        assertTrue(BiometricVoiceChallenge.matches("नीला आकाश साफ है", "नीला आकाश साफ है।"))
    }

    @Test fun substringOrNegatedPhraseDoesNotPass() {
        assertFalse(BiometricVoiceChallenge.matches("blue sky is clear", "not blue sky is clear"))
        assertFalse(BiometricVoiceChallenge.matches("blue sky is clear", "blue sky"))
        assertFalse(BiometricVoiceChallenge.matches("blue sky is clear", "the blue sky is clear"))
    }

    @Test fun blankChallengeNeverPasses() {
        assertFalse(BiometricVoiceChallenge.matches("   ", "   "))
    }
}
