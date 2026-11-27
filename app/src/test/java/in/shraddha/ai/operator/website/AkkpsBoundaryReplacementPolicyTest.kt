package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryReplacementPolicyTest {
    @Test fun emptyEditorCanAcceptAnImportWithoutReplacementPrompt() {
        assertFalse(AkkpsBoundaryReplacementPolicy.requiresConfirmation(0))
    }

    @Test fun anyExistingEditorPointsRequireExplicitReplacementConfirmation() {
        listOf(1, 2, 3, 50, AkkpsBoundaryGeometry.MAX_POINTS).forEach {
            assertTrue(AkkpsBoundaryReplacementPolicy.requiresConfirmation(it))
        }
    }
}
