package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerFieldPolicyTest {
    @Test fun allowsOnlySingleValueTextLikeControls() {
        listOf("text", "number", "tel", "email", "date", "search", "url", "").forEach {
            assertTrue("$it should be eligible", AkkpsRunnerFieldPolicy.isAllowedInputType(it))
        }
    }

    @Test fun rejectsSensitiveAndActionInputTypes() {
        listOf("hidden", "password", "file", "checkbox", "radio", "button", "submit", "reset", "image", "color", "range", "select-multiple")
            .forEach { assertFalse("$it must not be auto-filled", AkkpsRunnerFieldPolicy.isAllowedInputType(it)) }
    }
    @Test fun allowsOnlyEnabledSingleSelectControls() {
        assertTrue(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = false, disabled = false))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = true, disabled = false))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = false, disabled = true))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = true, disabled = true))
    }

}
