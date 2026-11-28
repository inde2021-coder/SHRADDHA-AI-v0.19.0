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
    @Test fun parsesOnlyExplicitHindiAndEnglishBooleanValuesForToggles() {
        listOf("yes", "true", "on", "1", "हाँ", "हां", "सक्रिय").forEach {
            assertEquals(it, true, AkkpsRunnerFieldPolicy.parseExplicitBoolean(it))
        }
        listOf("no", "false", "off", "0", "नहीं", "नही", "निष्क्रिय").forEach {
            assertEquals(it, false, AkkpsRunnerFieldPolicy.parseExplicitBoolean(it))
        }
        listOf("", "maybe", "yess", "1.0", "unknown").forEach {
            assertNull(it, AkkpsRunnerFieldPolicy.parseExplicitBoolean(it))
        }
    }

    @Test fun allowsOnlyEnabledSingleSelectControls() {
        assertTrue(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = false, disabled = false))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = true, disabled = false))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = false, disabled = true))
        assertFalse(AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = true, disabled = true))
    }

}
