package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsRunnerFieldPolicyTest {
    @Test fun allowsOnlyOrdinaryTextEntryTypes() {
        listOf("text", "number", "tel", "email", "date", "search", "url", "")
            .forEach { assertTrue("$it should be eligible", AkkpsRunnerFieldPolicy.isAllowedInputType(it)) }
    }

    @Test fun blocksCredentialUploadAndActionControls() {
        listOf("hidden", "password", "file", "checkbox", "radio", "button", "submit", "reset", "image", "color", "range")
            .forEach {
                assertFalse("$it must not be auto-filled", AkkpsRunnerFieldPolicy.isAllowedInputType(it))
                assertTrue(it in AkkpsRunnerFieldPolicy.blockedInputTypes)
            }
    }
}
