package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsAiSubmitGateTest {
    private fun maySubmit(aiRequested: Boolean, userExplicitlyRequested: Boolean, missing: List<String>): Boolean =
        aiRequested && userExplicitlyRequested && missing.none { it.isNotBlank() }

    @Test fun submitRequiresAiAndExplicitUserRequest() {
        assertTrue(maySubmit(true, true, emptyList()))
        assertFalse(maySubmit(false, true, emptyList()))
        assertFalse(maySubmit(true, false, emptyList()))
    }

    @Test fun anyMissingInformationBlocksSubmit() {
        assertFalse(maySubmit(true, true, listOf("farmer phone")))
        assertFalse(maySubmit(true, true, listOf("plot number", "village")))
    }

    @Test fun blankMissingEntriesDoNotBlockSubmit() {
        assertTrue(maySubmit(true, true, listOf("", "  ")))
    }
}
