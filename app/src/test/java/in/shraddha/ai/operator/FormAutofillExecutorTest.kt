package `in`.shraddha.ai.operator

import android.graphics.Rect
import com.Lia.assistant.agent.core.CapturedScreen
import com.Lia.assistant.agent.core.DriverResult
import com.Lia.assistant.agent.core.ScreenDriver
import com.Lia.assistant.agent.core.ScreenObservation
import com.Lia.assistant.agent.core.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormAutofillExecutorTest {
    private fun element(id: String, text: String, top: Int, bottom: Int, editable: Boolean = false) = UiElement(
        id = id,
        text = text,
        contentDescription = "",
        className = if (editable) "EditText" else "TextView",
        bounds = Rect(20, top, 500, bottom),
        isClickable = false,
        isEditable = editable,
        isScrollable = false
    )

    private fun screen(timestamp: Long, vararg elements: UiElement) = CapturedScreen(
        rawElements = elements.toList(),
        packageName = "test.app",
        windowClass = "Form",
        width = 1080,
        height = 2400,
        timestamp = timestamp
    )

    private class SequenceDriver(
        private val snapshots: List<CapturedScreen?>,
        var writeResult: DriverResult = DriverResult.OK
    ) : ScreenDriver {
        var captureCount = 0
        val writes = mutableListOf<Pair<UiElement, String>>()

        override suspend fun capture(): CapturedScreen? {
            val result = snapshots.getOrNull(captureCount.coerceAtMost(snapshots.lastIndex))
            captureCount++
            return result
        }

        override suspend fun clickElement(observation: ScreenObservation, element: UiElement) = DriverResult.FAILED
        override suspend fun gestureTap(x: Float, y: Float) = DriverResult.FAILED
        override suspend fun setText(element: UiElement, text: String): DriverResult {
            writes += element to text
            return writeResult
        }
        override suspend fun scroll(direction: String) = DriverResult.FAILED
        override suspend fun back() = DriverResult.FAILED
        override suspend fun home() = DriverResult.FAILED
    }

    private val farmerName = ExtractedFormValue(
        key = "farmerName",
        label = "Farmer Name",
        value = "Mahendra",
        confidence = 0.99f,
        status = "ready"
    )

    @Test fun fillsOnlyApprovedFieldAndVerifiesFreshSnapshot() = runTest {
        val target = element("name-input", "", 150, 210, editable = true)
        val driver = SequenceDriver(
            listOf(
                screen(1, element("name-label", "Farmer Name", 100, 140), target),
                screen(2, element("name-label", "Farmer Name", 100, 140), target.copy(text = "Mahendra"))
            )
        )

        val result = FormAutofillExecutor(driver).applyApproved(listOf(farmerName), setOf("farmerName"))

        assertEquals(1, driver.writes.size)
        assertEquals("Mahendra", driver.writes.single().second)
        assertTrue(result.single().success)
        assertEquals(2, driver.captureCount)
    }

    @Test fun performsNoCaptureOrWriteWhenNothingWasApproved() = runTest {
        val driver = SequenceDriver(emptyList())

        val result = FormAutofillExecutor(driver).applyApproved(listOf(farmerName), emptySet())

        assertTrue(result.isEmpty())
        assertEquals(0, driver.captureCount)
        assertTrue(driver.writes.isEmpty())
    }

    @Test fun doesNotGuessWhenMatchingLabelIsMissing() = runTest {
        val driver = SequenceDriver(
            listOf(screen(1, element("other-label", "Survey Number", 100, 140), element("input", "", 150, 210, true)))
        )

        val result = FormAutofillExecutor(driver).applyApproved(listOf(farmerName), setOf("farmerName"))

        assertFalse(result.single().success)
        assertTrue(driver.writes.isEmpty())
    }

    @Test fun reportsFailureWhenEnteredValueCannotBeVerified() = runTest {
        val target = element("name-input", "", 150, 210, editable = true)
        val driver = SequenceDriver(
            listOf(
                screen(1, element("name-label", "Farmer Name", 100, 140), target),
                screen(2, element("name-label", "Farmer Name", 100, 140), target.copy(text = "Different value"))
            )
        )

        val result = FormAutofillExecutor(driver).applyApproved(listOf(farmerName), setOf("farmerName"))

        assertFalse(result.single().success)
        assertEquals("Could not verify the entered value", result.single().message)
    }

    @Test fun doesNotClaimSuccessWhenAccessibilityWriteFails() = runTest {
        val target = element("name-input", "", 150, 210, editable = true)
        val driver = SequenceDriver(
            listOf(screen(1, element("name-label", "Farmer Name", 100, 140), target)),
            writeResult = DriverResult.FAILED
        )

        val result = FormAutofillExecutor(driver).applyApproved(listOf(farmerName), setOf("farmerName"))

        assertFalse(result.single().success)
        assertEquals(1, driver.captureCount)
    }
}
