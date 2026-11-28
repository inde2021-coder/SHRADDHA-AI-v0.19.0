package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsSubmitTargetPolicyTest {
    @Test fun rejectsDuplicateKeysAfterNormalization() {
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Farmer Name", "farmer-name")))
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("नाम", "  नाम ")))
    }

    @Test fun acceptsDistinctNonblankKeys() {
        assertTrue(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Farmer Name", "Village")))
    }

    @Test fun recognizesFinalSubmitButtonsButNotDraftButtons() {
        listOf("Submit", "Submit!", "Submit Report", "Submit Plot", "Submit Registration", "सबमिट करें", "जमा करें").forEach {
            assertTrue(it, AkkpsSubmitTargetPolicy.isExplicitSubmitLabel(it))
        }
        listOf("Save Draft", "Save", "Add visit", "Remove", "Cancel", "").forEach {
            assertFalse(it, AkkpsSubmitTargetPolicy.isExplicitSubmitLabel(it))
        }
    }

    @Test fun rejectsEmptyAndBlankKeys() {
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(emptyList()))
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Name", " ")))
    }
}
