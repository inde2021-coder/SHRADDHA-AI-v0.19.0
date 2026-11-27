package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsAiSubmitGateTest {
    @Test fun submitRequiresAiAndExplicitUserRequest() {
        assertTrue(AkkpsAiSubmitPolicy.maySubmit(true, true, emptyList()))
        assertFalse(AkkpsAiSubmitPolicy.maySubmit(false, true, emptyList()))
        assertFalse(AkkpsAiSubmitPolicy.maySubmit(true, false, emptyList()))
    }

    @Test fun anyMissingInformationBlocksSubmit() {
        assertFalse(AkkpsAiSubmitPolicy.maySubmit(true, true, listOf("farmer phone")))
        assertFalse(AkkpsAiSubmitPolicy.maySubmit(true, true, listOf("plot number", "village")))
    }

    @Test fun blankMissingEntriesDoNotBlockSubmit() {
        assertTrue(AkkpsAiSubmitPolicy.maySubmit(true, true, listOf("", "  ")))
    }

    @Test fun duplicateOrBlankFieldKeysAreRejected() {
        assertTrue(AkkpsAiSubmitPolicy.hasDuplicateFields(listOf("name", " Name ")))
        assertTrue(AkkpsAiSubmitPolicy.hasDuplicateFields(listOf("name", " ")))
        assertFalse(AkkpsAiSubmitPolicy.hasDuplicateFields(listOf("name", "village")))
    }
}
