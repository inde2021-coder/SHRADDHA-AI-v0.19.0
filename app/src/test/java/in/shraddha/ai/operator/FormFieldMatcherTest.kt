package `in`.shraddha.ai.operator

import android.graphics.Rect
import com.Lia.assistant.agent.core.ScreenObservation
import com.Lia.assistant.agent.core.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFieldMatcherTest {
    private fun element(id: String, text: String, top: Int, bottom: Int, editable: Boolean = false) = UiElement(
        id = id, text = text, contentDescription = "", className = if (editable) "EditText" else "TextView",
        bounds = Rect(20, top, 500, bottom), isClickable = false, isEditable = editable, isScrollable = false
    )

    private fun screen(vararg elements: UiElement) = ScreenObservation(
        observationId = "test", packageName = "test.app", windowClass = "Form",
        elements = elements.toList(), screenWidth = 1080, screenHeight = 2400, windowStamp = 1L
    )

    @Test fun mapsVisibleFarmerLabelToFollowingEditableField() {
        val label = element("label", "Farmer Name", 100, 140)
        val input = element("input", "", 150, 210, editable = true)
        val suggestions = FormFieldMatcher.suggest(
            listOf(ExtractedFormValue("farmerName", "Farmer Name", "Mahendra", 0.99f, "ready")),
            screen(label, input)
        )
        assertEquals(1, suggestions.size)
        assertEquals("input", suggestions.first().target.id)
        assertEquals("Mahendra", suggestions.first().value)
        assertFalse(suggestions.first().requiresReview)
    }

    @Test fun sensitiveFieldAlwaysRequiresHumanReview() {
        val suggestions = FormFieldMatcher.suggest(
            listOf(ExtractedFormValue("aadhaarNumber", "Aadhaar Number", "123456789012", 0.99f, "ready")),
            screen(element("label", "Aadhaar Number", 100, 140), element("input", "", 150, 210, true))
        )
        assertTrue(suggestions.single().requiresReview)
    }

    @Test fun ignoresLowConfidenceAndUnreviewedExtraction() {
        val screen = screen(element("label", "Farmer Name", 100, 140), element("input", "", 150, 210, true))
        val values = listOf(
            ExtractedFormValue("farmerName", "Farmer Name", "Guess", 0.75f, "review"),
            ExtractedFormValue("fatherName", "Father Name", "Unknown", 0.99f, "ready")
        )
        assertTrue(FormFieldMatcher.suggest(values, screen).isEmpty())
    }

    @Test fun doesNotGuessTargetWhenNoMatchingLabelIsVisible() {
        val suggestions = FormFieldMatcher.suggest(
            listOf(ExtractedFormValue("plotNumber", "Plot Number", "Plot 1", 0.99f, "ready")),
            screen(element("label", "Survey Number", 100, 140), element("input", "", 150, 210, true))
        )
        assertTrue(suggestions.isEmpty())
    }
}
