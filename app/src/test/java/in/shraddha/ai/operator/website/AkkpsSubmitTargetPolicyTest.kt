package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsSubmitTargetPolicyTest {
    private fun validKeys(keys: List<String>): Boolean {
        val normalized = keys.map { it.trim().lowercase() }
        return normalized.isNotEmpty() && normalized.none { it.isBlank() } &&
            normalized.distinct().size == normalized.size
    }

    @Test fun requiresAtLeastOneNonBlankMappedField() {
        assertTrue(validKeys(listOf("farmer name")))
        assertFalse(validKeys(emptyList()))
        assertFalse(validKeys(listOf(" ")))
    }

    @Test fun rejectsDuplicateMappedFieldKeys() {
        assertFalse(validKeys(listOf("Plot ID", " plot id ")))
        assertTrue(validKeys(listOf("Plot ID", "Village")))
    }
}
