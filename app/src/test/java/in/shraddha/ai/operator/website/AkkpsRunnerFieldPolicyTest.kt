package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsRunnerFieldPolicyTest {
    @Test fun blocksCredentialUploadAndActionControls() {
        listOf("hidden", "password", "file", "checkbox", "radio", "button", "submit", "reset", "image")
            .forEach { assertTrue("$it must not be auto-filled", it in AkkpsRunnerFieldPolicy.blockedInputTypes) }
    }

    @Test fun leavesOrdinaryTextEntryTypesAvailable() {
        listOf("text", "number", "tel", "email", "date", "search")
            .forEach { assertFalse("$it should remain available", it in AkkpsRunnerFieldPolicy.blockedInputTypes) }
    }
}
