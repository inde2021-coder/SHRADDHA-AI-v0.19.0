package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowStepResultVerificationTest {
    private fun assess(
        expectedText: String = "Saved successfully",
        expectedAbsent: Boolean = true,
        before: String = "before",
        after: String = "after",
        visible: List<String> = listOf("Saved successfully")
    ) = WorkflowStepResultVerification.assess(expectedText, expectedAbsent, before, after, visible)

    @Test fun newlyVisiblePostconditionOnChangedScreenVerifiesStep() {
        assertEquals(WorkflowStepResultVerification.Decision.VERIFIED, assess())
    }

    @Test fun missingPostconditionRequiresReview() {
        assertEquals(WorkflowStepResultVerification.Decision.REVIEW_REQUIRED, assess(visible = listOf("Save")))
    }

    @Test fun preexistingPostconditionIsNotProof() {
        assertEquals(WorkflowStepResultVerification.Decision.REVIEW_REQUIRED, assess(expectedAbsent = false))
    }

    @Test fun unchangedScreenCannotVerifyEvenWhenTextIsVisible() {
        assertEquals(WorkflowStepResultVerification.Decision.REVIEW_REQUIRED, assess(before = "same", after = "same"))
    }

    @Test fun emptyExpectedTextStillRequiresChangedScreen() {
        assertEquals(WorkflowStepResultVerification.Decision.VERIFIED, assess(expectedText = "", visible = emptyList()))
        assertEquals(WorkflowStepResultVerification.Decision.REVIEW_REQUIRED, assess(expectedText = "", before = "same", after = "same"))
    }

    @Test fun blankFingerprintsNeverVerify() {
        assertEquals(WorkflowStepResultVerification.Decision.REVIEW_REQUIRED, assess(before = "", after = "after"))
    }
}
