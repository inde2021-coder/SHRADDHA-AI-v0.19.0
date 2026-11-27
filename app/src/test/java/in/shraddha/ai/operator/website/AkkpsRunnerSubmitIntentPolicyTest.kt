package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerSubmitIntentPolicyTest {
    @Test fun acceptsExplicitTrailingEnglishSubmitCommands() {
        listOf("Fill farmer details, submit", "नाम राम; save", "send", "submit now", "save record").forEach {
            assertTrue(it, AkkpsRunnerSubmitIntentPolicy.isExplicit(it))
        }
    }

    @Test fun acceptsExplicitTrailingHindiAndHinglishCommands() {
        listOf("नाम राम, जमा करो", "किसान विवरण भरें; जमा करें", "सबमिट करें", "भेज दें", "सेव करें", "सहेजें").forEach {
            assertTrue(it, AkkpsRunnerSubmitIntentPolicy.isExplicit(it))
        }
    }

    @Test fun doesNotTreatMentionOfSubmitInMiddleAsApproval() {
        listOf("submit बाद में करना", "जमा राशि दर्ज करें", "सबमिट करने से पहले जाँचें", "भेजो मत").forEach {
            assertFalse(it, AkkpsRunnerSubmitIntentPolicy.isExplicit(it))
        }
    }

    @Test fun normalizesFullWidthAndRejectsBlankOrOversizedInput() {
        assertTrue(AkkpsRunnerSubmitIntentPolicy.isExplicit("नाम RAM，ｓｕｂｍｉｔ"))
        assertFalse(AkkpsRunnerSubmitIntentPolicy.isExplicit(" "))
        assertFalse(AkkpsRunnerSubmitIntentPolicy.isExplicit("x".repeat(4_001)))
    }
}
