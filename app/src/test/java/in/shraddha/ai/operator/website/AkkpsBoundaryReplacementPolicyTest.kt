package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryReplacementPolicyTest {
    @Test fun onlyAnEmptyEditorCanBeReplacedWithoutConfirmation() {
        assertFalse(AkkpsBoundaryReplacementPolicy.requiresConfirmation(0))
        assertTrue(AkkpsBoundaryReplacementPolicy.requiresConfirmation(1))
        assertTrue(AkkpsBoundaryReplacementPolicy.requiresConfirmation(500))
    }
}
