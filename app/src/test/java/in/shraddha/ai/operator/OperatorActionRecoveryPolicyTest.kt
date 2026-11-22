package `in`.shraddha.ai.operator

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorActionRecoveryPolicyTest {
    private fun screen(activity: String, text: String = "") = ScreenSnapshot(
        packageName = "com.example.target",
        activityName = activity,
        capturedAt = 1L,
        elements = if (text.isBlank()) emptyList() else listOf(
            ScreenElement(
                id = "result",
                text = text,
                description = "",
                className = "android.widget.TextView",
                bounds = Rect(0, 0, 1, 1),
                clickable = false,
                editable = false,
                scrollable = false,
                password = false
            )
        )
    )

    @Test fun newlyVisiblePostconditionOnChangedScreenCanVerifyCompletion() {
        val before = screen("ComposeActivity")
        val after = screen("SentActivity", "Message sent successfully")
        assertEquals(
            OperatorActionJournal.Recovery.VERIFIED_COMPLETE,
            OperatorActionJournal.assess(
                "com.example.target",
                OperatorActionJournal.fingerprintForTest(before),
                "Message sent",
                true,
                after
            )
        )
    }

    @Test fun unchangedScreenNeverVerifiesCompletion() {
        val current = screen("ComposeActivity", "Message sent")
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            OperatorActionJournal.assess(
                "com.example.target",
                OperatorActionJournal.fingerprintForTest(current),
                "Message sent",
                true,
                current
            )
        )
    }

    @Test fun missingPostconditionRequiresHumanReview() {
        val before = screen("ComposeActivity")
        val after = screen("ComposeActivity", "Send")
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            OperatorActionJournal.assess(
                "com.example.target",
                OperatorActionJournal.fingerprintForTest(before),
                "Message sent",
                true,
                after
            )
        )
    }

    @Test fun postconditionThatWasAlreadyVisibleBeforeActionIsNotProof() {
        val before = screen("ComposeActivity", "Saved")
        val after = screen("SavedActivity", "Saved")
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            OperatorActionJournal.assess(
                "com.example.target",
                OperatorActionJournal.fingerprintForTest(before),
                "Saved",
                false,
                after
            )
        )
    }

    @Test fun changedScreenInDifferentAppRequiresHumanReview() {
        val before = screen("ComposeActivity")
        val after = screen("SentActivity", "Message sent")
        assertEquals(
            OperatorActionJournal.Recovery.REVIEW_REQUIRED,
            OperatorActionJournal.assess(
                "com.example.other",
                OperatorActionJournal.fingerprintForTest(before),
                "Message sent",
                true,
                after
            )
        )
    }
}
