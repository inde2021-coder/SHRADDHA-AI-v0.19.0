package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsSubmitTargetPolicyTest {
    @Test fun requiresAtLeastOneNonBlankMappedField() {
        assertTrue(AkkpsSubmitTargetPolicy.hasValidKeys(listOf("farmer name")))
        assertFalse(validKeys(emptyList()))
        assertFalse(validKeys(listOf(" ")))
    }

    @Test fun rejectsDuplicateMappedFieldKeys() {
        assertFalse(validKeys(listOf("Plot ID", " plot id ")))
        assertTrue(validKeys(listOf("Plot ID", "Village")))
    }
}
