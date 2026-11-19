package `in`.shraddha.ai.biometric

import org.junit.Assert.*
import org.junit.Test

class BiometricVoiceChallengeTest {
    @Test fun acceptsExactPhraseWithCaseAndPunctuationDifferences() {
        assertTrue(BiometricVoiceChallenge.matches("blue river seven", "Blue river seven."))
    }

    @Test fun rejectsWrongPhraseAndEmptyTranscript() {
        assertFalse(BiometricVoiceChallenge.matches("blue river seven", "blue river eight"))
        assertFalse(BiometricVoiceChallenge.matches("blue river seven", ""))
    }

    @Test fun doesNotAcceptPartialPhrase() {
        assertFalse(BiometricVoiceChallenge.matches("silver mango twenty", "silver mango"))
    }
    @Test fun acceptsHindiPhraseWithoutStrippingDevanagari() {
        assertTrue(BiometricVoiceChallenge.matches("नीला आसमान सात", "नीला आसमान सात।"))
    }

    @Test fun rejectsDifferentHindiPhrase() {
        assertFalse(BiometricVoiceChallenge.matches("नीला आसमान सात", "नीला आसमान आठ"))
    }

}