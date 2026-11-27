package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsSubmitTargetPolicyTest {
    @Test fun requiresAtLeastOneNonBlankMappedField() {
        assertTrue(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("farmer name")))
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(emptyList()))
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf(" ")))
    }

    @Test fun rejectsDuplicateMappedFieldKeys() {
        assertFalse(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Plot ID", " plot id ")))
        assertTrue(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("Plot ID", "Village")))
    }
}
