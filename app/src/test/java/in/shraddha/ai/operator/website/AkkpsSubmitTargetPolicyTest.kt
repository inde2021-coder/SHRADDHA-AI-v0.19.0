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

    @Test fun rejectsEmptyAndBlankKeys() {
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(emptyList()))
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Name", " ")))
    }
}
