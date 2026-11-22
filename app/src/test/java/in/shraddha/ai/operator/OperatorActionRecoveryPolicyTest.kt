package `in`.shraddha.ai.operator

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorActionRecoveryPolicyTest {
    private fun assess(
        expectedPackage: String = "com.example.target",
        beforeFingerprint: String = "before",
        expectedText: String = "Message sent",
        expectedAbsentBefore: Boolean = true,
        currentPackage: String = "com.example.target",
        currentFingerprint: String = "after",
        visibleText: List<String> = listOf("Message sent successfully")
    ) = OperatorActionJournal.assess(
        expectedPackage, beforeFingerprint, expectedText, expectedAbsentBefore,
        currentPackage, currentFingerprint, visibleText
    )

    @Test fun newlyVisiblePostconditionOnChangedScreenCanVerifyCompletion() {
        assertEquals(OperatorActionJournal.Recovery.VERIFIED_COMPLETE, assess())
    }

    @Test fun unchangedScreenNeverVerifiesCompletion() {
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            assess(beforeFingerprint = "same", currentFingerprint = "same")
        )
    }

    @Test fun missingPostconditionRequiresHumanReview() {
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            assess(visibleText = listOf("Compose", "Send"))
        )
    }

    @Test fun postconditionAlreadyVisibleBeforeActionIsNotProof() {
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            assess(expectedText = "Saved", expectedAbsentBefore = false, visibleText = listOf("Saved"))
        )
    }

    @Test fun changedScreenInDifferentAppRequiresHumanReview() {
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            assess(currentPackage = "com.example.other")
        )
    }

    @Test fun emptyPostconditionNeverAuthorizesResume() {
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            assess(expectedText = "", visibleText = listOf("Anything"))
        )
    }
}
