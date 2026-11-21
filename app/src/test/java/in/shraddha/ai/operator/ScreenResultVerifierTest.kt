package `in`.shraddha.ai.operator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenResultVerifierTest {
    @Test fun matchesVisibleTextWithoutCaseSensitivity() {
        val elements = listOf(ScreenTextCandidate("Payment successful", "", false))
        assertTrue(ScreenResultVerifier.containsExpectedText(elements, "payment SUCCESSFUL"))
    }

    @Test fun matchesAccessibilityContentDescription() {
        val elements = listOf(ScreenTextCandidate("", "Send message", false))
        assertTrue(ScreenResultVerifier.containsExpectedText(elements, "send message"))
    }

    @Test fun neverUsesPasswordFieldsAsVerificationEvidence() {
        val elements = listOf(ScreenTextCandidate("123456", "PIN", true))
        assertFalse(ScreenResultVerifier.containsExpectedText(elements, "123456"))
    }

    @Test fun rejectsUnboundedExpectedText() {
        val elements = listOf(ScreenTextCandidate("done", "", false))
        assertFalse(ScreenResultVerifier.containsExpectedText(elements, "x".repeat(121)))
    }

    @Test fun blankExpectedTextDoesNotRequireVisibleEvidence() {
        assertTrue(ScreenResultVerifier.containsExpectedText(emptyList(), "  "))
    }
}
